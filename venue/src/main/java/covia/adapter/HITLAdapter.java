package covia.adapter;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
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
 * <p>Delivery into another user's inbox is a cross-user act gated by the
 * {@code hitl/request} ability on {@code <target>/h/} — checked against the
 * caller's ceiling and, cross-user, their presented proofs. Records are
 * venue-mediated: {@code h/} is not writable via {@code covia:write}, so
 * {@code from} is always the verified caller identity.</p>
 *
 * <p>Grants ride explicit choices only (approval asks and options). On an
 * {@code answer} outcome the response must ECHO the grants it approves; the
 * venue issues exactly the intersection of echoed and offered-and-triggered,
 * via {@code ucan:issue} under the responder's own authority (the granting
 * surface, COG-17).</p>
 */
public class HITLAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(HITLAdapter.class);

	// ========== Record field keys ==========

	static final AString K_ID          = Strings.intern("id");
	static final AString K_FROM        = Strings.intern("from");
	static final AString K_AGENT       = Strings.intern("agent");
	static final AString K_TITLE       = Strings.intern("title");
	static final AString K_DESCRIPTION = Strings.intern("description");
	static final AString K_ASKS        = Strings.intern("asks");
	static final AString K_STATUS      = Strings.intern("status");
	static final AString K_CREATED     = Strings.intern("created");
	static final AString K_EXPIRES     = Strings.intern("expires");
	static final AString K_RESPONSE    = Strings.intern("response");

	// Ask / option fields
	static final AString K_TYPE     = Strings.intern("type");
	static final AString K_PROMPT   = Strings.intern("prompt");
	static final AString K_OPTIONS  = Strings.intern("options");
	static final AString K_REQUIRED = Strings.intern("required");
	static final AString K_LABEL    = Strings.intern("label");
	static final AString K_GRANTS   = Strings.intern("grants");

	// Request / response fields
	static final AString K_USER     = Strings.intern("user");
	static final AString K_TIMEOUT  = Strings.intern("timeout");
	static final AString K_OUTCOME  = Strings.intern("outcome");
	static final AString K_ANSWERS  = Strings.intern("answers");
	static final AString K_COMMENTS = Strings.intern("comments");
	static final AString K_COMMENT  = Strings.intern("comment");
	static final AString K_TOKEN    = Strings.intern("token");
	static final AString K_ITEMS    = Strings.intern("items");
	static final AString K_COUNT    = Strings.intern("count");

	// Record statuses
	static final AString S_OPEN      = Strings.intern("open");
	static final AString S_ANSWERED  = Strings.intern("answered");
	static final AString S_REJECTED  = Strings.intern("rejected");
	static final AString S_EXPIRED   = Strings.intern("expired");
	static final AString S_CANCELLED = Strings.intern("cancelled");

	// Ask types / outcomes
	static final AString T_TEXT       = Strings.intern("text");
	static final AString T_APPROVAL   = Strings.intern("approval");
	static final AString T_CHOICE     = Strings.intern("choice");
	static final AString T_CHECKBOXES = Strings.intern("checkboxes");
	static final AString O_ANSWER = Strings.intern("answer");
	static final AString O_REJECT = Strings.intern("reject");

	/** Delivery ability for cross-user asks: {@code hitl/request} on {@code <target>/h/}. */
	public static final AString ABILITY_HITL_REQUEST = Strings.intern("hitl/request");

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

	// ========== hitl:request ==========

	@SuppressWarnings("unchecked")
	private void handleRequest(Job job, RequestContext ctx, ACell input) {
		AString caller = ctx.getCallerDID();
		if (caller == null) throw new AuthException("Authentication required for HITL requests");

		AString target = RT.ensureString(RT.getIn(input, K_USER));
		if (target == null) target = caller;

		AString title = RT.ensureString(RT.getIn(input, K_TITLE));
		if (title == null) throw new IllegalArgumentException("title is required");
		AVector<ACell> asks = validateAsks(RT.getIn(input, K_ASKS));

		// Delivery authorisation: a self-ask is always permitted; delivering into
		// ANOTHER user's inbox requires hitl/request on <target>/h/ — checked
		// against the caller's ceiling AND (cross-user) their presented proofs.
		if (!target.equals(caller)) {
			AString resource = Strings.create(target + "/h/");
			ctx.requireCapability(resource, ABILITY_HITL_REQUEST);
			long now = System.currentTimeMillis() / 1000;
			if (!CapabilityChecker.proofsCover(ctx.getProofs(), caller, engine.getDIDString(),
					resource, ABILITY_HITL_REQUEST, now)) {
				throw new AuthException("HITL delivery denied: requires " + ABILITY_HITL_REQUEST
					+ " on " + resource + " — present a delegation from the target user "
					+ "(transport ucans / bearer)");
			}
		}

		long nowMs = System.currentTimeMillis();
		AString id = Strings.create(job.getID().toHexString());
		AMap<AString, ACell> record = Maps.of(
			K_ID, id,
			K_FROM, caller,
			K_TITLE, title,
			K_ASKS, asks,
			K_STATUS, S_OPEN,
			K_CREATED, CVMLong.create(nowMs));
		AString description = RT.ensureString(RT.getIn(input, K_DESCRIPTION));
		if (description != null) record = record.assoc(K_DESCRIPTION, description);
		AString agentId = ctx.getAgentId();
		if (agentId != null) record = record.assoc(K_AGENT, agentId);

		Long timeoutSecs = null;
		ACell timeoutCell = RT.getIn(input, K_TIMEOUT);
		if (timeoutCell != null) {
			CVMLong t = RT.ensureLong(timeoutCell);
			if (t == null || t.longValue() <= 0) {
				throw new IllegalArgumentException("timeout must be a positive number of seconds");
			}
			timeoutSecs = t.longValue();
			record = record.assoc(K_EXPIRES, CVMLong.create(nowMs + timeoutSecs * 1000));
		}

		// Venue-mediated delivery into the target's inbox, then park the Job.
		final AString targetDID = target;
		engine.getVenueState().users().ensure(targetDID).putHitlRequest(id, record);
		Blob jobId = job.getID();
		job.setCancelHook(() -> markResolved(targetDID, id, S_CANCELLED, null));
		job.setStatus(Status.INPUT_REQUIRED);
		if (timeoutSecs != null) scheduleExpiry(targetDID, id, jobId, timeoutSecs * 1000);
		log.info("HITL request {} delivered to {} (from {})", id, targetDID, caller);
	}

	// ========== hitl:respond ==========

	@SuppressWarnings("unchecked")
	private ACell handleRespond(RequestContext ctx, ACell input) {
		AString caller = ctx.getCallerDID();
		if (caller == null) throw new AuthException("Authentication required");
		AString id = RT.ensureString(RT.getIn(input, K_ID));
		if (id == null) throw new IllegalArgumentException("id is required");

		// Structural authorisation: respond reads the CALLER's own inbox only.
		User user = engine.getVenueState().users().ensure(caller);
		AMap<AString, ACell> record = user.getHitlRequest(id);
		if (record == null) {
			throw new IllegalArgumentException("No HITL request " + id + " in your inbox");
		}
		if (!S_OPEN.equals(record.get(K_STATUS))) {
			throw new IllegalStateException("HITL request " + id + " is not open (status: "
				+ record.get(K_STATUS) + ")");
		}
		// Lazy expiry: a due-but-untriggered timer (e.g. lost on restart before
		// re-arm) must not let an expired request be answered.
		CVMLong expires = RT.ensureLong(record.get(K_EXPIRES));
		if (expires != null && System.currentTimeMillis() > expires.longValue()) {
			expireRequest(caller, id, Blob.parse(id.toString()));
			throw new IllegalStateException("HITL request " + id + " has expired");
		}

		AString outcome = RT.ensureString(RT.getIn(input, K_OUTCOME));
		if (!O_ANSWER.equals(outcome) && !O_REJECT.equals(outcome)) {
			throw new IllegalArgumentException("outcome must be 'answer' or 'reject'");
		}
		AString comment = RT.ensureString(RT.getIn(input, K_COMMENT));
		Job job = engine.jobs().getJob(Blob.parse(id.toString()));

		if (O_REJECT.equals(outcome)) {
			AMap<AString, ACell> response = Maps.of(K_OUTCOME, O_REJECT);
			if (comment != null) response = response.assoc(K_COMMENT, comment);
			user.putHitlRequest(id, record.assoc(K_STATUS, S_REJECTED).assoc(K_RESPONSE, response));
			if (job != null && !job.isFinished()) {
				job.fail("HITL request rejected" + (comment != null ? ": " + comment : ""));
			}
			return Maps.of(K_ID, id, K_STATUS, S_REJECTED);
		}

		// outcome == answer: validate against the asks, collecting triggered offers.
		AVector<ACell> asks = (AVector<ACell>) RT.getIn(record, K_ASKS);
		ACell answersCell = RT.getIn(input, K_ANSWERS);
		AMap<AString, ACell> answers = (answersCell instanceof AMap)
			? (AMap<AString, ACell>) answersCell : Maps.empty();
		AVector<ACell> triggered = validateAnswers(asks, answers);

		// Echo-consent: issue exactly the intersection of echoed and triggered.
		AVector<ACell> approved = intersectEchoedGrants(RT.getIn(input, K_GRANTS), triggered);
		AString token = (approved.count() > 0)
			? issueGrants(caller, RT.ensureString(record.get(K_FROM)), approved)
			: null;

		AMap<AString, ACell> response = Maps.of(K_OUTCOME, O_ANSWER, K_ANSWERS, answers);
		ACell comments = RT.getIn(input, K_COMMENTS);
		if (comments instanceof AMap) response = response.assoc(K_COMMENTS, comments);
		if (comment != null) response = response.assoc(K_COMMENT, comment);
		if (approved.count() > 0) response = response.assoc(K_GRANTS, approved);
		user.putHitlRequest(id, record.assoc(K_STATUS, S_ANSWERED).assoc(K_RESPONSE, response));

		if (job != null && !job.isFinished()) {
			AMap<AString, ACell> output = response.assoc(K_ID, id);
			if (token != null) output = output.assoc(K_TOKEN, token);
			job.completeWith(output);
		}
		return Maps.of(K_ID, id, K_STATUS, S_ANSWERED);
	}

	/** Issues the approved grants as a single token via the granting surface
	 *  (ucan:issue under the RESPONDER's authority — bare paths canonicalise to
	 *  the responder's namespace). Token exp = earliest grant exp, defaulted. */
	private AString issueGrants(AString responder, AString requester, AVector<ACell> approved) {
		if (requester == null) throw new IllegalStateException("record has no requester identity");
		long now = System.currentTimeMillis() / 1000;
		long exp = now + DEFAULT_GRANT_LIFETIME_SECS;
		for (long i = 0; i < approved.count(); i++) {
			CVMLong g = RT.ensureLong(RT.getIn(approved.get(i), Strings.intern("exp")));
			if (g != null && g.longValue() > now && g.longValue() < exp) exp = g.longValue();
		}
		try {
			ACell result = engine.jobs().invokeInternal("v/ops/ucan/issue",
				Maps.of(Strings.intern("aud"), requester,
					Strings.intern("att"), approved,
					Strings.intern("exp"), CVMLong.create(exp)),
				RequestContext.of(responder)).get(30, TimeUnit.SECONDS);
			AString token = RT.ensureString(RT.getIn(result, K_TOKEN));
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
		AString filter = RT.ensureString(RT.getIn(input, K_STATUS));
		AMap<AString, ACell> all = engine.getVenueState().users().ensure(caller).getHitlRequests();
		AVector<ACell> items = Vectors.empty();
		for (long i = 0; i < all.count(); i++) {
			ACell v = all.entryAt(i).getValue();
			if (!(v instanceof AMap)) continue;
			AMap<AString, ACell> rec = (AMap<AString, ACell>) v;
			if (filter != null && !filter.equals(rec.get(K_STATUS))) continue;
			AMap<AString, ACell> summary = Maps.of(
				K_ID, rec.get(K_ID),
				K_FROM, rec.get(K_FROM),
				K_TITLE, rec.get(K_TITLE),
				K_STATUS, rec.get(K_STATUS),
				K_CREATED, rec.get(K_CREATED));
			if (rec.get(K_EXPIRES) != null) summary = summary.assoc(K_EXPIRES, rec.get(K_EXPIRES));
			items = items.conj(summary);
		}
		return Maps.of(K_ITEMS, items, K_COUNT, CVMLong.create(items.count()));
	}

	// ========== Validation ==========

	private static final Set<String> ASK_TYPES = Set.of("text", "approval", "choice", "checkboxes");

	/** Validates the asks at submission (COG-16): ids unique, types known,
	 *  options present/unique where required, grants only on approval asks and
	 *  options. Throws IllegalArgumentException — no record, no parked job. */
	@SuppressWarnings("unchecked")
	static AVector<ACell> validateAsks(ACell asksCell) {
		if (!(asksCell instanceof AVector) || ((AVector<ACell>) asksCell).count() == 0) {
			throw new IllegalArgumentException("asks must be a non-empty array");
		}
		AVector<ACell> asks = (AVector<ACell>) asksCell;
		Set<String> ids = new HashSet<>();
		for (long i = 0; i < asks.count(); i++) {
			AMap<AString, ACell> ask = RT.castMap(asks.get(i));
			if (ask == null) throw new IllegalArgumentException("asks[" + i + "] must be an object");
			AString id = RT.ensureString(ask.get(K_ID));
			AString type = RT.ensureString(ask.get(K_TYPE));
			AString prompt = RT.ensureString(ask.get(K_PROMPT));
			if (id == null || !ids.add(id.toString())) {
				throw new IllegalArgumentException("asks[" + i + "].id is required and must be unique");
			}
			if (type == null || !ASK_TYPES.contains(type.toString())) {
				throw new IllegalArgumentException("asks[" + i + "].type must be one of " + ASK_TYPES);
			}
			if (prompt == null) throw new IllegalArgumentException("asks[" + i + "].prompt is required");
			boolean optionType = T_CHOICE.equals(type) || T_CHECKBOXES.equals(type);
			ACell optionsCell = ask.get(K_OPTIONS);
			if (optionType) {
				if (!(optionsCell instanceof AVector) || ((AVector<ACell>) optionsCell).count() == 0) {
					throw new IllegalArgumentException("asks[" + i + "].options must be a non-empty array");
				}
				Set<String> optionIds = new HashSet<>();
				AVector<ACell> options = (AVector<ACell>) optionsCell;
				for (long j = 0; j < options.count(); j++) {
					AMap<AString, ACell> opt = RT.castMap(options.get(j));
					AString optId = (opt != null) ? RT.ensureString(opt.get(K_ID)) : null;
					if (opt == null || optId == null || !optionIds.add(optId.toString())) {
						throw new IllegalArgumentException("asks[" + i + "].options[" + j
							+ "] must be an object with a unique id");
					}
					validateGrantList(opt.get(K_GRANTS), "asks[" + i + "].options[" + j + "]");
				}
			} else if (optionsCell != null) {
				throw new IllegalArgumentException("asks[" + i + "]: options only apply to choice/checkboxes");
			}
			// Grants attach ONLY where an explicit choice confers them:
			// approval asks and options (validated above). Nowhere else.
			ACell grants = ask.get(K_GRANTS);
			if (grants != null && !T_APPROVAL.equals(type)) {
				throw new IllegalArgumentException("asks[" + i + "]: grants only attach to approval "
					+ "asks and options — a grant must be the consequence of an explicit choice");
			}
			validateGrantList(grants, "asks[" + i + "]");
		}
		return asks;
	}

	private static void validateGrantList(ACell grantsCell, String where) {
		if (grantsCell == null) return;
		if (!(grantsCell instanceof AVector)) {
			throw new IllegalArgumentException(where + ".grants must be an array");
		}
		AVector<ACell> grants = (AVector<ACell>) grantsCell;
		for (long i = 0; i < grants.count(); i++) {
			AMap<AString, ACell> g = RT.castMap(grants.get(i));
			if (g == null || RT.ensureString(g.get(Capability.WITH)) == null
					|| RT.ensureString(g.get(Capability.CAN)) == null) {
				throw new IllegalArgumentException(where + ".grants[" + i + "] must be {with, can, exp?}");
			}
		}
	}

	/** Validates the answers against the asks; returns the OFFERED grants that
	 *  the choices actually triggered (approved approval asks, selected options). */
	@SuppressWarnings("unchecked")
	static AVector<ACell> validateAnswers(AVector<ACell> asks, AMap<AString, ACell> answers) {
		AVector<ACell> triggered = Vectors.empty();
		Set<String> askIds = new HashSet<>();
		for (long i = 0; i < asks.count(); i++) {
			AMap<AString, ACell> ask = (AMap<AString, ACell>) asks.get(i);
			AString askId = RT.ensureString(ask.get(K_ID));
			askIds.add(askId.toString());
			AString type = RT.ensureString(ask.get(K_TYPE));
			ACell answer = answers.get(askId);
			if (answer == null) {
				if (CVMBool.TRUE.equals(ask.get(K_REQUIRED))) {
					throw new IllegalArgumentException("required ask '" + askId + "' is unanswered");
				}
				continue;
			}
			if (T_TEXT.equals(type)) {
				if (RT.ensureString(answer) == null) {
					throw new IllegalArgumentException("answer for '" + askId + "' must be a string");
				}
			} else if (T_APPROVAL.equals(type)) {
				if (!(answer instanceof CVMBool)) {
					throw new IllegalArgumentException("answer for '" + askId + "' must be a boolean");
				}
				if (CVMBool.TRUE.equals(answer)) {
					triggered = appendGrants(triggered, ask.get(K_GRANTS));
				}
			} else { // choice / checkboxes
				AVector<ACell> options = (AVector<ACell>) ask.get(K_OPTIONS);
				if (T_CHOICE.equals(type)) {
					AMap<AString, ACell> opt = findOption(options, RT.ensureString(answer));
					if (opt == null) {
						throw new IllegalArgumentException("answer for '" + askId + "' must name an option id");
					}
					triggered = appendGrants(triggered, opt.get(K_GRANTS));
				} else {
					if (!(answer instanceof AVector)) {
						throw new IllegalArgumentException("answer for '" + askId + "' must be an array of option ids");
					}
					Set<String> seen = new HashSet<>();
					AVector<ACell> selected = (AVector<ACell>) answer;
					for (long j = 0; j < selected.count(); j++) {
						AString optId = RT.ensureString(selected.get(j));
						AMap<AString, ACell> opt = findOption(options, optId);
						if (opt == null || !seen.add(optId.toString())) {
							throw new IllegalArgumentException("answer for '" + askId
								+ "' contains an unknown or duplicate option id");
						}
						triggered = appendGrants(triggered, opt.get(K_GRANTS));
					}
				}
			}
		}
		// Unknown answer keys are an error — answers bind to asks, exactly.
		for (long i = 0; i < answers.count(); i++) {
			AString key = RT.ensureString(answers.entryAt(i).getKey());
			if (key == null || !askIds.contains(key.toString())) {
				throw new IllegalArgumentException("answers contains unknown ask id: " + key);
			}
		}
		return triggered;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> findOption(AVector<ACell> options, AString optId) {
		if (optId == null || options == null) return null;
		for (long i = 0; i < options.count(); i++) {
			AMap<AString, ACell> opt = (AMap<AString, ACell>) options.get(i);
			if (optId.equals(opt.get(K_ID))) return opt;
		}
		return null;
	}

	private static AVector<ACell> appendGrants(AVector<ACell> acc, ACell grantsCell) {
		if (!(grantsCell instanceof AVector)) return acc;
		AVector<ACell> grants = (AVector<ACell>) grantsCell;
		for (long i = 0; i < grants.count(); i++) acc = acc.conj(grants.get(i));
		return acc;
	}

	/** Echo-consent (COG-16): the venue issues exactly the intersection of the
	 *  ECHOED grants and the offers actually TRIGGERED by the choices made. An
	 *  echoed grant that was not offered-and-triggered fails the response. The
	 *  issued capability is always the OFFER's own map (with/can/exp as offered),
	 *  matched by {with, can} equality. */
	static AVector<ACell> intersectEchoedGrants(ACell echoedCell, AVector<ACell> triggered) {
		if (echoedCell == null) return Vectors.empty();
		if (!(echoedCell instanceof AVector)) {
			throw new IllegalArgumentException("grants must be an array of {with, can} capabilities");
		}
		AVector<ACell> echoed = (AVector<ACell>) echoedCell;
		AVector<ACell> approved = Vectors.empty();
		for (long i = 0; i < echoed.count(); i++) {
			AMap<AString, ACell> e = RT.castMap(echoed.get(i));
			AString with = (e != null) ? RT.ensureString(e.get(Capability.WITH)) : null;
			AString can = (e != null) ? RT.ensureString(e.get(Capability.CAN)) : null;
			ACell match = null;
			if (with != null && can != null) {
				for (long j = 0; j < triggered.count(); j++) {
					ACell offer = triggered.get(j);
					if (with.equals(RT.getIn(offer, Capability.WITH))
							&& can.equals(RT.getIn(offer, Capability.CAN))) {
						match = offer;
						break;
					}
				}
			}
			if (match == null) {
				throw new IllegalArgumentException("echoed grant " + i + " was not offered by the "
					+ "choices made — the venue issues only offered-and-triggered grants");
			}
			approved = approved.conj(match);
		}
		return approved;
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
		boolean marked = markResolved(targetDID, id, S_EXPIRED, null);
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
		if (record == null || !S_OPEN.equals(record.get(K_STATUS))) return false;
		AMap<AString, ACell> updated = record.assoc(K_STATUS, status);
		if (response != null) updated = updated.assoc(K_RESPONSE, response);
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
				if (!S_OPEN.equals(rec.get(K_STATUS))) continue;
				CVMLong expires = RT.ensureLong(rec.get(K_EXPIRES));
				if (expires == null) continue;
				AString id = RT.ensureString(rec.get(K_ID));
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
