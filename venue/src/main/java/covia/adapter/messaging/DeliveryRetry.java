package covia.adapter.messaging;

import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.webhook.WebhookInbox;
import covia.api.Fields;
import covia.exception.AuthException;

/**
 * Bounded retry for inbound session delivery: handing a durable receipt's
 * message to a local agent session ({@link ConversationRouter#deliver}).
 *
 * <p>A delivery commits atomically or not at all, so a failed attempt is safe
 * to repeat — but only briefly. Retries back off, doubling from the backoff,
 * and must start within the window measured from the receipt's first attempt.
 * When no attempt remains in the window, or the message can never be delivered
 * (agent missing, suspended or not admitting the bot's owner; invalid input),
 * the receipt becomes FAILED: terminal, kept for the operator and never retried,
 * including after restart. Each attempt is recorded on the receipt before it runs, so
 * neither a restart nor a crash mid-attempt resets the window. No delivery work
 * continues past the window while the recipient is not operational.</p>
 */
public final class DeliveryRetry {
	public static final String WINDOW_EXHAUSTED = "Delivery did not succeed within its retry window";
	public static final String UNDELIVERABLE = "The recipient cannot take this message";

	/** What one pass over a receipt did. */
	public enum Outcome {
		/** The message was handed over, now or by an earlier pass. */
		DELIVERED,
		/** Not delivered yet: pass again after {@link Result#waitMillis()}. */
		WAITING,
		/** This pass ended the receipt as FAILED. */
		FAILED,
		/** Nothing to do: the receipt is not PENDING, or the binding stopped. */
		SKIPPED
	}

	/** One pass's outcome, with the receipt as this pass left it (null when skipped). */
	public record Result(Outcome outcome, long waitMillis, AMap<AString, ACell> receipt) {
		/** The FAILED receipt's reason, one of this class's or its binding's constants. */
		public String reason() { return text(Fields.REASON); }
		/** The error of the latest failed attempt, or the refusal, if any. */
		public String error() { return text(WebhookInbox.LAST_ERROR); }
		private String text(AString field) {
			AString value = receipt == null ? null : RT.ensureString(receipt.get(field));
			return value == null ? null : value.toString();
		}
	}

	/** The recipient side of an attempt. */
	public interface Target {
		/** Null when the recipient can take the message now; otherwise why not. A refusal is never retried. */
		String refusal();
		/** Hand the message over with its receipt. A throw means nothing committed, unless the receipt is no longer PENDING. */
		void deliver();
	}

	/** Runs a receipt write within the binding's lifecycle; returns null, without writing, once it has stopped. */
	public interface Guard {
		AMap<AString, ACell> write(Supplier<AMap<AString, ACell>> write);
	}

	private static final Result SKIPPED = new Result(Outcome.SKIPPED, 0, null);

	private final long windowMillis;
	private final long backoffMillis;

	public DeliveryRetry(long windowMillis, long backoffMillis) {
		this.windowMillis = windowMillis;
		this.backoffMillis = Math.max(1, backoffMillis);
	}

	/** Milliseconds until the receipt's next attempt is due (0 = now), or -1 when no attempt remains within its window. */
	public long due(AMap<AString, ACell> receipt, long now) {
		long attempts = number(receipt, WebhookInbox.ATTEMPTS);
		if (attempts <= 0) return 0;
		long first = number(receipt, WebhookInbox.FIRST_ATTEMPT_AT);
		long next = number(receipt, WebhookInbox.ATTEMPTED_AT) + backoff(attempts);
		return Math.max(next, now) - first >= windowMillis ? -1 : Math.max(0, next - now);
	}

	/** The wait after the n-th attempt: doubling from the backoff, never past the window. */
	private long backoff(long attempts) {
		long delay = backoffMillis;
		for (long i = 1; i < attempts && delay < windowMillis; i++) delay *= 2;
		return delay;
	}

	/** One pass over a receipt: attempt delivery when it is due, otherwise wait, or fail it once nothing remains. */
	public Result attempt(WebhookInbox inbox, String id, Guard guard, Target target) {
		AMap<AString, ACell> receipt = inbox.get(id);
		if (receipt == null || !WebhookInbox.PENDING.equals(receipt.get(Fields.STATUS))) return SKIPPED;
		long now = System.currentTimeMillis();
		long wait = due(receipt, now);
		if (wait < 0) return fail(inbox, id, guard, WINDOW_EXHAUSTED, null);
		if (wait > 0) return new Result(Outcome.WAITING, wait, receipt);
		if (guard.write(() -> inbox.attempt(id, now)) == null) return SKIPPED;
		try {
			String refusal = target.refusal();
			if (refusal != null) return fail(inbox, id, guard, UNDELIVERABLE, refusal);
			target.deliver();
			return new Result(Outcome.DELIVERED, 0, inbox.get(id));
		} catch (RuntimeException failure) {
			String error = AAdapter.describeFailure(failure);
			// A refusal now explains the failure, e.g. an agent suspended mid-attempt.
			String refusal = permanent(failure) ? error : refusalOf(target);
			AMap<AString, ACell> current = inbox.get(id);
			if (current == null) return SKIPPED;
			// The input committed although the call failed afterwards (the durability barrier).
			if (WebhookInbox.COMPLETE.equals(current.get(Fields.STATUS))) return new Result(Outcome.DELIVERED, 0, current);
			if (!WebhookInbox.PENDING.equals(current.get(Fields.STATUS))) return SKIPPED;
			if (refusal != null) return fail(inbox, id, guard, UNDELIVERABLE, refusal);
			long next = due(current, System.currentTimeMillis());
			if (next < 0) return fail(inbox, id, guard, WINDOW_EXHAUSTED, error);
			AMap<AString, ACell> noted = guard.write(() -> inbox.attemptFailed(id, error));
			return noted == null ? SKIPPED : new Result(Outcome.WAITING, next, noted);
		}
	}

	private static Result fail(WebhookInbox inbox, String id, Guard guard, String reason, String error) {
		long now = System.currentTimeMillis();
		AMap<AString, ACell> failed = guard.write(() -> inbox.fail(id, reason, error, now));
		return failed == null ? SKIPPED : new Result(Outcome.FAILED, 0, failed);
	}

	/** Authorisation and invalid-argument failures recur on every attempt. */
	static boolean permanent(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof AuthException || t instanceof IllegalArgumentException) return true;
		}
		return false;
	}

	private static String refusalOf(Target target) {
		try {
			return target.refusal();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static long number(AMap<AString, ACell> receipt, AString field) {
		return receipt.get(field) instanceof CVMLong n ? n.longValue() : 0;
	}
}
