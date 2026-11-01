package covia.venue;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.ResponseException;
import covia.exception.ShuttingDownException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Engine-owned observation of remote work. Each attempt is bounded by its
 * transport; the Job has no lifetime deadline. Timers are disposable and
 * adapters reconstruct them from the delegation record on recovery.
 */
public final class RemoteJobs implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(RemoteJobs.class);
	/** Refusal of remote work once {@link #close} has begun. */
	public static final String CLOSED = "Remote observation is closed";
	public static final String INVALID_CANCELLATION_STATUS = "Invalid remote cancellation status";
	public static final String CANCELLATION_ID_MISMATCH = "Remote cancellation job ID mismatch";
	public static final AString DELEGATION = Strings.intern("delegation");
	public static final AString PROTOCOL = Strings.intern("protocol");
	public static final AString TARGET = Strings.intern("target");
	public static final AString REMOTE_ID = Strings.intern("remoteId");
	public static final AString OBSERVATION = Strings.intern("observation");
	public static final AString AUTH_REF = Strings.intern("authRef");
	public static final AString ERROR_TYPE = Strings.intern("observationErrorType");
	/** An expected observation failure's reason; absent for anything else. */
	public static final AString ERROR = Strings.intern("observationError");
	public static final AString REMOTE_STATUS = Strings.intern("remoteStatus");
	public static final AString OWNS_REMOTE = Strings.intern("ownsRemote");
	public static final AString OUTCOME = Strings.intern("outcome");
	public static final AString HTTP_STATUS = Strings.intern("httpStatus");
	public static final AString CANCELLATION_ERROR_TYPE = Strings.intern("errorType");
	/** An expected cancellation failure's reason; absent for anything else. */
	public static final AString CANCELLATION_ERROR = Strings.intern("error");
	/** Cancellation outcomes recorded under {@link #OUTCOME}. */
	public static final AString OUTCOME_REQUESTED = Strings.intern("requested");
	public static final AString OUTCOME_ACKNOWLEDGED = Strings.intern("acknowledged");
	public static final AString OUTCOME_FINISHED = Strings.intern("finished");
	public static final AString OUTCOME_REJECTED = Strings.intern("rejected");
	public static final AString OUTCOME_UNKNOWN = Strings.intern("unknown");
	private static final AString SUBMITTING = Strings.intern("submitting");
	private static final AString HEALTHY = Strings.intern("healthy");
	private static final AString RETRYING = Strings.intern("retrying");
	private static final AString UNKNOWN = Strings.intern("acceptance-unknown");
	private static final AString SUSPENDED = Strings.intern("suspended");
	private static final AString OPERATION_METADATA = Strings.intern("operationMetadata");
	private final Engine engine;
	private final ScheduledThreadPoolExecutor timer;
	private final ConcurrentHashMap<Job, Watch> watches = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Job, Consumer<Job>> cleanups = new ConcurrentHashMap<>();
	private final Semaphore permits = new Semaphore(32);
	private final ConcurrentHashMap<ACell, Semaphore> peerPermits = new ConcurrentHashMap<>();
	/** Requests started and not yet settled; {@link #close} waits for them. Its monitor guards {@link #closed}'s setting. */
	private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
	private final long intervalMs;
	private final long maxDelayMs;
	private volatile boolean closed;

	RemoteJobs(Engine engine) { this(engine, 500, 30_000); }

	RemoteJobs(Engine engine, long intervalMs, long maxDelayMs) {
		this.engine = engine;
		this.intervalMs = intervalMs;
		this.maxDelayMs = maxDelayMs;
		timer = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "remote-job-observer");
			t.setDaemon(true);
			return t;
		});
		timer.setRemoveOnCancelPolicy(true);
	}

	public static AMap<AString, ACell> descriptor(Job job) {
		return RT.ensureMap(job.getData().get(DELEGATION));
	}

	/** Checkpoint intent and protected transport credentials before submitting once. */
	public void prepare(Job job, String protocol, String target, AMap<AString, ACell> credentials) {
		prepare(job, protocol, target, credentials, true);
	}

	/** Observation of an existing job does not confer cancellation ownership. */
	public void prepare(Job job, String protocol, String target, AMap<AString, ACell> credentials, boolean ownsRemote) {
		if (closed) throw new ShuttingDownException(CLOSED);
		AMap<AString, ACell> record = Maps.of(PROTOCOL, Strings.create(protocol),
			TARGET, Strings.create(target), OBSERVATION, SUBMITTING,
			OWNS_REMOTE, convex.core.data.prim.CVMBool.create(ownsRemote));
		if (job.isRecorded()) {
			if (job instanceof VenueJob hosted) credentials = credentials.assoc(OPERATION_METADATA, hosted.meta());
			AString ref = Strings.create("remote-job-" + job.getID().toHexString());
			secrets().store(ref, JSON.print(credentials), SecretStore.keys(engine.getKeyPair()));
			record = record.assoc(AUTH_REF, ref);
			cleanUpOnFinish(job, ref);
		}
		AMap<AString, ACell> prepared = record;
		job.update(data -> data.assoc(DELEGATION, prepared));
		if (job.isRecorded()) engine.flush();
	}

	/** Fence submission callbacks against shutdown, without interpreting local cancellation remotely. */
	public void received(Job job, Runnable action) {
		synchronized (job) {
			AMap<AString, ACell> record = descriptor(job);
			if (closed || record == null || SUSPENDED.equals(record.get(OBSERVATION))) return;
			action.run();
		}
	}

	/** Restore exactly the saved transport authority; never substitute venue authority. */
	public AMap<AString, ACell> credentials(Job job) {
		return credentials(descriptor(job));
	}

	private AMap<AString, ACell> credentials(AMap<AString, ACell> record) {
		AString ref = record == null ? null : RT.ensureString(record.get(AUTH_REF));
		AString value = ref == null ? null : secrets().decrypt(ref, SecretStore.keys(engine.getKeyPair()));
		if (value == null) throw new UnavailableException("Remote observation credentials unavailable");
		return RT.ensureMap(JSON.parse(value));
	}

	/** Restore the resolved definition, independent of later catalog edits. */
	AMap<AString, ACell> recoveryMetadata(AMap<AString, ACell> record) {
		return RT.ensureMap(credentials(record).get(OPERATION_METADATA));
	}

	void adapterUnavailable(Job job) { health(job, Strings.intern("adapter-unavailable"), null); }

	private SecretStore secrets() {
		return engine.getVenueState().users().ensure(engine.getDIDString()).secrets();
	}

	private void cleanUpOnFinish(Job job, AString ref) {
		Consumer<Job> listener = j -> {
			if (!j.isFinished()) return;
			secrets().delete(ref);
			Consumer<Job> installed = cleanups.remove(j);
			if (installed != null) j.unsubscribe(installed);
		};
		if (cleanups.putIfAbsent(job, listener) == null) {
			job.subscribe(listener);
			listener.accept(job);
		}
	}

	/** Supporting remote evidence, committed with the mirrored local state. */
	public static AMap<AString, ACell> observed(AMap<AString, ACell> data, AString status) {
		AMap<AString, ACell> record = RT.ensureMap(data.get(DELEGATION));
		return data.assoc(DELEGATION, record.assoc(REMOTE_STATUS, status)
			.assoc(OBSERVATION, HEALTHY).dissoc(ERROR_TYPE).dissoc(ERROR));
	}

	/** Bind a known ID before observing. Never call this by resubmitting on timeout. */
	public void accepted(Job job, String id) {
		if (id == null || id.isBlank()) throw new ResponseException("Remote response has no job ID");
		job.update(data -> {
			AMap<AString, ACell> record = RT.ensureMap(data.get(DELEGATION));
			ACell old = record.get(REMOTE_ID);
			if (old != null && !old.equals(Strings.create(id))) {
				throw new ResponseException("Remote job identity changed");
			}
			return data.assoc(DELEGATION, record.assoc(REMOTE_ID, Strings.create(id))
				.assoc(OBSERVATION, HEALTHY).dissoc(ERROR_TYPE).dissoc(ERROR));
		});
		if (job.isRecorded()) engine.flush();
	}

	/** Loss of an acknowledgement is uncertainty, not an execution failure. No replay. */
	public void submissionUnknown(Job job, Throwable error) { health(job, UNKNOWN, error); }

	/**
	 * One asynchronous, transport-bounded cancellation attempt. The adapter owns
	 * protocol interpretation; local cancellation never waits for the peer.
	 * Captured transport authority remains usable after terminal secret cleanup.
	 * Once closed, nothing is sent and the outcome is recorded as unknown.
	 */
	public void attemptCancellation(Job job, String remoteId,
			java.util.concurrent.Callable<AMap<AString, ACell>> request) {
		CompletableFuture<Void> attempt;
		try {
			attempt = start(() -> {
				try {
					recordCancellation(job, remoteId, Maps.of(OUTCOME, OUTCOME_REQUESTED));
				} finally {
					AMap<AString, ACell> result;
					try { result = request.call(); }
					catch (Exception error) {
						Throwable cause = unwrap(error);
						result = cause instanceof covia.exception.ResponseException re
							&& re.response instanceof java.net.http.HttpResponse<?> response
							? cancellationHttpFailure(response.statusCode())
							: unknownCancellation(cause);
						if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
					}
					recordCancellation(job, remoteId, result);
				}
				return CompletableFuture.completedFuture(null);
			});
		} catch (ShuttingDownException refused) {
			// No flush: the engine is closing, and its final flush, if still to come, carries this
			job.recordCancellation(unknownCancellation(refused).assoc(REMOTE_ID, Strings.create(remoteId)));
			return;
		}
		attempt.whenComplete((done, failure) -> {
			if (failure != null) log.error("Recording remote cancellation of job {} failed", job.getID(), unwrap(failure));
		});
	}

	private void recordCancellation(Job job, String remoteId, AMap<AString, ACell> result) {
		job.recordCancellation(result.assoc(REMOTE_ID, Strings.create(remoteId)));
		if (job.isRecorded()) engine.flush();
	}

	/** A terminal response is evidence, never a change to the cancelled local result. */
	public static AMap<AString, ACell> cancellationResult(AString status) {
		if (!(Status.CANCELLED.equals(status) || Status.COMPLETE.equals(status)
			|| Status.FAILED.equals(status) || Status.REJECTED.equals(status)
			|| Status.PENDING.equals(status) || Status.STARTED.equals(status)
			|| Status.PAUSED.equals(status) || Status.INPUT_REQUIRED.equals(status) || Status.AUTH_REQUIRED.equals(status))) {
			throw new ResponseException(INVALID_CANCELLATION_STATUS);
		}
		AString outcome = Status.CANCELLED.equals(status) ? OUTCOME_ACKNOWLEDGED
			: Job.isFinished(Maps.of(Fields.STATUS, status)) ? OUTCOME_FINISHED : OUTCOME_UNKNOWN;
		return Maps.of(OUTCOME, outcome, REMOTE_STATUS, status);
	}

	/** Validated response from a Covia job cancellation endpoint. */
	public static AMap<AString, ACell> cancellationResult(convex.core.data.Blob id, AMap<AString, ACell> snapshot) {
		if (snapshot == null || !id.equals(Job.parseID(snapshot.get(Fields.ID)))) {
			throw new ResponseException(CANCELLATION_ID_MISMATCH);
		}
		return cancellationResult(RT.ensureString(snapshot.get(Fields.STATUS)));
	}

	private static AMap<AString, ACell> unknownCancellation(Throwable cause) {
		AMap<AString, ACell> result = Maps.of(OUTCOME, OUTCOME_UNKNOWN,
			CANCELLATION_ERROR_TYPE, cause.getClass().getSimpleName());
		AString reason = reason(cause);
		return reason == null ? result : result.assoc(CANCELLATION_ERROR, reason);
	}

	/**
	 * An expected failure's reason, which is the venue's own words. Null for
	 * anything else, whose text may carry a credential-bearing URL or a
	 * remote's body; its type is recorded instead.
	 */
	private static AString reason(Throwable error) {
		if (covia.exception.CoviaException.reasonOf(error) == null) return null;
		return Strings.create(covia.adapter.AAdapter.describeFailure(error));
	}

	public static AMap<AString, ACell> cancellationHttpFailure(int code) {
		return Maps.of(OUTCOME, code >= 400 && code < 500 && code != 408 ? OUTCOME_REJECTED : OUTCOME_UNKNOWN,
			HTTP_STATUS, code);
	}

	/** A protocol rejection proves non-acceptance; a timeout or 5xx does not. */
	public void submissionFailed(Job job, Throwable error) {
		Throwable cause = unwrap(error);
		if (cause instanceof covia.exception.ResponseException re
				&& re.response instanceof java.net.http.HttpResponse<?> response) {
			int code = response.statusCode();
			if (code >= 400 && code < 500 && code != 408) {
				job.fail("Remote submission rejected: HTTP " + code);
				return;
			}
		}
		submissionUnknown(job, cause);
	}

	/** Recovery may lack usable credentials without implying remote execution failed. */
	public void unavailable(Job job, Throwable error) {
		health(job, Strings.intern("auth-required"), error);
	}

	private void health(Job job, AString value, Throwable error) {
		AMap<AString, ACell> current = descriptor(job);
		AString ref = current == null ? null : RT.ensureString(current.get(AUTH_REF));
		if (ref != null && !closed) cleanUpOnFinish(job, ref);
		AString type = error == null ? null : Strings.create(unwrap(error).getClass().getSimpleName());
		AString reason = reason(error);
		job.update(data -> {
			AMap<AString, ACell> record = RT.ensureMap(data.get(DELEGATION));
			if (record == null) return null;
			AMap<AString, ACell> next = record.assoc(OBSERVATION, value);
			next = type == null ? next.dissoc(ERROR_TYPE) : next.assoc(ERROR_TYPE, type);
			next = reason == null ? next.dissoc(ERROR) : next.assoc(ERROR, reason);
			return next.equals(record) ? null : data.assoc(DELEGATION, next);
		});
	}

	/** One request at a time per Job. A replacement fences callbacks from its predecessor. */
	public void observe(Job job, Supplier<CompletableFuture<AMap<AString, ACell>>> poll,
			Consumer<AMap<AString, ACell>> apply) {
		synchronized (job) {
			AString ref = RT.ensureString(descriptor(job).get(AUTH_REF));
			if (ref != null) cleanUpOnFinish(job, ref);
			Watch watch = new Watch(job, poll, apply);
			Watch old = watches.put(job, watch);
			if (old != null) old.stop();
			watch.listener = job.subscribe(j -> { if (j.isFinished()) stop(j); });
			if (job.isFinished() || closed) { stop(job); return; }
			watch.schedule(0);
		}
	}

	/**
	 * Starts one request on a virtual thread, never the alarm thread: starting
	 * it can block on discovery, a venue challenge or the transport.
	 * {@link #close} waits for every request started here.
	 *
	 * @throws ShuttingDownException once close has begun, starting nothing
	 */
	private <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> request) {
		CompletableFuture<T> started;
		synchronized (requests) {
			if (closed) throw new ShuttingDownException(CLOSED);
			started = CompletableFuture.supplyAsync(request, covia.adapter.AAdapter.VIRTUAL_EXECUTOR).thenCompose(f -> f);
			requests.add(started);
		}
		started.whenComplete((result, error) -> requests.remove(started));
		return started;
	}

	private static Throwable unwrap(Throwable error) {
		while ((error instanceof java.util.concurrent.CompletionException
				|| error instanceof java.util.concurrent.ExecutionException) && error.getCause() != null) {
			error = error.getCause();
		}
		return error;
	}

	public void suspend(Job job) {
		synchronized (job) {
			stop(job);
			health(job, SUSPENDED, null);
		}
	}

	private void stop(Job job) {
		Watch watch = watches.remove(job);
		if (watch != null) watch.stop();
	}

	private final class Watch {
		final Job job;
		final Supplier<CompletableFuture<AMap<AString, ACell>>> poll;
		final Consumer<AMap<AString, ACell>> apply;
		volatile java.util.concurrent.ScheduledFuture<?> alarm;
		volatile Consumer<Job> listener;
		volatile boolean stopped;
		long delay = intervalMs;
		Watch(Job job, Supplier<CompletableFuture<AMap<AString, ACell>>> poll,
				Consumer<AMap<AString, ACell>> apply) {
			this.job = job; this.poll = poll; this.apply = apply;
		}
		boolean active() { return !closed && !stopped && !job.isFinished() && watches.get(job) == this; }
		void stop() {
			stopped = true;
			if (alarm != null) alarm.cancel(false);
			if (listener != null) job.unsubscribe(listener);
		}
		void schedule(long ms) {
			if (active()) {
				try { alarm = timer.schedule(this::attempt, ms, TimeUnit.MILLISECONDS); }
				catch (java.util.concurrent.RejectedExecutionException e) { stop(); }
			}
		}
		void attempt() {
			if (!active()) return;
			Semaphore peer = peerPermits.computeIfAbsent(descriptor(job).get(TARGET), ignored -> new Semaphore(4));
			if (!peer.tryAcquire()) { schedule(intervalMs); return; }
			if (!permits.tryAcquire()) { peer.release(); schedule(intervalMs); return; }
			CompletableFuture<AMap<AString, ACell>> request;
			try { request = start(poll); }
			catch (RuntimeException | Error error) { request = CompletableFuture.failedFuture(error); }
			request.whenComplete((snapshot, error) -> {
				synchronized (job) {
					try {
						if (!active()) return;
						if (error != null) throw new java.util.concurrent.CompletionException(error);
						apply.accept(snapshot);
						if (active()) health(job, HEALTHY, null);
						delay = intervalMs;
					} catch (RuntimeException | Error failure) {
						Throwable cause = unwrap(failure);
						java.net.http.HttpResponse<?> response = cause instanceof covia.exception.ResponseException re
							&& re.response instanceof java.net.http.HttpResponse<?> r ? r : null;
						boolean auth = response != null && (response.statusCode() == 401 || response.statusCode() == 403);
						if (active()) health(job, auth ? Strings.intern("auth-required") : RETRYING, cause);
						delay = Math.min(maxDelayMs, Math.max(intervalMs, delay * 2));
						if (response != null) delay = Math.max(delay, covia.grid.client.RetryPolicy.parseRetryAfterMs(
							response.headers().firstValue("Retry-After").orElse(null), System.currentTimeMillis()));
					} finally {
						permits.release();
						peer.release();
						schedule(delay + java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1, delay / 4)));
					}
				}
			});
		}
	}

	/**
	 * Stops observation, then waits for every request already started, each
	 * bounded by its transport's own timeout, so none outlives the engine; an
	 * interrupt ends the wait and is kept on the thread. No
	 * request starts once close has begun. A request may need engine services
	 * that synchronise on the engine, so {@link Engine#close} calls this before
	 * taking its monitor. Idempotent.
	 */
	@Override public void close() {
		synchronized (requests) { closed = true; }
		watches.forEach((job, watch) -> watch.stop());
		watches.clear();
		cleanups.forEach(Job::unsubscribe);
		cleanups.clear();
		timer.shutdownNow();
		for (CompletableFuture<?> request : requests) {
			try { request.get(); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
			catch (ExecutionException | CancellationException e) {
				// The request's own outcome, handled where it was started
			}
		}
	}
}
