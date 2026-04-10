package covia.grid;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
	 * Optional handle to the underlying interruptible work future. Set by
	 * adapters that submit work to an executor (e.g. {@code executor.submit(...)}).
	 * When non-null, {@link #cancel()} will call {@code workFuture.cancel(true)}
	 * to interrupt the running thread, so a cancel actually stops the work
	 * rather than just marking the lattice status. CompletableFuture from
	 * {@code runAsync} cannot be used here — its {@code cancel(true)} ignores
	 * the interrupt flag and never interrupts the thread.
	 */
	private volatile Future<?> workFuture = null;

	/** Per-job message queue for incoming message records */
	private final ConcurrentLinkedQueue<AMap<AString, ACell>> messageQueue = new ConcurrentLinkedQueue<>();

	/** Optional listener notified after each state update */
	private volatile Consumer<Job> updateListener = null;

	/** Transient reference to the operation being executed, if resolved */
	private final Operation operation;

	/** Key for previous state pointer in job data */
	public static final AString PREV = Strings.intern("prev");

	public Job(AMap<AString, ACell> status) {
		this(status, null);
	}

	public Job(AMap<AString, ACell> status, Operation operation) {
		this.data = new AtomicReference<>(status);
		this.operation = operation;
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

		Consumer<Job> listener = this.updateListener;
		if (listener != null) {
			listener.accept(this);
		}

		if (isFinished(current)) {
			completeResultFuture();
			onFinish(current);
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

	// ===== Message Queue =====

	/**
	 * Enqueues a message record for this job.
	 * @param record Message record to enqueue
	 */
	public void enqueueMessage(AMap<AString, ACell> record) {
		messageQueue.add(record);
	}

	/**
	 * Dequeues the next message record from this job's queue.
	 * @return Next message record, or null if queue is empty
	 */
	public AMap<AString, ACell> dequeueMessage() {
		return messageQueue.poll();
	}

	/**
	 * Gets the current number of messages in this job's queue.
	 * @return Queue depth
	 */
	public int getQueueSize() {
		return messageQueue.size();
	}

	// ===== Update Listener =====

	/**
	 * Sets a listener to be notified after each state update.
	 * Used by SSE server for per-job event broadcasting.
	 * @param listener Listener to set, or null to remove
	 */
	public void setUpdateListener(Consumer<Job> listener) {
		this.updateListener = listener;
	}

	/**
	 * Gets the operation associated with this job, if resolved.
	 * @return Operation, or null if not resolved at creation time
	 */
	public Operation getOperation() {
		return operation;
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
	 * <p>If a {@linkplain #setWorkFuture work future} has been registered, it
	 * is cancelled with {@code mayInterruptIfRunning=true} so the underlying
	 * worker thread is interrupted (e.g. unblocks {@code Thread.sleep}). This
	 * is what makes cancel actually stop the work rather than just marking
	 * the lattice status.</p>
	 */
	public void cancel() {
		if (isFinished()) return;
		cancelled = true;
		update(job -> {
			job = job.assoc(Fields.STATUS, Status.CANCELLED);
			job = job.assoc(Fields.ERROR, Strings.create("Job cancelled: " + getID().toHexString()));
			return job;
		});
		Future<?> wf = workFuture;
		if (wf != null) wf.cancel(true);
	}

	/**
	 * Register the interruptible {@link Future} representing this job's
	 * underlying work. Adapters that submit work via
	 * {@code ExecutorService.submit(...)} should call this so that
	 * {@link #cancel()} can actually interrupt the worker thread.
	 *
	 * <p>Pass the {@link Future} returned by {@code submit}, NOT a
	 * {@link CompletableFuture} from {@code runAsync} — the latter ignores
	 * {@code mayInterruptIfRunning}.</p>
	 *
	 * @param future the interruptible future, or null to clear
	 */
	public void setWorkFuture(Future<?> future) {
		this.workFuture = future;
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
