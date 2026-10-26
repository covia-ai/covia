package covia.adapter.remote;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;

/**
 * Job-facing lifecycle shared by external agent adapters (HTTP, CLI or SDK).
 * A Job may represent a task, a turn, or an open conversation. Providers decide
 * when a reply ends that interaction; the base never infers completion from a
 * reply, imposes a lifetime timeout, or replays submissions on recovery.
 *
 * <p>Use RemoteJobs by composition for accepted remote tasks. Conversation
 * resumption and task reattachment are different provider recovery contracts.
 * Administrative operations retain AAdapter's ordinary future-based path.</p>
 *
 * @param <I> provider's live interaction binding
 */
public abstract class ARemoteAgentAdapter<I extends RemoteInteraction> extends AAdapter {
	public static final String NO_INTERACTION = "Job has no agent interaction accepting messages";
	public static final String INTERACTION_ENDED = "Agent interaction ended";
	/** Where a running Job says what its work is doing now: see {@link Job#progress}. */
	public static final AString PROGRESS = Fields.PROGRESS;

	private final ConcurrentHashMap<Blob, Binding> interactions = new ConcurrentHashMap<>();

	private final class Binding {
		final Job job;
		final I interaction;
		final Consumer<Job> release;
		Binding(Job job, I interaction) {
			this.job = job;
			this.interaction = interaction;
			this.release = j -> { if (j.isFinished()) forgetInteraction(j.getID()); };
		}
	}

	public static final String UNSUPPORTED_AGENT_OPERATION = "Agent adapter does not support this operation: ";
	public static final String INVALID_AGENT_DEFINITION = "Invalid agent definition: ";

	/** Validate adapter-specific data without contacting the provider or resolving secrets. */
	public void validateAgent(AMap<AString, ACell> definition) {}

	/** A private backend invocation, dispatched through the ordinary Job machinery. */
	public record AgentInvocation(String operation, ACell input) {}

	/** Translate the common agent API; provider configuration comes only from the definition. */
	public AgentInvocation agentInvocation(String operation, AMap<AString, ACell> definition,
		ACell content, ACell session) {
		throw new UnsupportedOperationException(UNSUPPORTED_AGENT_OPERATION + operation);
	}

	/** Provider conversation identity, independent of the Covia session and task IDs. */
	public ACell agentSession(Job job) { return null; }

	/** Provider reply projected into the common chat response / request output. */
	public ACell agentOutput(Job job) { return job.getOutput(); }

	/** Whether this operation uses the interaction lifecycle. */
	protected abstract boolean isInteractionOperation(String operation);

	/** Called once after the Job has claimed STARTED and invoke authority passed. */
	protected abstract void startInteraction(Job job, RequestContext ctx,
		AMap<AString, ACell> meta, ACell input);

	/** Only conversation operations expose a reply before their Job completes. */
	protected boolean returnsFirstReply(String operation) { return false; }

	@Override
	public final void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (!isInteractionOperation(getSubOperation(meta))) {
			super.invoke(job, ctx, meta, input);
			return;
		}
		job.start(() -> {
			try {
				requireInvoke(ctx);
				startInteraction(job, ctx, meta, input);
			} catch (RuntimeException | Error e) {
				settleJob(job, null, e);
				throw e;
			}
		});
	}

	/**
	 * Install a passive binding once, including cancellation after late acceptance.
	 * Terminal cleanup removes listeners and the live map entry, not external work.
	 */
	protected final I bindInteraction(Job job, I interaction) {
		synchronized (job) {
			Binding binding = new Binding(job, interaction);
			Binding existing = interactions.putIfAbsent(job.getID(), binding);
			if (existing != null) return existing.interaction;
			try {
				job.setCancelHook(interaction::cancel);
			} finally {
				// Late cancellation may invoke provider code immediately. Even a
				// throwing cancellation must release the terminal binding.
				job.subscribe(binding.release);
				binding.release.accept(job);
			}
			return interaction;
		}
	}

	/** Live binding, if any; external identity belongs on the provider's checkpoint. */
	protected final I interaction(Job job) {
		Binding binding = interactions.get(job.getID());
		return binding == null ? null : binding.interaction;
	}

	/** Release local bookkeeping without cancelling external work. */
	protected final void forgetInteraction(Blob id) {
		Binding binding = interactions.remove(id);
		if (binding != null) binding.job.unsubscribe(binding.release);
	}

	/** Used by a provider closing its own resources. */
	protected final void clearInteractions() {
		for (Blob id : interactions.keySet()) forgetInteraction(id);
	}

	/** Restore a conversation for delivery; default refuses rather than resubmits. */
	protected I restoreInteraction(Job job) { return null; }

	@Override
	public final boolean supportsMultiTurn() { return true; }

	@Override
	public final void handleMessage(Job job, AMap<AString, ACell> messageRecord) {
		I live;
		synchronized (job) {
			if (job.isFinished()) throw new IllegalStateException(INTERACTION_ENDED);
			live = interaction(job);
			if (live == null) {
				live = restoreInteraction(job);
				if (live != null) live = bindInteraction(job, live);
			}
		}
		if (live == null) throw new IllegalStateException(NO_INTERACTION);
		live.deliver(messageRecord);
	}

	/**
	 * Provider-neutral progress: {@code message}, one line a person can read,
	 * and the provider's own {@code data} beside it. Observed while the Job
	 * runs and never persisted; a finished Job refuses it.
	 */
	protected static void publishProgress(Job job, String message, ACell data) {
		job.progress(message, data);
	}

	@Override
	public final CompletableFuture<ACell> resultFuture(Job job, AMap<AString, ACell> meta, ACell input) {
		if (!returnsFirstReply(getSubOperation(meta))) return job.future();
		CompletableFuture<ACell> result = new CompletableFuture<>();
		Consumer<Job> listener = j -> {
			if (Status.INPUT_REQUIRED.equals(j.getStatus()) || Status.COMPLETE.equals(j.getStatus())) {
				result.complete(j.getData().get(Fields.OUTPUT));
			} else if (j.isFinished()) {
				result.completeExceptionally(new JobFailedException(
					j.getErrorMessage() == null ? INTERACTION_ENDED : j.getErrorMessage()));
			}
		};
		job.subscribe(listener);
		listener.accept(job);
		result.whenComplete((r, e) -> job.unsubscribe(listener));
		return result;
	}
}
