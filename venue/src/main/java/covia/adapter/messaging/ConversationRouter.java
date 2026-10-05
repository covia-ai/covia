package covia.adapter.messaging;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Provider-independent Job routing and per-conversation ordering. Transport
 * authentication/admission must happen before enqueueing. This in-memory queue
 * is not a durable inbox and must not be used as a webhook acknowledgement.
 */
public final class ConversationRouter {
	private static final String AGENT_CHAT = "v/ops/agent/chat";
	private static final String UNKNOWN_SESSION = "Unknown session";
	public static final String NO_RESPONSE = "(no response)";
	private static final AString[] TEXT_KEYS = {
		Fields.TEXT, Fields.RESPONSE, Strings.intern("content"), Fields.MESSAGE, Fields.RESULT };
	private final Engine engine;
	private final MessagingBotSpec spec;
	private final ConversationSessions sessions;
	private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

	public ConversationRouter(Engine engine, MessagingBotSpec spec, ConversationSessions sessions) {
		this.engine = Objects.requireNonNull(engine);
		this.spec = Objects.requireNonNull(spec);
		this.sessions = Objects.requireNonNull(sessions);
	}

	/** Serializes the entire turn, including replies and resets, without blocking other conversations. */
	public CompletableFuture<Void> enqueue(String conversation, Runnable work) {
		Objects.requireNonNull(conversation);
		Objects.requireNonNull(work);
		CompletableFuture<Void> next = new CompletableFuture<>();
		// Publish the tail before starting work. Completion/cleanup inside compute
		// can otherwise race insertion or recursively modify ConcurrentHashMap.
		CompletableFuture<Void> previous = tails.put(conversation, next);
		if (previous == null) previous = CompletableFuture.completedFuture(null);
		next.whenComplete((result, failure) -> tails.remove(conversation, next));
		previous.handleAsync((result, failure) -> {
			try { work.run(); next.complete(null); }
			catch (Throwable t) { next.completeExceptionally(t); }
			return null;
		}, AAdapter.VIRTUAL_EXECUTOR);
		return next.copy();
	}

	public boolean isIdle(String conversation) {
		CompletableFuture<Void> tail = tails.get(conversation);
		return tail == null || tail.isDone();
	}

	/** Call within this conversation's queued turn. */
	public ACell chat(String conversation, ACell message) {
		String sid = sessions.get(conversation);
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, Strings.create(spec.agent()), Fields.MESSAGE, message);
		if (sid != null) input = input.assoc(Fields.SESSION_ID, Strings.create(sid));
		ACell result;
		try {
			result = runOperation(AGENT_CHAT, input);
		} catch (RuntimeException e) {
			if (sid == null || !isUnknownSession(e)) throw e;
			sessions.remove(conversation);
			result = runOperation(AGENT_CHAT, input.dissoc(Fields.SESSION_ID));
		}
		AString newSession = RT.ensureString(RT.getIn(result, Fields.SESSION_ID));
		if (newSession != null && !newSession.toString().equals(sid)) sessions.put(conversation, newSession.toString());
		return RT.getIn(result, Fields.RESPONSE);
	}

	/** Call within the same queue as chat, so a reset cannot race an in-flight turn. */
	public void reset(String conversation) { sessions.remove(conversation); }

	public RequestContext context() { return RequestContext.of(spec.userDID(engine)); }

	public ACell runOperation(String operation, ACell input) {
		Job job = engine.jobs().invokeOperation(operation, input, context());
		ACell result = job.awaitResult();
		if (job.getStatus() != Status.COMPLETE) {
			String why = job.getErrorMessage();
			throw new JobFailedException(operation + " " + job.getStatus() + (why == null ? "" : ": " + why));
		}
		return result;
	}

	/** Operation reply policy: null means no reply. */
	public String operationReply(ACell result) {
		if (spec.silent()) return null;
		String fixed = spec.fixedReply();
		return fixed != null ? fixed : responseText(result);
	}

	public static String responseText(ACell value) {
		String text = renderText(value);
		return text == null || text.isBlank() ? NO_RESPONSE : text;
	}

	public static String renderText(ACell value) {
		if (value == null) return null;
		if (value instanceof AString text) return text.toString();
		if (value instanceof AMap<?, ?> map) for (AString key : TEXT_KEYS) {
			ACell v = RT.getIn(map, key);
			if (v instanceof AString text) return text.toString();
		}
		return JSON.printPretty(value).toString();
	}

	/** Unambiguous opaque key, e.g. Slack installation + channel + thread timestamp. */
	public static String key(String... components) {
		StringBuilder result = new StringBuilder();
		for (String component : components) {
			Objects.requireNonNull(component);
			result.append(component.length()).append(':').append(component);
		}
		return result.toString();
	}

	private static boolean isUnknownSession(Throwable t) {
		for (Throwable cause = t; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
			if (cause.getMessage() != null && cause.getMessage().contains(UNKNOWN_SESSION)) return true;
		}
		return false;
	}
}
