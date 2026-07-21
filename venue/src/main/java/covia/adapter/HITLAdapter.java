package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.hitl.HitlValidation;
import covia.api.Abilities;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.lattice.CapabilityChecker;
import covia.venue.RequestContext;
import covia.venue.User;

/**
 * Human-in-the-Loop requests (COG-16). A HITL request separates three
 * concerns, each carried by an existing grid primitive:
 *
 * <ul>
 *   <li><b>The record</b> — a durable document in the target user's {@code h/}
 *       inbox: what is being asked, by whom, what grants are offered, and how
 *       it was resolved. The record is the source of HITL semantics; clients
 *       discover pending asks by listing {@code h/}, never by scanning job
 *       statuses.</li>
 *   <li><b>The Job</b> — the requester's lifecycle carrier, parked in
 *       {@code INPUT_REQUIRED} (accurate but non-implicative: the status never
 *       carries HITL meaning). Resolves {@code COMPLETE} with the response as
 *       output, or {@code FAILED} on rejection/expiry.</li>
 *   <li><b>The response op</b> — the responder's action; authorised
 *       structurally by inbox ownership (respond only reads the caller's own
 *       {@code h/}).</li>
 * </ul>
 *
 * <p>This adapter owns authorisation, persistence and job control only. The
 * domain rules — ask/answer validation and the echo-consent grant intersection
 * — live in {@link HitlValidation} (pure, engine-free); the shared field
 * vocabulary and client builders live in {@link Hitl} (covia-core). The clean
 * drive is: {@code hitl:request} (Job parks {@code INPUT_REQUIRED}) →
 * {@code hitl:respond} by the inbox owner → Job completes with the response
 * (or fails on reject/expiry).</p>
 */
public class HITLAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(HITLAdapter.class);

	/** Delivery ability for cross-user asks: {@code hitl/request} on {@code <target>/h/}. */
	public static final AString ABILITY_HITL_REQUEST = Abilities.HITL_REQUEST;

	/** Default lifetime for grants offered without an explicit {@code exp} (7 days). */
	static final long DEFAULT_GRANT_LIFETIME_SECS = 7 * 24 * 3600L;

	/** Expiry timers — daemon; lost on restart and re-armed by {@link #rearmExpiries()}. */
	private static final ScheduledExecutorService EXPIRY = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "hitl-expiry");
		t.setDaemon(true);
		return t;
	});

	@Override
	public String getName() {
		return "hitl";
	}

	@Override
	public String getDescription() {
		return "Human-in-the-Loop requests (COG-16): ask a human for decisions, approvals or "
			+ "information. Requests land as durable records in the target user's h/ inbox and "
			+ "are carried by a standard Job that completes with the response or fails on "
			+ "rejection or expiry. Approval asks and options may offer capability grants, "
			+ "issued only for choices the responder explicitly makes and echoes.";
	}

	@Override
	protected void installAssets() {
		installAsset("hitl/request", "/adapters/hitl/request.json");
		installAsset("hitl/respond", "/adapters/hitl/respond.json");
		installAsset("hitl/list",    "/adapters/hitl/list.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		try {
			switch (subOp == null ? "" : subOp) {
				case "respond": {
					final ACell in = input;
					return CompletableFuture.supplyAsync(() -> handleRespond(ctx, in), VIRTUAL_EXECUTOR);
				}
				case "list":
					return CompletableFuture.completedFuture(handleList(ctx, input));
				case "request":
					return CompletableFuture.failedFuture(new IllegalStateException(
						"hitl:request is Job-carried — invoke it as an operation, not internally"));
				default:
					return CompletableFuture.failedFuture(
						new IllegalArgumentException("Unknown hitl operation: " + subOp));
			}
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (!"request".equals(getSubOperation(meta))) {
			super.invoke(job, ctx, meta, input); // one-shot ops via invokeFuture
			return;
		}
		requireInvoke(ctx);
		handleRequest(job, ctx, input); // throws -> JobManager fails the job
	}

	// ========== hitl:request — validate, authorise, deliver, park ==========

	private void handleRequest(Job job, RequestContext ctx, ACell input) {
		AString caller = ctx.getCallerDID();
		if (caller == null) throw new AuthException("Authentication required for HITL requests");

		AString target = RT.ensureString(RT.getIn(input, Hitl.USER));
		if (target == null) target = caller;
		AString title = RT.ensureString(RT.getIn(input, Hitl.TITLE));
		if (title == null) throw new IllegalArgumentException("title is required");
		AVector<ACell> asks = HitlValidation.validateAsks(RT.getIn(input, Hitl.ASKS));
		Long timeoutSecs = parseTimeout(RT.getIn(input, Hitl.TIMEOUT));

		requireDeliverable(ctx, caller, target);

		// Build and deliver the record (venue-mediated: `from` is the verified
		// caller, and h/ is not writable via covia:write), then park the Job.
		long nowMs = System.currentTimeMillis();
		AString id = Strings.create(job.getID().toHexString());
		AMap<AString, ACell> record = buildRecord(id, caller, ctx.getAgentId(), title,
			RT.ensureString(RT.getIn(input, Hitl.DESCRIPTION)), asks, nowMs, timeoutSecs);

		final AString targetDID = target;
		engine.getVenueState().users().ensure(targetDID).putHitlRequest(id, record);
		Blob jobId = job.getID();
		job.setCancelHook(() -> markResolved(targetDID, id, Hitl.CANCELLED, null));
		job.setStatus(Status.INPUT_REQUIRED);
		if (timeoutSecs != null) scheduleExpiry(targetDID, id, jobId, timeoutSecs * 1000);
		log.info("HITL request {} delivered to {} (from {})", id, targetDID, caller);
	}

	/** A self-ask is always permitted; delivering into ANOTHER user's inbox
	 *  requires hitl/request on {@code <target>/h/} — checked against the
	 *  caller's grant scope AND (cross-user) their presented proofs. */
	private void requireDeliverable(RequestContext ctx, AString caller, AString target) {
		if (target.equals(caller)) return;
		AString resource = Strings.create(target + "/h/");
		engine.requireAuthority(ctx,resource, ABILITY_HITL_REQUEST);
		long now = System.currentTimeMillis() / 1000;
		if (!CapabilityChecker.proofsCover(ctx.getProofs(), caller, engine.getDIDString(),
				resource, ABILITY_HITL_REQUEST, now)) {
			throw new AuthException("HITL delivery denied: requires " + ABILITY_HITL_REQUEST
				+ " on " + resource + " — present a delegation from the target user "
				+ "(transport ucans / bearer)");
		}
	}

	private static AMap<AString, ACell> buildRecord(AString id, AString from, AString agentId,
			AString title, AString description, AVector<ACell> asks, long nowMs, Long timeoutSecs) {
		AMap<AString, ACell> record = Maps.of(
			Hitl.ID, id,
			Hitl.FROM, from,
			Hitl.TITLE, title,
			Hitl.ASKS, asks,
			Hitl.STATUS, Hitl.OPEN,
			Hitl.CREATED, CVMLong.create(nowMs));
		if (description != null) record = record.assoc(Hitl.DESCRIPTION, description);
		if (agentId != null) record = record.assoc(Hitl.AGENT, agentId);
		if (timeoutSecs != null) record = record.assoc(Hitl.EXPIRES, CVMLong.create(nowMs + timeoutSecs * 1000));
		return record;
	}

	private static Long parseTimeout(ACell timeoutCell) {
		if (timeoutCell == null) return null;
		CVMLong t = RT.ensureLong(timeoutCell);
		if (t == null || t.longValue() <= 0) {
			throw new IllegalArgumentException("timeout must be a positive number of seconds");
		}
		return t.longValue();
	}

	// ========== hitl:respond — resolve record and job ==========

	@SuppressWarnings("unchecked")
	private ACell handleRespond(RequestContext ctx, ACell input) {
		AString caller = ctx.getCallerDID();
		if (caller == null) throw new AuthException("Authentication required");
		AString id = RT.ensureString(RT.getIn(input, Hitl.ID));
		if (id == null) throw new IllegalArgumentException("id is required");
		// Records key on bare hex, but REST renders job ids 0x-prefixed —
		// accept both, so a pasted job id just works.
		String idStr = id.toString();
		if (idStr.startsWith("0x") || idStr.startsWith("0X")) {
			id = Strings.create(idStr.substring(2));
		}

		// Structural authorisation: respond reads the CALLER's own inbox only.
		User user = engine.getVenueState().users().ensure(caller);
		AMap<AString, ACell> record = user.getHitlRequest(id);
		if (record == null) {
			throw new IllegalArgumentException("No HITL request " + id + " in your inbox");
		}
		if (!Hitl.OPEN.equals(record.get(Hitl.STATUS))) {
			throw new IllegalStateException("HITL request " + id + " is not open (status: "
				+ record.get(Hitl.STATUS) + ")");
		}
		// Lazy expiry: a due-but-untriggered timer (e.g. lost on restart before
		// re-arm) must not let an expired request be answered.
		CVMLong expires = RT.ensureLong(record.get(Hitl.EXPIRES));
		if (expires != null && System.currentTimeMillis() > expires.longValue()) {
			expireRequest(caller, id, Blob.parse(id.toString()));
			throw new IllegalStateException("HITL request " + id + " has expired");
		}

		AString outcome = RT.ensureString(RT.getIn(input, Hitl.OUTCOME));
		AString comment = RT.ensureString(RT.getIn(input, Hitl.COMMENT));
		Job job = engine.jobs().getJob(Blob.parse(id.toString()));
		if (Hitl.REJECT.equals(outcome)) {
			return resolveReject(user, id, record, comment, job);
		}
		if (Hitl.ANSWER.equals(outcome)) {
			return resolveAnswer(user, id, record, input, comment, job);
		}
		throw new IllegalArgumentException("outcome must be 'answer' or 'reject'");
	}

	private ACell resolveReject(User user, AString id, AMap<AString, ACell> record,
			AString comment, Job job) {
		AMap<AString, ACell> response = Maps.of(Hitl.OUTCOME, Hitl.REJECT);
		if (comment != null) response = response.assoc(Hitl.COMMENT, comment);
		user.putHitlRequest(id, record.assoc(Hitl.STATUS, Hitl.REJECTED).assoc(Hitl.RESPONSE, response));
		if (job != null && !job.isFinished()) {
			// The reason must travel in the job error — the requester cannot
			// read the responder's inbox.
			job.fail("HITL request rejected" + (comment != null ? ": " + comment : ""));
		}
		return Maps.of(Hitl.ID, id, Hitl.STATUS, Hitl.REJECTED);
	}

	@SuppressWarnings("unchecked")
	private ACell resolveAnswer(User user, AString id, AMap<AString, ACell> record,
			ACell input, AString comment, Job job) {
		AVector<ACell> asks = (AVector<ACell>) RT.getIn(record, Hitl.ASKS);
		ACell answersCell = RT.getIn(input, Hitl.ANSWERS);
		AMap<AString, ACell> answers = (answersCell instanceof AMap)
			? (AMap<AString, ACell>) answersCell : Maps.empty();

		// Domain rules: validate answers, compute triggered offers, then the
		// echo-consent intersection — all pure (HitlValidation).
		AVector<ACell> triggered = HitlValidation.validateAnswers(asks, answers);
		AVector<ACell> approved = HitlValidation.intersectEchoedGrants(RT.getIn(input, Hitl.GRANTS), triggered);
		AString token = (approved.count() > 0)
			? issueGrants(user.getDID(), RT.ensureString(record.get(Hitl.FROM)), approved)
			: null;

		AMap<AString, ACell> response = Maps.of(Hitl.OUTCOME, Hitl.ANSWER, Hitl.ANSWERS, answers);
		ACell comments = RT.getIn(input, Hitl.COMMENTS);
		if (comments instanceof AMap) response = response.assoc(Hitl.COMMENTS, comments);
		if (comment != null) response = response.assoc(Hitl.COMMENT, comment);
		if (approved.count() > 0) response = response.assoc(Hitl.GRANTS, approved);
		user.putHitlRequest(id, record.assoc(Hitl.STATUS, Hitl.ANSWERED).assoc(Hitl.RESPONSE, response));

		if (job != null && !job.isFinished()) {
			AMap<AString, ACell> output = response.assoc(Hitl.ID, id);
			if (token != null) output = output.assoc(Hitl.TOKEN, token);
			job.completeWith(output);
		}
		return Maps.of(Hitl.ID, id, Hitl.STATUS, Hitl.ANSWERED);
	}

	/** Issues the approved grants as a single token via the granting surface
	 *  (ucan:issue under the RESPONDER's authority — bare paths canonicalise to
	 *  the responder's namespace). Token exp = earliest grant exp, defaulted. */
	private AString issueGrants(AString responder, AString requester, AVector<ACell> approved) {
		if (requester == null) throw new IllegalStateException("record has no requester identity");
		long now = System.currentTimeMillis() / 1000;
		long exp = now + DEFAULT_GRANT_LIFETIME_SECS;
		for (long i = 0; i < approved.count(); i++) {
			CVMLong g = RT.ensureLong(RT.getIn(approved.get(i), Hitl.EXP));
			if (g != null && g.longValue() > now && g.longValue() < exp) exp = g.longValue();
		}
		try {
			ACell result = engine.jobs().invokeInternal("v/ops/ucan/issue",
				Maps.of(Strings.intern("aud"), requester,
					Strings.intern("att"), approved,
					Hitl.EXP, CVMLong.create(exp)),
				RequestContext.of(responder)).get(30, TimeUnit.SECONDS);
			AString token = RT.ensureString(RT.getIn(result, Hitl.TOKEN));
			if (token == null) throw new IllegalStateException("ucan:issue returned no token");
			return token;
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			throw new IllegalStateException("Grant issuance failed: " + cause.getMessage(), cause);
		}
	}

	// ========== hitl:list ==========

	@SuppressWarnings("unchecked")
	private ACell handleList(RequestContext ctx, ACell input) {
		AString caller = ctx.getCallerDID();
		if (caller == null) throw new AuthException("Authentication required");
		AString filter = RT.ensureString(RT.getIn(input, Hitl.STATUS));
		AMap<AString, ACell> all = engine.getVenueState().users().ensure(caller).getHitlRequests();
		AVector<ACell> items = Vectors.empty();
		for (long i = 0; i < all.count(); i++) {
			ACell v = all.entryAt(i).getValue();
			if (!(v instanceof AMap)) continue;
			AMap<AString, ACell> rec = (AMap<AString, ACell>) v;
			if (filter != null && !filter.equals(rec.get(Hitl.STATUS))) continue;
			AMap<AString, ACell> summary = Maps.of(
				Hitl.ID, rec.get(Hitl.ID),
				Hitl.FROM, rec.get(Hitl.FROM),
				Hitl.TITLE, rec.get(Hitl.TITLE),
				Hitl.STATUS, rec.get(Hitl.STATUS),
				Hitl.CREATED, rec.get(Hitl.CREATED));
			if (rec.get(Hitl.EXPIRES) != null) summary = summary.assoc(Hitl.EXPIRES, rec.get(Hitl.EXPIRES));
			items = items.conj(summary);
		}
		return Maps.of(Hitl.ITEMS, items, Hitl.COUNT, CVMLong.create(items.count()));
	}

	// ========== Expiry ==========

	private void scheduleExpiry(AString targetDID, AString id, Blob jobId, long delayMs) {
		EXPIRY.schedule(() -> {
			try {
				expireRequest(targetDID, id, jobId);
			} catch (Throwable t) {
				log.warn("HITL expiry for {} failed: {}", id, t.getMessage());
			}
		}, delayMs, TimeUnit.MILLISECONDS);
	}

	/** Expires an open request: record → expired, job → FAILED. Terminal-state
	 *  stickiness makes the race against a response commit exactly one winner. */
	private void expireRequest(AString targetDID, AString id, Blob jobId) {
		boolean marked = markResolved(targetDID, id, Hitl.EXPIRED, null);
		if (!marked) return; // already resolved
		Job job = engine.jobs().getJob(jobId);
		if (job != null && !job.isFinished()) job.fail("HITL request expired");
		log.info("HITL request {} expired", id);
	}

	/** Marks an OPEN record with a terminal status; returns false if it was
	 *  already resolved (no overwrite of an answered/rejected record). */
	private boolean markResolved(AString targetDID, AString id, AString status, AMap<AString, ACell> response) {
		User user = engine.getVenueState().users().ensure(targetDID);
		AMap<AString, ACell> record = user.getHitlRequest(id);
		if (record == null || !Hitl.OPEN.equals(record.get(Hitl.STATUS))) return false;
		AMap<AString, ACell> updated = record.assoc(Hitl.STATUS, status);
		if (response != null) updated = updated.assoc(Hitl.RESPONSE, response);
		user.putHitlRequest(id, updated);
		return true;
	}

	/**
	 * Re-arms expiry enforcement after a venue restart (COG-16: expiry MUST
	 * survive restarts). Scans users' inboxes for OPEN requests with an
	 * {@code expires} stamp: overdue ones expire immediately, future ones get
	 * fresh timers. Called at venue launch after {@code recoverJobs()} (which
	 * restores the parked INPUT_REQUIRED jobs these records refer to).
	 *
	 * @return number of requests re-armed or expired
	 */
	@SuppressWarnings("unchecked")
	public int rearmExpiries() {
		AMap<AString, ACell> all = engine.getVenueState().users().getAll();
		if (all == null) return 0;
		int count = 0;
		long nowMs = System.currentTimeMillis();
		for (long u = 0; u < all.count(); u++) {
			AString did = RT.ensureString(all.entryAt(u).getKey());
			if (did == null) continue;
			User user = engine.getVenueState().users().ensure(did);
			AMap<AString, ACell> reqs = user.getHitlRequests();
			for (long i = 0; i < reqs.count(); i++) {
				ACell v = reqs.entryAt(i).getValue();
				if (!(v instanceof AMap)) continue;
				AMap<AString, ACell> rec = (AMap<AString, ACell>) v;
				if (!Hitl.OPEN.equals(rec.get(Hitl.STATUS))) continue;
				CVMLong expires = RT.ensureLong(rec.get(Hitl.EXPIRES));
				if (expires == null) continue;
				AString id = RT.ensureString(rec.get(Hitl.ID));
				if (id == null) continue;
				Blob jobId = Blob.parse(id.toString());
				long delay = expires.longValue() - nowMs;
				if (delay <= 0) {
					expireRequest(did, id, jobId);
				} else {
					scheduleExpiry(did, id, jobId, delay);
				}
				count++;
			}
		}
		if (count > 0) log.info("HITL: re-armed expiry for {} open request(s)", count);
		return count;
	}
}
