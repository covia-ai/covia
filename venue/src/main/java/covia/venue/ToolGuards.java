package covia.venue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;

/**
 * Guards on agents' tool calls: checks an embedding host registers to see each
 * operation call an agent's run loop is about to dispatch, and to let it pass
 * or refuse it. Where the agent event tap ({@link AgentEvents}) only watches,
 * a guard decides — a host can hold a call it judges risky while it asks
 * someone, then let it through or turn it away.
 *
 * <p><b>Contract.</b> A guard returns {@code null} to pass the call and a
 * human-readable reason to refuse it. A refused call does not run: its tool
 * result is {@code "Error: "} plus the reason, so the model sees why, exactly
 * as it sees a capability denial. Every registered guard must pass. Harness
 * tools (complete, subgoal, context and skill loads) are the run loop's own
 * and are never guarded; every operation call is, {@code hitl:request}
 * included.</p>
 *
 * <p><b>Bounded, fail-closed.</b> The guards run on their own virtual thread
 * within the tool call's own budget (the agent's {@code toolCallTimeoutMs}),
 * so a guard may block while it decides, but cannot hang the run loop: one
 * that runs out of time, or throws, refuses the call. With no guard
 * registered — the usual case — a check costs nothing.</p>
 */
public final class ToolGuards {

	/** The reason given when the guards did not decide within the call's budget. */
	public static final String TIMED_OUT = "a tool guard did not decide in time";
	/** The reason given, before the failure, when a guard threw. */
	public static final String FAILED = "a tool guard failed: ";

	/**
	 * One operation call an agent is about to make.
	 *
	 * @param ownerDID  the agent's owner, whose namespace it acts in
	 * @param agentId   the agent's id, under its owner
	 * @param sessionId the session the call belongs to, or null
	 * @param tool      the tool name the model used
	 * @param operation the operation the call will run
	 * @param input     the call's arguments
	 */
	public record Call(AString ownerDID, AString agentId, Blob sessionId, String tool, AString operation,
			ACell input) {
	}

	/** One check on agents' tool calls. */
	@FunctionalInterface
	public interface Guard {
		/** Null to let {@code call} run; otherwise why it may not. May block. */
		String check(Call call) throws Exception;
	}

	/** A registered guard; {@link #close()} removes it. */
	@FunctionalInterface
	public interface Registration extends AutoCloseable {
		@Override void close();
	}

	private final CopyOnWriteArrayList<Guard> guards = new CopyOnWriteArrayList<>();

	/** Registers {@code guard}; it sees every agent's operation calls on this venue. */
	public Registration add(Guard guard) {
		java.util.Objects.requireNonNull(guard, "guard");
		guards.add(guard);
		return () -> guards.remove(guard);
	}

	/**
	 * Null when every guard lets {@code call} run; otherwise the first reason
	 * given, or {@link #TIMED_OUT} / {@link #FAILED} when the guards did not
	 * decide within {@code timeoutMs}.
	 */
	public String check(Call call, long timeoutMs) {
		if (guards.isEmpty()) return null;
		CompletableFuture<String> verdict = new CompletableFuture<>();
		Thread.ofVirtual().name("tool-guard-" + call.tool()).start(() -> {
			try {
				for (Guard g : guards) {
					String reason = g.check(call);
					if (reason != null) {
						verdict.complete(reason);
						return;
					}
				}
				verdict.complete(null);
			} catch (Throwable t) {
				verdict.complete(FAILED + t.getMessage());
			}
		});
		try {
			// A timed wait on a virtual thread: on JDK 25 before 25.0.3
			// (JDK-8370887) it can expire tens of seconds late.
			return verdict.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			return TIMED_OUT;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return TIMED_OUT;
		} catch (ExecutionException e) {
			return FAILED + e.getMessage();
		}
	}
}
