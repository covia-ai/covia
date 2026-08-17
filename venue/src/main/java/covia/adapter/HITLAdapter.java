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
import convex.core.data.AccountKey;
import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import covia.adapter.hitl.HitlValidation;
import covia.api.Abilities;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.venue.RequestContext;
import covia.venue.UcanJwtValidator;
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

	/** Optional adapter-config ceiling. Absent/null means no venue maximum. */
	static final AString CONFIG_MAX_GRANT_LIFETIME_SECS = Strings.intern("maxGrantLifetimeSecs");

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

	/** Public settings: maxGrantLifetimeSecs — plain operating parameters, nothing an operator would keep private. */
	@Override
	public AMap<AString, ACell> publicConfig() {
		return publicConfig("maxGrantLifetimeSecs");
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
		// The adapter's own skill: v/skills/hitl lives and dies with this adapter.
		installSkill("hitl", "/skills/hitl.json");
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
		// Default target is the calling USER — for an agent, its owner. "Ask my
		// human" is the whole point of HITL, and an agent has no inbox of its own
		// to deliver into.
		if (target == null) target = ctx.getUserDID();
		AString title = RT.ensureString(RT.getIn(input, Hitl.TITLE));
		if (title == null) throw new IllegalArgumentException("title is required");
		AVector<ACell> asks = HitlValidation.validateAsks(RT.getIn(input, Hitl.ASKS));
		validateOfferedGrantExpiries(asks, System.currentTimeMillis() / 1000);
		Long timeoutSecs = parseTimeout(RT.getIn(input, Hitl.TIMEOUT));

		requireDeliverable(ctx, caller, target);

		// Build and deliver the record (venue-mediated: `from` is the verified
		// caller, and h/ is not writable via covia:write), then park the Job.
		long nowMs = System.currentTimeMillis();
		AString id = Strings.create(job.getID().toHexString());
		AMap<AString, ACell> record = buildRecord(id, caller, ctx.getAgentId(), title,
			RT.ensureString(RT.getIn(input, Hitl.DESCRIPTION)), asks, nowMs, timeoutSecs);

		final AString targetDID = target;
		User targetUser = engine.getVenueState().users().get(targetDID);
		if (targetUser == null) {
			throw new IllegalArgumentException("HITL target user is not registered at this venue: "
				+ targetDID);
		}
		targetUser.putHitlRequest(id, record);
		Blob jobId = job.getID();
		job.setCancelHook(() -> markResolved(targetDID, id, Hitl.CANCELLED, null));
		job.setStatus(Status.INPUT_REQUIRED);
		if (timeoutSecs != null) scheduleExpiry(targetDID, id, jobId, timeoutSecs * 1000);
		log.info("HITL request {} delivered to {} (from {})", id, targetDID, caller);
	}

	/** An ask within the caller's own user family is always permitted — a user
	 *  asking themselves, or an agent asking the owner whose namespace it runs
	 *  in (the documented default, which needs no delegation). Delivering into
	 *  ANOTHER user's inbox requires hitl/request on {@code <target>/h/} —
	 *  checked against the caller's grant scope AND (cross-user) their presented
	 *  proofs. Proximity relaxes friction here, not authorisation: a foreign
	 *  target still needs a delegation. */
	private void requireDeliverable(RequestContext ctx, AString caller, AString target) {
		if (ctx.relationTo(target).isSameUser()) return;
		AString resource = Strings.create(target + "/h/");
		// A foreign inbox is a local cross-user resource: enter the common gate
		// once so public policy/proofs and any delegated caveats are evaluated
		// exactly once.
		engine.requireLocalAccess(ctx, resource, ABILITY_HITL_REQUEST);
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
		// Answering is a GRANTING SURFACE (COG-17): an approved ask mints a
		// venue-signed UCAN under the responder's authority. The responder is the
		// inbox owner, so an agent reaching here would answer its OWN request and
		// grant itself authority as its owner — laundering the ucan:issue
		// restriction through the one surface that exists to put a human in the
		// loop. A capability scope is no defence: an agent trusted with a null
		// (unrestricted) scope is exactly the one that could do it. So the gate is
		// on identity, not on scope.
		if (ctx.isSubPrincipal()) {
			throw new AuthException("Agents cannot answer HITL requests: "
				+ ctx.getCallerDID() + " would be responding on behalf of "
				+ ctx.getUserDID() + ", and an approved ask issues a capability grant. "
				+ "Only the human who owns the inbox can answer.");
		}
		// The inbox is the user's, and any grant approved here is issued under the
		// user's own identity — agents hold no granting authority (COG-17).
		AString caller = ctx.getUserDID();
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
		User user = engine.getVenueState().users().get(caller);
		if (user == null) throw new AuthException("User is not registered at this venue: " + caller);
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

		// Transported self-sovereign tokens (COG-19): a token ask's answer is a
		// UCAN the responder signed with their OWN key. Verify provenance (their
		// signature, the requested audience, a live expiry) and carry it to the
		// requester — never mint, never re-sign. The signed token is a secret, so
		// it is REDACTED from the durable inbox record and delivered only on the
		// requester's job output.
		AString requester = RT.ensureString(record.get(Hitl.FROM));
		AMap<AString, ACell> transported = Maps.empty();
		AMap<AString, ACell> recordedAnswers = answers;
		long tnow = System.currentTimeMillis() / 1000;
		for (long i = 0; i < asks.count(); i++) {
			AMap<AString, ACell> ask = (AMap<AString, ACell>) asks.get(i);
			if (!Hitl.TOKEN_ASK.equals(RT.ensureString(ask.get(Hitl.TYPE)))) continue;
			AString askId = RT.ensureString(ask.get(Hitl.ID));
			AString jwt = RT.ensureString(answers.get(askId));
			if (jwt == null) continue; // unanswered optional token ask
			AString pinnedAud = RT.ensureString(RT.getIn(ask.get(Hitl.TOKEN), Hitl.AUDIENCE));
			AString expectedAud = (pinnedAud != null) ? pinnedAud : requester;
			verifyTransportedToken(jwt, user.getDID(), expectedAud, tnow);
			transported = transported.assoc(askId, jwt);
			recordedAnswers = recordedAnswers.assoc(askId,
				Strings.create("<signed token delivered to requester>"));
		}

		AMap<AString, ACell> response = Maps.of(Hitl.OUTCOME, Hitl.ANSWER, Hitl.ANSWERS, recordedAnswers);
		ACell comments = RT.getIn(input, Hitl.COMMENTS);
		if (comments instanceof AMap) response = response.assoc(Hitl.COMMENTS, comments);
		if (comment != null) response = response.assoc(Hitl.COMMENT, comment);
		if (approved.count() > 0) response = response.assoc(Hitl.GRANTS, approved);
		user.putHitlRequest(id, record.assoc(Hitl.STATUS, Hitl.ANSWERED).assoc(Hitl.RESPONSE, response));

		if (job != null && !job.isFinished()) {
			AMap<AString, ACell> output = response.assoc(Hitl.ID, id);
			if (token != null) output = output.assoc(Hitl.TOKEN, token);
			// The full signed tokens ride the requester's job output (never the
			// durable inbox record); the requester reads them by ask id.
			if (transported.count() > 0) output = output.assoc(Hitl.TOKENS, transported);
			job.completeWith(output);
		}
		return Maps.of(Hitl.ID, id, Hitl.STATUS, Hitl.ANSWERED);
	}

	/**
	 * Verifies a self-sovereign token the responder signed client-side (COG-19),
	 * before transporting it to the requester. This is provenance validation, not
	 * policy: the venue confirms the human actually signed this token for the
	 * requested audience with a live expiry — it never mints, re-signs, or judges
	 * the capabilities (the responder is authoritative over their own key).
	 *
	 * @param jwt         the signed UCAN JWT (the answer)
	 * @param responder   the answering user's DID — must be the token's {@code iss}
	 * @param expectedAud the audience the request asked for (pinned, or the
	 *                    requester by default) — must be the token's {@code aud}
	 * @param now         current time, unix seconds
	 * @throws AuthException / IllegalArgumentException on any failure
	 */
	private void verifyTransportedToken(AString jwt, AString responder, AString expectedAud, long now) {
		UcanJwtValidator.Validation validation =
			UcanJwtValidator.validate(jwt, now, engine.didVerifier());
		UCAN token = validation.token();
		if (token == null) {
			throw new IllegalArgumentException(
				"token answer is not a valid UCAN JWT: " + validation.reason());
		}
		// iss must be the answering human — not something an agent slipped in.
		AString issuer = token.getIssuer();
		if (issuer == null || !issuer.equals(responder)) {
			throw new AuthException("token must be signed by you (iss=" + responder
				+ "); got iss=" + issuer);
		}
		// Signature must verify against the issuer's OWN did:key (self-sovereign).
		AccountKey issuerKey = UCAN.fromDIDKey(issuer);
		if (issuerKey == null) {
			throw new AuthException("token issuer must be a did:key (self-sovereign signer): " + issuer);
		}
		if (JWT.verifyPublic(jwt, issuerKey) == null) {
			throw new AuthException("token signature does not verify against your key");
		}
		// A bounded lifetime is required, and must be live now.
		JWT parsed = JWT.parse(jwt);
		AMap<AString, ACell> claims = (parsed != null) ? parsed.getClaims() : null;
		if (claims == null || claims.get(UCAN.EXP) == null) {
			throw new IllegalArgumentException("token must carry an exp (bounded lifetime)");
		}
		if (!UCANValidator.checkTemporalBounds(token, now)) {
			throw new IllegalArgumentException("token is expired or not yet valid");
		}
		// Audience binding: the token is usable only by the principal it was
		// requested for, so leakage from a persisted job record cannot be replayed.
		AString aud = token.getAudience();
		if (aud == null || !aud.equals(expectedAud)) {
			throw new AuthException("token aud must be " + expectedAud
				+ " (the requester); got aud=" + aud);
		}
	}

	/** Issues the approved grants as a single token via the granting surface
	 *  (ucan:issue under the RESPONDER's authority — bare paths canonicalise to
	 *  the responder's namespace). Token exp = earliest grant exp, defaulted;
	 *  explicit null is unbounded and mints a genuinely non-expiring token
	 *  (exp: null, Convex #678) — permitted only when no venue ceiling is set. */
	private AString issueGrants(AString responder, AString requester, AVector<ACell> approved) {
		if (requester == null) throw new IllegalStateException("record has no requester identity");
		long now = System.currentTimeMillis() / 1000;
		Long maxLifetime = configuredMaxGrantLifetimeSecs();
		Long exp = null; // null sorts as infinity and means an explicitly unbounded token
		for (long i = 0; i < approved.count(); i++) {
			AMap<AString, ACell> grant = RT.castMap(approved.get(i));
			Long grantExp;
			if (grant != null && grant.containsKey(Hitl.EXP)) {
				ACell value = grant.get(Hitl.EXP);
				CVMLong g = RT.ensureLong(value);
				if (value == null) {
					grantExp = null;
				} else if (g != null) {
					grantExp = g.longValue();
					if (grantExp <= now) {
						throw new IllegalArgumentException("approved grant " + i + " has expired");
					}
				} else {
					throw new IllegalArgumentException("approved grant " + i
						+ " exp must be unix seconds or null");
				}
			} else {
				long defaultLifetime = (maxLifetime == null)
					? DEFAULT_GRANT_LIFETIME_SECS
					: Math.min(DEFAULT_GRANT_LIFETIME_SECS, maxLifetime);
				grantExp = now + defaultLifetime;
			}
			validateExpiryAgainstMaximum(grantExp, maxLifetime, now, "approved grant " + i);
			if (grantExp != null && (exp == null || grantExp < exp)) exp = grantExp;
		}
		try {
			ACell result = engine.jobs().invokeInternal("v/ops/ucan/issue",
				Maps.of(Strings.intern("aud"), requester,
					Strings.intern("att"), approved,
					Hitl.EXP, (exp == null) ? null : CVMLong.create(exp)),
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

	/** Validate policy before delivery, so the human never approves an expiry
	 *  that this venue would silently shorten or refuse only after consent. */
	@SuppressWarnings("unchecked")
	private void validateOfferedGrantExpiries(AVector<ACell> asks, long now) {
		Long maxLifetime = configuredMaxGrantLifetimeSecs();
		for (long i = 0; i < asks.count(); i++) {
			AMap<AString, ACell> ask = RT.castMap(asks.get(i));
			validateGrantListExpiries(ask.get(Hitl.GRANTS), maxLifetime, now,
				"asks[" + i + "]");
			ACell optionsCell = ask.get(Hitl.OPTIONS);
			if (!(optionsCell instanceof AVector)) continue;
			AVector<ACell> options = (AVector<ACell>) optionsCell;
			for (long j = 0; j < options.count(); j++) {
				AMap<AString, ACell> option = RT.castMap(options.get(j));
				validateGrantListExpiries(option.get(Hitl.GRANTS), maxLifetime, now,
					"asks[" + i + "].options[" + j + "]");
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void validateGrantListExpiries(ACell grantsCell, Long maxLifetime,
			long now, String where) {
		if (!(grantsCell instanceof AVector)) return;
		AVector<ACell> grants = (AVector<ACell>) grantsCell;
		for (long i = 0; i < grants.count(); i++) {
			AMap<AString, ACell> grant = RT.castMap(grants.get(i));
			if (grant == null || !grant.containsKey(Hitl.EXP)) continue;
			ACell value = grant.get(Hitl.EXP);
			CVMLong exp = RT.ensureLong(value);
			if (value != null && exp == null) {
				throw new IllegalArgumentException(where + ".grants[" + i
					+ "].exp must be unix seconds or null");
			}
			Long expiry = (exp == null) ? null : exp.longValue();
			if (expiry != null && expiry <= now) {
				throw new IllegalArgumentException(where + ".grants[" + i + "].exp is expired");
			}
			validateExpiryAgainstMaximum(expiry, maxLifetime, now,
				where + ".grants[" + i + "]");
		}
	}

	private static void validateExpiryAgainstMaximum(Long expiry, Long maxLifetime,
			long now, String where) {
		if (maxLifetime == null) return;
		if (expiry == null) {
			throw new IllegalArgumentException(where + " requests no expiry, exceeding this venue's "
				+ maxLifetime + "-second HITL grant ceiling");
		}
		if (expiry - now > maxLifetime) {
			throw new IllegalArgumentException(where + ".exp exceeds this venue's "
				+ maxLifetime + "-second HITL grant ceiling");
		}
	}

	/** Returns the configured positive ceiling, or null when absent/explicitly null. */
	private Long configuredMaxGrantLifetimeSecs() {
		AMap<AString, ACell> config = engine.adapterConfig(getName());
		if (!config.containsKey(CONFIG_MAX_GRANT_LIFETIME_SECS)) return null;
		ACell value = config.get(CONFIG_MAX_GRANT_LIFETIME_SECS);
		if (value == null) return null;
		CVMLong seconds = RT.ensureLong(value);
		if (seconds == null || seconds.longValue() <= 0) {
			throw new IllegalStateException("adapters.hitl.maxGrantLifetimeSecs must be "
				+ "a positive number of seconds or null");
		}
		return seconds.longValue();
	}

	// ========== hitl:list ==========

	@SuppressWarnings("unchecked")
	private ACell handleList(RequestContext ctx, ACell input) {
		AString caller = ctx.getUserDID();
		if (caller == null) throw new AuthException("Authentication required");
		AString filter = RT.ensureString(RT.getIn(input, Hitl.STATUS));
		User user = engine.getVenueState().users().get(caller);
		if (user == null) throw new AuthException("User is not registered at this venue: " + caller);
		AMap<AString, ACell> all = user.getHitlRequests();
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
		User user = engine.getVenueState().users().get(targetDID);
		if (user == null) return false;
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
			User user = engine.getVenueState().users().get(did);
			if (user == null) continue;
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
