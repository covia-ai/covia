package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.venue.RequestContext;

/**
 * User-facing surface for the per-venue grid {@link covia.venue.Scheduler}.
 * Schedules any grid operation to fire at a future time — waking an agent is one
 * such operation. See {@code venue/docs/GRID_SCHEDULER.md}.
 *
 * <p>Operations: {@code schedule}, {@code cancel}, {@code trigger}, {@code list}.
 * The firing identity and authority (owner DID, UCAN proofs, caps) are captured
 * from the scheduling caller and replayed at fire time, so a scheduled
 * invocation cannot exceed the authority the owner held when scheduling.</p>
 */
public class SchedulerAdapter extends AAdapter {

	private static final AString OPERATION = Strings.intern("operation");
	private static final AString INPUT = Strings.intern("input");
	private static final AString TIME = Strings.intern("time");
	private static final AString AFTER = Strings.intern("after");
	private static final AString HANDLE = Strings.intern("handle");
	private static final AString CANCELLED = Strings.intern("cancelled");
	private static final AString TRIGGERED = Strings.intern("triggered");
	private static final AString RESULT = Strings.intern("result");
	private static final AString EVENTS = Strings.intern("events");

	@Override
	public String getName() {
		return "scheduler";
	}

	@Override
	public String getDescription() {
		return "Schedules grid operations to run at a future time. Returns a handle "
			+ "for each scheduled event so the owner can trigger it early, cancel it, "
			+ "or list pending events. Firing runs the operation with the authority "
			+ "captured when it was scheduled.";
	}

	@Override
	protected void installAssets() {
		installAsset("scheduler/schedule", "/adapters/scheduler/schedule.json");
		installAsset("scheduler/cancel",   "/adapters/scheduler/cancel.json");
		installAsset("scheduler/trigger",  "/adapters/scheduler/trigger.json");
		installAsset("scheduler/list",     "/adapters/scheduler/list.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(
				new AuthException("Scheduler operations require an authenticated caller"));
		}
		String op = getSubOperation(meta);
		try {
			switch (op) {
				case "schedule":
					return CompletableFuture.supplyAsync(() -> handleSchedule(ctx, input), VIRTUAL_EXECUTOR);
				case "cancel":
					return CompletableFuture.supplyAsync(() -> handleCancel(ctx, input), VIRTUAL_EXECUTOR);
				case "list":
					return CompletableFuture.supplyAsync(() -> handleList(ctx), VIRTUAL_EXECUTOR);
				case "trigger":
					return handleTrigger(ctx, input);
				default:
					return CompletableFuture.failedFuture(
						new IllegalArgumentException("Unknown scheduler operation: " + op));
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell handleSchedule(RequestContext ctx, ACell input) {
		AString opRef = RT.ensureString(RT.getIn(input, OPERATION));
		if (opRef == null) {
			throw new IllegalArgumentException("schedule requires an 'operation' reference");
		}
		ACell opInput = RT.getIn(input, INPUT);
		CVMLong timeV = RT.ensureLong(RT.getIn(input, TIME));
		CVMLong afterV = RT.ensureLong(RT.getIn(input, AFTER));
		long wakeTime;
		if (timeV != null) {
			wakeTime = timeV.longValue();
		} else if (afterV != null) {
			wakeTime = System.currentTimeMillis() + afterV.longValue();
		} else {
			throw new IllegalArgumentException(
				"schedule requires 'time' (absolute millis) or 'after' (millis from now)");
		}
		Blob handle = engine.gridScheduler().schedule(opRef, opInput, ctx, wakeTime);
		return Maps.of(HANDLE, handle, TIME, CVMLong.create(wakeTime));
	}

	private ACell handleCancel(RequestContext ctx, ACell input) {
		Blob handle = parseHandle(RT.getIn(input, HANDLE));
		boolean cancelled = engine.gridScheduler().cancel(handle, ctx);
		return Maps.of(CANCELLED, CVMBool.create(cancelled));
	}

	private CompletableFuture<ACell> handleTrigger(RequestContext ctx, ACell input) {
		Blob handle = parseHandle(RT.getIn(input, HANDLE));
		return engine.gridScheduler().trigger(handle, ctx).thenApply(result -> {
			AMap<AString, ACell> out = Maps.of(TRIGGERED, CVMBool.TRUE);
			return (result != null) ? out.assoc(RESULT, result) : out;
		});
	}

	private ACell handleList(RequestContext ctx) {
		return Maps.of(EVENTS, engine.gridScheduler().list(ctx));
	}

	private static Blob parseHandle(ACell h) {
		if (h instanceof Blob b) return b;
		if (h instanceof AString s) {
			String str = s.toString();
			if (str.startsWith("0x")) str = str.substring(2);
			Blob b = Blob.fromHex(str);
			if (b == null) throw new IllegalArgumentException("Invalid handle: " + s);
			return b;
		}
		throw new IllegalArgumentException("A 'handle' (from schedule) is required");
	}
}
