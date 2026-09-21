package covia.venue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.grid.Job;

/**
 * Engine-owned observation of remote work. Each attempt is bounded by its
 * transport; the Job has no lifetime deadline. Timers are disposable and
 * adapters reconstruct them from the delegation record on recovery.
 */
public final class RemoteJobs implements AutoCloseable {
	public static final AString DELEGATION = Strings.intern("delegation");
	public static final AString PROTOCOL = Strings.intern("protocol");
	public static final AString TARGET = Strings.intern("target");
	public static final AString REMOTE_ID = Strings.intern("remoteId");
	public static final AString OBSERVATION = Strings.intern("observation");
	public static final AString AUTH_REF = Strings.intern("authRef");
	public static final AString ERROR_TYPE = Strings.intern("observationErrorType");
	public static final AString REMOTE_STATUS = Strings.intern("remoteStatus");
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
		if (closed) throw new IllegalStateException("Remote observation is closed");
		AMap<AString, ACell> record = Maps.of(PROTOCOL, Strings.create(protocol),
			TARGET, Strings.create(target), OBSERVATION, SUBMITTING);
		if (job.isRecorded()) {
			if (job instanceof VenueJob hosted) credentials = credentials.assoc(OPERATION_METADATA, hosted.meta());
			AString ref = Strings.create("remote-job-" + job.getID().toHexString());
			secrets().store(ref, JSON.print(credentials), SecretStore.deriveKey(engine.getKeyPair()));
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
		AString value = ref == null ? null : secrets().decrypt(ref, SecretStore.deriveKey(engine.getKeyPair()));
		if (value == null) throw new IllegalStateException("Remote observation credentials unavailable");
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
			.assoc(OBSERVATION, HEALTHY).dissoc(ERROR_TYPE));
	}

	/** Bind a known ID before observing. Never call this by resubmitting on timeout. */
	public void accepted(Job job, String id) {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("Remote response has no job ID");
		job.update(data -> {
			AMap<AString, ACell> record = RT.ensureMap(data.get(DELEGATION));
			ACell old = record.get(REMOTE_ID);
			if (old != null && !old.equals(Strings.create(id))) {
				throw new IllegalStateException("Remote job identity changed");
			}
			return data.assoc(DELEGATION, record.assoc(REMOTE_ID, Strings.create(id))
				.assoc(OBSERVATION, HEALTHY).dissoc(ERROR_TYPE));
		});
		if (job.isRecorded()) engine.flush();
	}

	/** Loss of an acknowledgement is uncertainty, not an execution failure. No replay. */
	public void submissionUnknown(Job job, Throwable error) { health(job, UNKNOWN, error); }

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
		// Exception text may contain a credential-bearing URL or response body.
		AString type = error == null ? null : Strings.create(unwrap(error).getClass().getSimpleName());
		job.update(data -> {
			AMap<AString, ACell> record = RT.ensureMap(data.get(DELEGATION));
			if (record == null) return null;
			AMap<AString, ACell> next = record.assoc(OBSERVATION, value);
			next = type == null ? next.dissoc(ERROR_TYPE) : next.assoc(ERROR_TYPE, type);
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
			// Starting a request can perform blocking discovery/auth. Never do it on the alarm thread.
			CompletableFuture<AMap<AString, ACell>> request;
			try {
				request = CompletableFuture.supplyAsync(poll::get, covia.adapter.AAdapter.VIRTUAL_EXECUTOR).thenCompose(f -> f);
			} catch (RuntimeException | Error error) { request = CompletableFuture.failedFuture(error); }
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

	@Override public void close() {
		closed = true;
		watches.forEach((job, watch) -> watch.stop());
		watches.clear();
		cleanups.forEach(Job::unsubscribe);
		cleanups.clear();
		timer.shutdownNow();
	}
}
