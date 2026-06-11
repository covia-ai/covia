package covia.grid;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.exception.JobPollingFailedException;

/**
 * Class representing a Covia Job.
 *
 * <p>Thread-safe without locks: uses {@link AtomicReference} for the job data
 * so virtual threads are never pinned to carrier threads. All side effects
 * in the update path (lattice persistence, future completion) are idempotent,
 * so concurrent updates are safe — the CAS ensures terminal states are
 * sticky (once finished, the data reference never changes).</p>
 */
public class Job {

	/** Job status data — atomic for lock-free concurrent updates */
	private final AtomicReference<AMap<AString, ACell>> data;

	protected volatile boolean cancelled = false;

	/** Lazy result future — atomic for safe publication */
	private final AtomicReference<CompletableFuture<ACell>> resultFuture = new AtomicReference<>();

	/**
	 * Optional cancellation hook. Invoked by {@link #cancel()} so the work can
	 * actually stop (interrupt a sleeping thread, close a connection, etc.)
	 * rather than just marking the lattice status. Adapters that submit
	 * interruptible work register a hook such as
	 * {@code () -> future.cancel(true)}.
	 */
	private volatile Runnable onCancel = null;

	/** Key for previous state pointer in job data */
	public static final AString PREV = Strings.intern("prev");

	/**
	 * Per-Job listeners invoked on every successful state update. Consumers
	 * subscribe via {@link #subscribe(Consumer)} and are guaranteed to see
	 * the Job (not a snapshot) with {@link #getData()} reflecting the state
	 * just committed by the CAS. Cross-cutting listeners (every Job in a
	 * venue) belong on {@code JobManager} instead — this list is for
	 * per-Job consumers like SSE subscriptions.
	 */
	private final CopyOnWriteArrayList<Consumer<Job>> listeners = new CopyOnWriteArrayList<>();

	public Job(AMap<AString, ACell> status) {
		this.data = new AtomicReference<>(status);
	}

	public static boolean isFinished(AMap<AString, ACell> jobData) {
		AString status = RT.ensureString(jobData.get(Fields.STATUS));
		if (status == null) throw new Error("Job status should never be null");
		return status.equals(Status.COMPLETE)
			|| status.equals(Status.FAILED)
			|| status.equals(Status.CANCELLED)
			|| status.equals(Status.REJECTED);
	}

	public static Blob parseID(Object a) {
		if (a instanceof Blob b) return b;
		if (a instanceof ABlob b) return b.toFlatBlob();
		if (a instanceof String s) return Blob.parse(s);
		if (a instanceof AString s) return Blob.parse(s.toString());
		throw new IllegalArgumentException("Unable to convert to Job ID: " + a);
	}

	public static Job create(AMap<AString, ACell> status) {
		return new Job(status);
	}

	/**
	 * Gets the remote ID of the Job. Valid for the venue which is executing it.
	 * A 16-byte Blob (6 bytes timestamp + 2 bytes counter + 8 bytes random).
	 * @return Job ID as Blob
	 */
	public Blob getID() {
		ACell id = RT.get(data.get(), Fields.ID);
		if (id == null) return null;
		return parseID(id);
	}

	/**
	 * Checks if this job is finished (COMPLETE, FAILED, CANCELLED or REJECTED)
	 * @return true if finished
	 */
	public boolean isFinished() {
		return isFinished(data.get());
	}

	/**
	 * Checks if this job is COMPLETE, i.e. has a valid result
	 * @return true if complete
	 */
	public boolean isComplete() {
		return Status.COMPLETE.equals(RT.ensureString(data.get().get(Fields.STATUS)));
	}

	/**
	 * Checks if this job is paused (PAUSED, INPUT_REQUIRED, or AUTH_REQUIRED)
	 * @return true if paused
	 */
	public boolean isPaused() {
		AString status = getStatus();
		return Status.PAUSED.equals(status)
			|| Status.INPUT_REQUIRED.equals(status)
			|| Status.AUTH_REQUIRED.equals(status);
	}

	/**
	 * Updates the job data atomically. No effect if job is already finished.
	 *
	 * <p>Lock-free: {@link #processUpdate} runs first (side effects like
	 * lattice persistence are idempotent via CRDT merge), then a CAS on the
	 * {@link AtomicReference} ensures terminal states are sticky. Post-update
	 * hooks (listener, future completion) run only if the update took effect.</p>
	 *
	 * @param newData New job status data
	 */
	public void updateData(AMap<AString, ACell> newData) {
		// Pre-process: may add timestamp, persist to lattice (all idempotent)
		newData = processUpdate(newData);
		if (newData == null) return;

		// Atomic state transition — terminal states are sticky
		final AMap<AString, ACell> prepared = newData;
		AMap<AString, ACell> prev = data.getAndUpdate(current -> {
			if (isFinished(current)) return current;
			AMap<AString, ACell> d = prepared;
			if (current != null && !current.isEmpty()) {
				d = d.assoc(PREV, current);
			}
			return d;
		});

		// CAS was a no-op if already finished
		if (isFinished(prev)) return;

		// Post-update side effects
		AMap<AString, ACell> current = data.get();
		onUpdate(current);
		notifyListeners();

		if (isFinished(current)) {
			completeResultFuture();
			onFinish(current);
		}
	}

	/**
	 * Subscribe a listener that will be notified on every successful state
	 * update to this Job. The listener runs on the thread that called
	 * {@link #updateData}; it must not block or throw.
	 *
	 * @return the supplied listener, for chaining
	 */
	public Consumer<Job> subscribe(Consumer<Job> listener) {
		listeners.add(listener);
		return listener;
	}

	/**
	 * Remove a previously-registered listener. Identity-based; you must pass
	 * the same object that was passed to {@link #subscribe}.
	 *
	 * @return true if the listener was removed
	 */
	public boolean unsubscribe(Consumer<Job> listener) {
		return listeners.remove(listener);
	}

	private void notifyListeners() {
		for (Consumer<Job> l : listeners) {
			try {
				l.accept(this);
			} catch (Throwable t) {
				// Don't let a misbehaving listener poison updateData; there's
				// no sensible recovery beyond isolating the failure.
			}
		}
	}

	/**
	 * Completes the result future based on job status.
	 * Idempotent: CompletableFuture.complete() returns false on second call.
	 */
	private void completeResultFuture() {
		CompletableFuture<ACell> f = resultFuture.get();
		if (f == null) return;
		if (isComplete()) {
			f.complete(getOutput());
		} else {
			f.completeExceptionally(new JobFailedException(this));
		}
	}

	/**
	 * Reports that client-side polling stopped tracking this Job before it
	 * reached a terminal state — typically a polling timeout or transport
	 * failure. <b>Does not change the Job's status</b>: the remote work is
	 * not known to have failed; we just lost visibility. Awaiters of
	 * {@link #future()} / {@link #awaitResult()} receive a
	 * {@link JobPollingFailedException} carrying the cause and last-known
	 * status, so they can re-acquire authoritative state via
	 * {@code client.getJobStatus(id)} if they care.
	 *
	 * <p>Idempotent: subsequent calls have no effect (CompletableFuture
	 * completion is one-shot).</p>
	 *
	 * @param cause the underlying polling failure (must not be null)
	 */
	public void pollingFailed(Throwable cause) {
		CompletableFuture<ACell> f = resultFuture.get();
		if (f == null) return;
		AString status = RT.ensureString(getData().get(Fields.STATUS));
		String lastKnown = (status != null) ? status.toString() : "UNKNOWN";
		f.completeExceptionally(new JobPollingFailedException(getID(), lastKnown, cause));
	}

	/**
	 * Hook called before the atomic data update. Subclasses may override to
	 * modify data or perform side effects (e.g. lattice persistence).
	 *
	 * <p><b>Side effects MUST be idempotent</b> — concurrent callers may invoke
	 * this for data that is ultimately discarded by the CAS (e.g. if the job
	 * reached a terminal state between processUpdate and the CAS). Lattice
	 * persistence is naturally idempotent via CRDT merge.</p>
	 *
	 * TODO: consider is this is sensible
	 *
	 * @param newData new status data of the Job
	 * @return updated version of the data, or null to cancel the update
	 */
	public AMap<AString, ACell> processUpdate(AMap<AString, ACell> newData) {
		return newData;
	}

	/**
	 * Hook called after data is updated. Subclasses may override.
	 * @param newData new status data of the Job
	 */
	public void onUpdate(AMap<AString, ACell> newData) {
	}

	/**
	 * Hook called after job reaches a terminal state. Subclasses may override.
	 * @param finalData final status data of the Job
	 */
	public void onFinish(AMap<AString, ACell> finalData) {
	}

	/**
	 * Sets the future for the Job result. Can only be done once.
	 * @param future future to set
	 */
	public void setFuture(CompletableFuture<ACell> future) {
		if (!resultFuture.compareAndSet(null, future)) {
			throw new IllegalStateException("Result future already set");
		}
		if (isFinished()) {
			completeResultFuture();
		}
	}

	public AString getStatus() {
		return RT.ensureString(RT.get(data.get(), Fields.STATUS));
	}

	/**
	 * Gets the full Job data
	 * @return Job data as a JSON-compatible Map
	 */
	public AMap<AString, ACell> getData() {
		return data.get();
	}

	/**
	 * Gets the output of this Job, assuming it is COMPLETE
	 * @return Job output
	 * @throws IllegalStateException if job has not finished
	 * @throws JobFailedException if job finished but is not COMPLETE
	 */
	public ACell getOutput() {
		if (!isFinished()) throw new IllegalStateException("Job has no output, status is " + getStatus());
		if (!isComplete()) throw new JobFailedException(this);
		return RT.get(data.get(), Fields.OUTPUT);
	}

	/**
	 * Gets the error message of this Job, if it has FAILED or REJECTED
	 * @return Error message as a string, or null if no message or job is not failed/rejected
	 */
	public String getErrorMessage() {
		AString status = getStatus();
		if (status != Status.FAILED && status != Status.REJECTED) return null;
		ACell errorField = RT.get(data.get(), Fields.ERROR);
		return errorField != null ? errorField.toString() : null;
	}

	/**
	 * Gets the caller DID associated with this job, if identified.
	 * @return Caller DID string, or null if anonymous
	 */
	public AString getCaller() {
		return RT.ensureString(getData().get(Fields.CALLER));
	}

	// ===== State History =====

	/**
	 * Gets the previous state from the current job data's prev chain.
	 * @return Previous state data, or null if this is the first state
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> getPreviousState() {
		return (AMap<AString, ACell>) data.get().get(PREV);
	}

	/**
	 * Cancel this Job. No effect if already finished.
	 *
	 * <p>If a {@linkplain #setCancelHook cancel hook} has been registered, it
	 * runs after the status transition so the underlying worker can actually
	 * stop (e.g. interrupt a sleeping thread via {@code future.cancel(true)}).
	 * Without a hook, cancellation only flips the lattice status and the
	 * worker must poll {@link #isFinished()} to notice.</p>
	 */
	public void cancel() {
		if (isFinished()) return;
		cancelled = true;
		update(job -> {
			job = job.assoc(Fields.STATUS, Status.CANCELLED);
			job = job.assoc(Fields.ERROR, Strings.create("Job cancelled: " + getID().toHexString()));
			return job;
		});
		Runnable hook = onCancel;
		if (hook != null) hook.run();
	}

	/**
	 * Register a cancellation hook invoked by {@link #cancel()}. Adapters that
	 * submit interruptible work should register
	 * {@code () -> future.cancel(true)} so a cancel actually stops the worker
	 * thread. Pass a {@link java.util.concurrent.Future} from
	 * {@code ExecutorService.submit(...)}, not a {@link CompletableFuture}
	 * from {@code runAsync} (the latter ignores {@code mayInterruptIfRunning}).
	 *
	 * @param hook the cancel callback, or null to clear
	 */
	public void setCancelHook(Runnable hook) {
		this.onCancel = hook;
	}

	/**
	 * Pause this Job, if it is currently running (STARTED).
	 * @throws IllegalStateException if the job is already finished
	 */
	public void pause() {
		if (isFinished()) throw new IllegalStateException("Job already finished");
		updateData(data.get().assoc(Fields.STATUS, Status.PAUSED));
	}

	/**
	 * Resume this Job from a paused state (PAUSED, INPUT_REQUIRED, AUTH_REQUIRED).
	 * Sets status back to STARTED.
	 * @throws IllegalStateException if the job is not paused or is finished
	 */
	public void resume() {
		if (isFinished()) throw new IllegalStateException("Job already finished");
		if (!isPaused()) throw new IllegalStateException("Job is not paused: " + getStatus());
		updateData(data.get().assoc(Fields.STATUS, Status.STARTED));
	}

	public void setStatus(AString newStatus) {
		if (isFinished()) throw new IllegalStateException("Job already finished");
		updateData(data.get().assoc(Fields.STATUS, newStatus));
	}

	public void completeWith(ACell result) {
		if (isFinished()) throw new IllegalStateException("Job already finished");
		AMap<AString, ACell> newData = getData();
		newData = newData.assoc(Fields.STATUS, Status.COMPLETE);
		newData = newData.assoc(Fields.OUTPUT, result);
		updateData(newData);
	}

	/**
	 * Waits for the job to complete and returns the result. Blocks indefinitely.
	 * @param <T> Expected type of ACell result, for convenience
	 * @return Result of job
	 * @throws JobFailedException if job failed
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T awaitResult() {
		try {
			return (T) future().join();
		} catch (CompletionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof JobFailedException) {
				throw (JobFailedException) cause;
			}
			fail(cause.getMessage());
			throw new JobFailedException(this);
		}
	}

	/**
	 * Waits for the job to complete and returns the result, with a timeout.
	 * @param <T> Expected type of ACell result, for convenience
	 * @param timeoutMillis Maximum time to wait in milliseconds
	 * @return Result of job
	 * @throws JobFailedException if job failed or timed out
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T awaitResult(long timeoutMillis) {
		try {
			return (T) future().get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			fail("Job timed out after " + timeoutMillis + "ms");
			throw new JobFailedException(this);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof JobFailedException) {
				throw (JobFailedException) cause;
			}
			fail(cause.getMessage());
			throw new JobFailedException(this);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("Interrupted while waiting for result");
			throw new JobFailedException(this);
		}
	}

	/**
	 * Gets the lazy future for the Job result.
	 * Creates the future on first call. If the Job is already finished,
	 * the future will be immediately completed (or completed exceptionally).
	 *
	 * @return CompletableFuture that completes with the job output on success,
	 *         or completes exceptionally with JobFailedException on failure
	 */
	public CompletableFuture<ACell> future() {
		CompletableFuture<ACell> f = resultFuture.get();
		if (f != null) return f;
		CompletableFuture<ACell> newFuture = new CompletableFuture<>();
		if (resultFuture.compareAndSet(null, newFuture)) {
			if (isFinished()) completeResultFuture();
			return newFuture;
		}
		return resultFuture.get(); // another thread won the race
	}

	/**
	 * Causes the job to fail, if it is not already finished.
	 * @param message Error message
	 */
	public void fail(String message) {
		if (isFinished()) return;
		update(job -> {
			job = job.assoc(Fields.STATUS, Status.FAILED);
			job = job.assoc(Fields.ERROR, Strings.create(message));
			return job;
		});
	}

	/**
	 * Updates the job data atomically using the given update function.
	 * @param updater Function to apply to current data
	 */
	public void update(UnaryOperator<AMap<AString, ACell>> updater) {
		AMap<AString, ACell> oldData = getData();
		AMap<AString, ACell> newData = updater.apply(oldData);
		updateData(newData);
	}

	/**
	 * Create a job with a specific failure message
	 * @param message Failure message
	 * @return Failed Job
	 */
	public static Job failure(String message) {
		return Job.create(Maps.of(
			Fields.STATUS, Status.FAILED,
			Fields.ERROR, Strings.create(message)
		));
	}

	/**
	 * Create a job with a specific rejection message
	 * @param message Rejection message
	 * @return Rejected Job
	 */
	public static Job rejected(String message) {
		return Job.create(Maps.of(
			Fields.STATUS, Status.REJECTED,
			Fields.ERROR, Strings.create(message)
		));
	}

	/**
	 * Create a job with PAUSED status
	 * @param message Optional message explaining why the job was paused
	 * @return Paused Job
	 */
	public static Job paused(String message) {
		AMap<AString, ACell> jobData = Maps.of(Fields.STATUS, Status.PAUSED);
		if (message != null && !message.isEmpty()) {
			jobData = jobData.assoc(Fields.MESSAGE, Strings.create(message));
		}
		return Job.create(jobData);
	}
}
