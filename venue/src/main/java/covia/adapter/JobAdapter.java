package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.InvalidDefinitionException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.ResponseException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.venue.RemoteJobs;
import covia.venue.RequestContext;

/** Tool surface for the universal Job contract; execution belongs to JobManager and the job's adapter. */
public class JobAdapter extends AAdapter {

	public static final String INVALID_ID = "'id' must be a valid Job ID returned by an invocation";
	public static final String INVALID_VENUE = "'venue' must be a string when provided; omit it for the local venue";
	public static final String INVALID_DEFAULT_VENUE = "Operation 'venue' must be a string when provided";
	public static final String INVALID_TIMEOUT = "'timeout' must be a non-negative integer in milliseconds";
	public static final String INVALID_MESSAGE = "'message' must be an object, for example {content: \"continue\"}";
	public static final String UNKNOWN_OPERATION = "Unknown job operation: ";
	public static final String NOT_FOUND = "Job not found: ";
	public static final String RESULT_TIMEOUT = "job:result timed out after %dms waiting for job %s; the job continues";
	public static final String INVALID_REMOTE = "Remote venue returned an invalid Job identity or status";
	private static final AString PROTOCOL = Strings.intern("covia");

	@Override public String getName() { return "job"; }
	@Override public String getDescription() {
		return "Inspect and control existing Jobs on this or another venue, regardless of which adapter runs them.";
	}

	@Override protected void installAssets() {
		for (String op : new String[]{"status", "result", "cancel", "pause", "resume", "message"}) {
			installAsset("job/" + op, "/adapters/job/" + op + ".json");
		}
		installSkill("root/jobs", "/skills/jobs.json");
		installSkill("jobs/jobs", "/skills/jobs.json");
	}

	@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		validateOperation(op);
		Blob id = id(input);
		AString target = target(meta, input);
		if ("result".equals(op)) {
			long timeout = timeout(input);
			if (target != null) return engine.jobs().invokeInternal(meta, input, ctx);
			Job job = engine.jobs().getJob(id, ctx);
			if (job == null) throw missing(id);
			// A wait owns neither the target job nor its future. Cancelling or timing
			// out this derived future cannot cancel the work being observed.
			CompletableFuture<ACell> wait = job.future().thenApply(value -> value);
			return bounded(wait, timeout, id);
		}
		AMap<AString, ACell> message = "message".equals(op) ? message(input) : null;
		if (target == null) {
			return switch (op) {
				case "status" -> CompletableFuture.completedFuture(found(engine.jobs().getJobData(id, ctx), id));
				case "cancel" -> CompletableFuture.completedFuture(found(engine.jobs().cancelJob(id, ctx), id));
				case "pause" -> CompletableFuture.completedFuture(found(engine.jobs().pauseJob(id, ctx), id));
				case "resume" -> CompletableFuture.completedFuture(found(engine.jobs().resumeJob(id, ctx), id));
				case "message" -> {
					engine.jobs().deliverMessage(id, message, ctx);
					yield CompletableFuture.completedFuture(delivered(id));
				}
				default -> throw new NotSupportedException(UNKNOWN_OPERATION + op);
			};
		}
		// Connection setup and synchronous SDK controls may perform network I/O.
		return CompletableFuture.supplyAsync(() -> {
			if (!"status".equals(op)) engine.remoteJobs().requireNotClosing();
			Venue venue = VenueConnection.connect(engine, ctx, target, input).venue();
			return switch (op) {
				case "status" -> venue.getJobStatus(id).thenApply(data -> (ACell) found(data, id));
				case "cancel" -> CompletableFuture.<ACell>completedFuture(found(venue.cancelJob(id), id));
				case "pause" -> CompletableFuture.<ACell>completedFuture(found(venue.pauseJob(id), id));
				case "resume" -> CompletableFuture.<ACell>completedFuture(found(venue.resumeJob(id), id));
				case "message" -> {
					venue.sendMessage(id.toHexString(), message);
					yield CompletableFuture.<ACell>completedFuture(delivered(id));
				}
				default -> throw new NotSupportedException(UNKNOWN_OPERATION + op);
			};
		}, VIRTUAL_EXECUTOR).thenCompose(future -> future);
	}

	@Override public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (!"result".equals(getSubOperation(meta)) || target(meta, input) == null) {
			super.invoke(job, ctx, meta, input);
			return;
		}
		job.start(() -> {
			Blob id = id(input);
			long timeout = timeout(input);
			// The timeout belongs to this observation, never the target's execution.
			bounded(job.future().thenApply(value -> value), timeout, id)
				.whenComplete((value, error) -> { if (error != null && !job.isFinished()) job.fail(unwrap(error)); });
			CompletableFuture.runAsync(() -> {
				if (job.isFinished()) return;
				AString target = target(meta, input);
				VenueConnection connection = VenueConnection.connect(engine, ctx, target, input);
				if (job.isFinished()) return;
				RemoteJobs observer = engine.remoteJobs();
				observer.prepare(job, PROTOCOL.toString(), target.toString(), connection.credentials(), false);
				observer.accepted(job, id.toHexString());
				watch(job, connection.venue(), id);
			}, VIRTUAL_EXECUTOR).whenComplete((value, error) -> { if (error != null) job.fail(unwrap(error)); });
		});
	}

	private void watch(Job wait, Venue venue, Blob id) {
		engine.remoteJobs().observe(wait, () -> venue.getJobStatus(id), snapshot -> {
			AString status = snapshot == null ? null : RT.ensureString(snapshot.get(Fields.STATUS));
			if (snapshot == null || !id.equals(Job.parseID(snapshot.get(Fields.ID)))
					|| !(Status.PENDING.equals(status) || Status.STARTED.equals(status) || Status.PAUSED.equals(status)
					|| Status.INPUT_REQUIRED.equals(status) || Status.AUTH_REQUIRED.equals(status) || Status.COMPLETE.equals(status)
					|| Status.FAILED.equals(status) || Status.REJECTED.equals(status) || Status.CANCELLED.equals(status))) {
				throw new ResponseException(INVALID_REMOTE);
			}
			if (Status.COMPLETE.equals(status)) {
				wait.completeWith(snapshot.get(Fields.OUTPUT), data -> RemoteJobs.observed(data, status));
			} else {
				// This is a result wait, not another interaction with the target.
				// Its remoteStatus shows input/auth waits without soliciting replies here.
				wait.update(data -> {
					AMap<AString, ACell> next = RemoteJobs.observed(data, status);
					if (Job.isFinished(snapshot)) next = next.assoc(Fields.STATUS, status)
						.assoc(Fields.ERROR, snapshot.get(Fields.ERROR)).assoc(Fields.ERROR_CODE, snapshot.get(Fields.ERROR_CODE));
					return next.equals(data) ? null : next;
				});
			}
		});
	}

	@Override public void recoverJob(Job job) {
		AMap<AString, ACell> kept = engine.remoteJobs().kept(job);
		// Bounded waits end on restart; restarting their budget would extend the caller's deadline.
		if (kept == null || timeout(RT.getIn(job.getData(), Fields.INPUT)) > 0) { super.recoverJob(job); return; }
		try {
			AString remoteId = RemoteJobs.remoteId(job);
			if (remoteId == null) { engine.remoteJobs().submissionUnknown(job, null); return; }
			AMap<AString, ACell> saved = engine.remoteJobs().connection(job);
			if (!VenueConnection.resumable(saved)) { engine.remoteJobs().credentialNotKept(job); return; }
			Venue venue = VenueConnection.restore(engine, RT.ensureString(kept.get(RemoteJobs.TARGET)), saved);
			watch(job, venue, Job.parseID(remoteId));
		} catch (RuntimeException e) { engine.remoteJobs().unavailable(job, e); }
	}

	@Override public void suspendJob(Job job) {
		if (engine.remoteJobs().kept(job) != null) engine.remoteJobs().suspend(job);
		else super.suspendJob(job);
	}

	private static CompletableFuture<ACell> bounded(CompletableFuture<ACell> wait, long timeout, Blob id) {
		if (timeout == 0) return wait;
		return wait.orTimeout(timeout, TimeUnit.MILLISECONDS).exceptionallyCompose(error -> {
			if (unwrap(error) instanceof TimeoutException) return CompletableFuture.failedFuture(
				new UnavailableException(RESULT_TIMEOUT.formatted(timeout, id.toHexString())));
			return CompletableFuture.failedFuture(error);
		});
	}

	private static void validateOperation(String op) {
		if (op == null || !("status".equals(op) || "result".equals(op) || "cancel".equals(op)
			|| "pause".equals(op) || "resume".equals(op) || "message".equals(op)))
			throw new NotSupportedException(UNKNOWN_OPERATION + op);
	}

	private static Blob id(ACell input) {
		try {
			ACell value = RT.getIn(input, Fields.ID);
			Blob id = value == null ? null : Job.parseID(value);
			if (id == null) throw new InvalidInputException(INVALID_ID);
			return id;
		} catch (IllegalArgumentException e) { throw new InvalidInputException(INVALID_ID, e); }
	}

	private static long timeout(ACell input) {
		ACell value = RT.getIn(input, Fields.TIMEOUT);
		if (value == null) return 0;
		if (value instanceof CVMLong number && number.longValue() >= 0) return number.longValue();
		throw new InvalidInputException(INVALID_TIMEOUT);
	}

	private static AString target(ACell meta, ACell input) {
		AMap<?, ?> args = RT.ensureMap(input);
		if (args != null && args.containsKey(Fields.VENUE)) {
			if (args.get(Fields.VENUE) instanceof AString value) return value;
			throw new InvalidInputException(INVALID_VENUE);
		}
		AMap<?, ?> op = RT.ensureMap(RT.getIn(meta, Fields.OPERATION));
		if (op != null && op.containsKey(Fields.VENUE)) {
			if (op.get(Fields.VENUE) instanceof AString value) return value;
			throw new InvalidDefinitionException(INVALID_DEFAULT_VENUE);
		}
		return null;
	}

	private static AMap<AString, ACell> message(ACell input) {
		AMap<AString, ACell> message = RT.ensureMap(RT.getIn(input, Fields.MESSAGE));
		if (message == null) throw new InvalidInputException(INVALID_MESSAGE);
		return message;
	}

	private static NotFoundException missing(Blob id) { return new NotFoundException(NOT_FOUND + id.toHexString(), id.toHexString()); }
	private static AMap<AString, ACell> found(AMap<AString, ACell> data, Blob id) {
		if (data == null) throw missing(id);
		return data;
	}
	private static AMap<AString, ACell> delivered(Blob id) {
		return Maps.of(Fields.ID, id.toHexString(), Fields.DELIVERED, CVMBool.TRUE);
	}
}
