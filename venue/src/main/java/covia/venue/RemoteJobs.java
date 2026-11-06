package covia.venue;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import covia.api.Fields;
import covia.exception.ResponseException;
import covia.exception.ShuttingDownException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * Engine-owned observation of remote work. Each attempt is bounded by its
 * transport; the Job has no lifetime deadline. Timers are disposable and
 * adapters reconstruct them on recovery from what the job keeps.
 *
 * <p>A job's record carries what its caller can follow under
 * {@link #DELEGATION}: the remote job's {@link #REMOTE_ID}, its last observed
 * {@link #REMOTE_STATUS}, and {@link #NOT_FOLLOWED} while this venue has stopped
 * following it. How the venue follows it is kept in the job's entry
 * ({@link JobManager#adapterData}) beside the adapter's connection: the
 * {@link #PROTOCOL}, the {@link #TARGET}, whether it {@link #OWNS_REMOTE},
 * and the {@link #OBSERVATION} health.</p>
 */
public final class RemoteJobs implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(RemoteJobs.class);
	/** Refusal of remote work once {@link #close} has begun. */
	public static final String CLOSED = "Remote observation is closed";
	/** Refusal of a new send to a remote venue once this venue has begun shutting down. */
	public static final String SENDS_CLOSED = "Venue is shutting down; nothing new is sent to remote venues";
	public static final String INVALID_CANCELLATION_STATUS = "Invalid remote cancellation status";
	public static final String CANCELLATION_ID_MISMATCH = "Remote cancellation job ID mismatch";
	/** Why a recovered job cannot reach its remote job again: it keeps nothing to do so with. */
	public static final String NO_CONNECTION = "This job keeps nothing to reach its remote job again";
	/**
	 * Why a recovered job that signed in to its remote venue as its caller
	 * ended: the venue keeps no sign-in credential, so it cannot follow the
	 * remote job after a restart. Formatted with the remote job's ID and its
	 * venue, where the caller can follow it.
	 */
	public static final String CREDENTIAL_NOT_KEPT =
		"This venue restarted and keeps no sign-in credential, so it no longer follows remote job %s; follow it at %s";
	/**
	 * Why this venue stopped following a job when it cannot tell whether the
	 * remote venue accepted it. Formatted with that venue.
	 */
	public static final String ACCEPTANCE_UNKNOWN =
		"This venue does not know whether %s accepted this job, and will not send it again; check there before retrying or cancelling";
	/**
	 * Why this venue stopped following a job after a restart: it could not
	 * reach the remote job again. Formatted with the remote job's ID and its
	 * venue.
	 */
	public static final String UNREACHABLE =
		"This venue restarted and cannot reach remote job %s at %s again; follow it there";
	/** As {@link #UNREACHABLE}, with the reason third. */
	public static final String UNREACHABLE_BECAUSE =
		"This venue restarted and cannot reach remote job %s at %s again (%s); follow it there";
	/**
	 * Why this venue stopped following a job after a restart: the adapter that
	 * ran it is not loaded. Formatted with the adapter's name, the remote job's
	 * ID and its venue.
	 */
	public static final String ADAPTER_MISSING =
		"This venue restarted without its %s adapter, so it does not follow remote job %s at %s until that adapter is back";

	/** On the record: what the caller can follow of the job's remote work. */
	public static final AString DELEGATION = Strings.intern("delegation");
	/** In {@link #DELEGATION}: the remote job's ID, once the remote venue accepted it. */
	public static final AString REMOTE_ID = Strings.intern("remoteId");
	/** In {@link #DELEGATION}: the remote job's status as last observed. */
	public static final AString REMOTE_STATUS = Strings.intern("remoteStatus");
	/** In {@link #DELEGATION}: why this venue has stopped following the remote job, until it follows it again. */
	public static final AString NOT_FOLLOWED = Strings.intern("notFollowed");

	/** In the job's entry: the protocol the remote job is reached by. */
	public static final AString PROTOCOL = Strings.intern("protocol");
	/** In the job's entry: the remote venue or endpoint. */
	public static final AString TARGET = Strings.intern("target");
	/** In the job's entry: whether cancelling the job cancels the remote job. */
	public static final AString OWNS_REMOTE = Strings.intern("ownsRemote");
	/** In the job's entry: how observation of the remote job stands. */
	public static final AString OBSERVATION = Strings.intern("observation");
	/** In the job's entry: the type of the last observation failure. */
	public static final AString ERROR_TYPE = Strings.intern("observationErrorType");
	/** In the job's entry: an expected observation failure's reason; absent for anything else. */
	public static final AString ERROR = Strings.intern("observationError");
	/** What the job's entry keeps for this component, beside the adapter's connection. */
	private static final AString[] FOLLOWING = {PROTOCOL, TARGET, OWNS_REMOTE, OBSERVATION, ERROR_TYPE, ERROR};
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
	private static final AString AUTH_REQUIRED = Strings.intern("auth-required");
	private static final AString ADAPTER_UNAVAILABLE = Strings.intern("adapter-unavailable");
	private final Engine engine;
	private final ScheduledThreadPoolExecutor timer;
	private final ConcurrentHashMap<Job, Watch> watches = new ConcurrentHashMap<>();
	/** Jobs suspended at shutdown, whose late callbacks {@link #received} fences. */
	private final Set<convex.core.data.Blob> suspended = ConcurrentHashMap.newKeySet();
	private final Semaphore permits = new Semaphore(32);
	private final ConcurrentHashMap<ACell, Semaphore> peerPermits = new ConcurrentHashMap<>();
	/** Requests started and not yet settled; {@link #close} waits for them. Its monitor guards {@link #closed}'s setting. */
	private final Set<CompletableFuture<?>> requests = ConcurrentHashMap.newKeySet();
	/** Guards {@link #abandoned}'s setting against a cancellation outcome being recorded. */
	private final Object settling = new Object();
	private final long intervalMs;
	private final long maxDelayMs;
	private volatile boolean closed;
	/** Set once close stops waiting: later answers are ignored, and a request not yet begun sends nothing. */
	private volatile boolean abandoned;

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

	/** What the job's record shows its caller of its remote work ({@link #DELEGATION}), or null before there is any. */
	public static AMap<AString, ACell> descriptor(Job job) {
		return RT.ensureMap(job.getData().get(DELEGATION));
	}

	/** The remote job's ID, or null until the remote venue has accepted it. */
	public static AString remoteId(Job job) {
		return RT.ensureString(RT.getIn(job.getData(), DELEGATION, REMOTE_ID));
	}

	/**
	 * What this venue keeps to follow a job's remote work, in the job's entry
	 * ({@link JobManager#adapterData}): the {@link #PROTOCOL}, {@link #TARGET},
	 * {@link #OWNS_REMOTE} and {@link #OBSERVATION} health, beside the
	 * adapter's connection.
	 *
	 * @return the data, or null when the job delegates no work or has finished
	 */
	public AMap<AString, ACell> kept(Job job) {
		AMap<AString, ACell> data = engine.jobs().adapterData(job);
		return (data == null || data.get(PROTOCOL) == null) ? null : data;
	}

	/** The remote venue or endpoint a job's work was sent to, or null when it keeps none. */
	public AString target(Job job) {
		return RT.ensureString(RT.getIn(kept(job), TARGET));
	}

	/**
	 * Checkpoint intent before submitting once. {@code connection} is what
	 * recovery needs to reach the remote job again: the connection's mode,
	 * with the target's DID and forwarded grants when the venue signs in as
	 * itself, or the reference of a secret to read again; never a sign-in
	 * credential or a secret's value. It is kept with how the venue follows
	 * the job ({@link #kept}) until the job ends.
	 */
	public void prepare(Job job, String protocol, String target, AMap<AString, ACell> connection) {
		prepare(job, protocol, target, connection, true);
	}

	/**
	 * Refuses a new send to another venue or remote agent once this venue has
	 * begun shutting down: a new remote job, a message to one, or a write
	 * there. Call it before any contact, discovery and venue proof included.
	 * Status reads and cancellations are not sends; requests already under way
	 * get the shutdown grace window ({@link #close(long)}).
	 *
	 * @throws ShuttingDownException once shutdown has begun
	 */
	public void requireNotClosing() {
		if (engine.isClosing()) throw new ShuttingDownException(SENDS_CLOSED);
	}

	/** Observation of an existing job does not confer cancellation ownership. */
	public void prepare(Job job, String protocol, String target, AMap<AString, ACell> connection, boolean ownsRemote) {
		if (closed) throw new ShuttingDownException(CLOSED);
		AMap<AString, ACell> kept = connection.assoc(PROTOCOL, Strings.create(protocol))
			.assoc(TARGET, Strings.create(target)).assoc(OBSERVATION, SUBMITTING)
			.assoc(OWNS_REMOTE, convex.core.data.prim.CVMBool.create(ownsRemote));
		// Kept before anything is sent: a delegation that recovery cannot follow is never begun
		engine.jobs().updateAdapterData(job, data -> kept);
		if (job.isRecorded()) engine.flush();
	}

	/** Fence submission callbacks against shutdown, without interpreting local cancellation remotely. */
	public void received(Job job, Runnable action) {
		synchronized (job) {
			if (closed || suspended.contains(job.getID())) return;
			action.run();
		}
	}

	/**
	 * What {@link #prepare} kept for recovery to reach the remote job again;
	 * never venue authority in its place.
	 *
	 * @throws UnavailableException with {@link #NO_CONNECTION} when the job keeps none
	 */
	public AMap<AString, ACell> connection(Job job) {
		AMap<AString, ACell> kept = engine.jobs().adapterData(job);
		if (kept == null) throw new UnavailableException(NO_CONNECTION);
		for (AString field : FOLLOWING) kept = kept.dissoc(field);
		return kept;
	}

	/**
	 * Ends a recovered job that signed in to its remote venue as its caller:
	 * nothing is sent there, and the reason names the remote job and its venue
	 * so the caller can follow it there ({@link #CREDENTIAL_NOT_KEPT}).
	 */
	public void credentialNotKept(Job job) {
		end(job, CREDENTIAL_NOT_KEPT.formatted(remoteId(job), target(job)));
	}

	/** Fails a recovered job that will not be observed; what was kept for its recovery goes with its entry. */
	public void end(Job job, String reason) {
		job.fail(reason);
	}

	/** Stops following a recovered job whose adapter is not loaded, until a restart finds it again ({@link #ADAPTER_MISSING}). */
	void adapterUnavailable(Job job) {
		AString id = remoteId(job);
		stopFollowing(job, ADAPTER_UNAVAILABLE, null, (id == null) ? ACCEPTANCE_UNKNOWN.formatted(target(job))
			: ADAPTER_MISSING.formatted(job.getData().get(Fields.ADAPTER), id, target(job)));
	}

	/**
	 * Supporting remote evidence, committed with the mirrored local state. The
	 * venue follows the remote job again, so the record no longer says it does not.
	 */
	public static AMap<AString, ACell> observed(AMap<AString, ACell> data, AString status) {
		return data.assoc(DELEGATION, shown(data).assoc(REMOTE_STATUS, status).dissoc(NOT_FOLLOWED));
	}

	/** What a record shows of its remote work, empty before it shows any. */
	private static AMap<AString, ACell> shown(AMap<AString, ACell> data) {
		AMap<AString, ACell> record = RT.ensureMap(data.get(DELEGATION));
		return (record == null) ? Maps.empty() : record;
	}

	/** Bind a known ID before observing. Never call this by resubmitting on timeout. */
	public void accepted(Job job, String id) {
		if (id == null || id.isBlank()) throw new ResponseException("Remote response has no job ID");
		job.update(data -> {
			AMap<AString, ACell> record = shown(data);
			ACell old = record.get(REMOTE_ID);
			if (old != null && !old.equals(Strings.create(id))) {
				throw new ResponseException("Remote job identity changed");
			}
			return data.assoc(DELEGATION, record.assoc(REMOTE_ID, Strings.create(id)).dissoc(NOT_FOLLOWED));
		});
		health(job, HEALTHY, null);
		if (job.isRecorded()) engine.flush();
	}

	/**
	 * Loss of an acknowledgement is uncertainty, not an execution failure. No
	 * replay: the venue stops following the job ({@link #ACCEPTANCE_UNKNOWN}).
	 */
	public void submissionUnknown(Job job, Throwable error) {
		stopFollowing(job, UNKNOWN, error, ACCEPTANCE_UNKNOWN.formatted(target(job)));
	}

	/**
	 * One asynchronous, transport-bounded cancellation attempt. The adapter owns
	 * protocol interpretation; local cancellation never waits for the peer.
	 * Captured transport authority remains usable after terminal secret cleanup.
	 * Once closed, nothing is sent and the outcome is recorded as unknown. An
	 * attempt close gives up on keeps its {@code requested} outcome, and its
	 * answer is ignored.
	 *
	 * @return The attempt, complete once it has ended
	 */
	public CompletableFuture<Void> attemptCancellation(Job job, String remoteId,
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
			return CompletableFuture.completedFuture(null);
		}
		attempt.whenComplete((done, failure) -> {
			// A shutdown refusal means close gave up on it before it began
			if (failure != null && !(unwrap(failure) instanceof ShuttingDownException)) {
				log.error("Recording remote cancellation of job {} failed", job.getID(), unwrap(failure));
			}
		});
		return attempt;
	}

	/** Records an outcome, unless close has given up on requests: nothing is written after that. */
	private void recordCancellation(Job job, String remoteId, AMap<AString, ACell> result) {
		synchronized (settling) {
			if (abandoned) return;
			job.recordCancellation(result.assoc(REMOTE_ID, Strings.create(remoteId)));
			if (job.isRecorded()) engine.flush();
		}
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

	/**
	 * Recovery could not reach the remote job again. That lacks usable
	 * authority or a connection without implying remote execution failed: the
	 * venue stops following the job ({@link #UNREACHABLE}).
	 */
	public void unavailable(Job job, Throwable error) {
		AString id = remoteId(job);
		AString target = target(job);
		AString reason = reason(error);
		stopFollowing(job, AUTH_REQUIRED, error, (id == null) ? ACCEPTANCE_UNKNOWN.formatted(target)
			: (reason == null) ? UNREACHABLE.formatted(id, target) : UNREACHABLE_BECAUSE.formatted(id, target, reason));
	}

	/**
	 * This venue stops following a job's remote work, which it will not take
	 * up again by itself: its health goes in the job's entry, and the record
	 * says why ({@link #NOT_FOLLOWED}).
	 */
	private void stopFollowing(Job job, AString value, Throwable error, String why) {
		health(job, value, error);
		AString text = Strings.create(why);
		job.update(data -> {
			AMap<AString, ACell> record = shown(data);
			return text.equals(record.get(NOT_FOLLOWED)) ? null : data.assoc(DELEGATION, record.assoc(NOT_FOLLOWED, text));
		});
		log.warn("Job {} no longer follows its remote work{}: {}", job.getID().toHexString(),
			(error == null) ? "" : " (" + unwrap(error).getClass().getSimpleName() + ")", why);
	}

	/** How observation of a job's remote work stands, in its entry; never on its record. */
	private void health(Job job, AString value, Throwable error) {
		if (kept(job) == null) return;
		AString type = error == null ? null : Strings.create(unwrap(error).getClass().getSimpleName());
		AString reason = reason(error);
		engine.jobs().updateAdapterData(job, kept -> {
			AMap<AString, ACell> next = kept.assoc(OBSERVATION, value);
			next = type == null ? next.dissoc(ERROR_TYPE) : next.assoc(ERROR_TYPE, type);
			next = reason == null ? next.dissoc(ERROR) : next.assoc(ERROR, reason);
			return next.equals(kept) ? kept : next;
		});
	}

	/** One request at a time per Job. A replacement fences callbacks from its predecessor. */
	public void observe(Job job, Supplier<CompletableFuture<AMap<AString, ACell>>> poll,
			Consumer<AMap<AString, ACell>> apply) {
		synchronized (job) {
			// Followed again: callbacks are no longer fenced as at shutdown
			suspended.remove(job.getID());
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
			started = CompletableFuture.supplyAsync(() -> {
				// Begun only after close gave up on requests: send nothing
				if (abandoned) throw new ShuttingDownException(CLOSED);
				return request.get();
			}, covia.adapter.AAdapter.VIRTUAL_EXECUTOR).thenCompose(f -> f);
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

	/** Stops observation at shutdown and fences the job's late callbacks ({@link #received}). */
	public void suspend(Job job) {
		synchronized (job) {
			stop(job);
			suspended.add(job.getID());
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
		/** The remote venue its requests count against, or the job itself when it keeps none. */
		final ACell peer;
		Watch(Job job, Supplier<CompletableFuture<AMap<AString, ACell>>> poll,
				Consumer<AMap<AString, ACell>> apply) {
			this.job = job; this.poll = poll; this.apply = apply;
			AString target = target(job);
			this.peer = (target != null) ? target : job.getID();
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
			Semaphore peer = peerPermits.computeIfAbsent(this.peer, ignored -> new Semaphore(4));
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
						if (active()) health(job, auth ? AUTH_REQUIRED : RETRYING, cause);
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
	 * Stops observation and lets requests already started run until
	 * {@code graceEnds} ({@link System#nanoTime}), the end of the venue's
	 * shutdown grace window, then gives up on the rest: a later answer is
	 * ignored, and a request not yet begun sends nothing. No request starts
	 * once close has begun. An interrupt ends the wait and is kept on the
	 * thread. A request may need engine services that synchronise on the
	 * engine, so {@link Engine#close} calls this before taking its monitor.
	 * Idempotent.
	 */
	void close(long graceEnds) {
		synchronized (requests) { closed = true; }
		watches.forEach((job, watch) -> watch.stop());
		watches.clear();
		timer.shutdownNow();
		try {
			for (CompletableFuture<?> request : requests) {
				long remaining = graceEnds - System.nanoTime();
				if (remaining <= 0) break;
				try { request.get(remaining, TimeUnit.NANOSECONDS); }
				catch (ExecutionException | CancellationException e) {
					// The request's own outcome, handled where it was started
				}
			}
		} catch (TimeoutException e) {
			// The grace window has ended
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		synchronized (settling) { abandoned = true; }
	}

	/** Closes at once, giving up on any request still running. */
	@Override public void close() { close(System.nanoTime()); }
}
