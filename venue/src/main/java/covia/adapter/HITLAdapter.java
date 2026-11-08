package covia.adapter;

import java.util.concurrent.CompletableFuture;

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
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import covia.adapter.hitl.HitlValidation;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConfigurationException;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.grid.Attribution;
import covia.grid.Job;
import covia.venue.Engine;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.venue.RequestContext;
import covia.venue.AgentState;
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
	public static final String REQUEST_ALREADY_RESOLVED = "HITL request is no longer open";
	public static final String RESOLVED_WITHOUT_RESULT = "HITL request was resolved before restart but its full result was not saved";
	public static final String REQUEST_REJECTED = "HITL request rejected";
	public static final String REQUEST_EXPIRED = "HITL request expired";
	/** Refusal of {@code hitl:expire} from anyone but the venue, which schedules it. */
	public static final String EXPIRE_VENUE_ONLY = "Only the venue expires HITL requests, as their timeouts fall due";

	/**
	 * The operation a request's timeout schedules, fired as the venue at its
	 * {@code expires} time with input {@code {user, id}}.
	 */
	public static final AString EXPIRE_OP = Strings.intern("v/ops/hitl/expire");

	private static final Logger log = LoggerFactory.getLogger(HITLAdapter.class);

	/** Delivery ability for cross-user asks: {@code hitl/request} on {@code <target>/h/}. */
	public static final AString ABILITY_HITL_REQUEST = Abilities.HITL_REQUEST;

	/** The collection {@code hitl:list} reads: the caller's inbox. */
	private static final AString INBOX = Strings.intern("h/");

	/** Default lifetime for grants offered without an explicit {@code exp} (7 days). */
	static final long DEFAULT_GRANT_LIFETIME_SECS = 7 * 24 * 3600L;

	/** Optional adapter-config ceiling. Absent/null means no venue maximum. */
	static final AString CONFIG_MAX_GRANT_LIFETIME_SECS = Strings.intern("maxGrantLifetimeSecs");

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
		installSkill("agents/hitl", "/skills/hitl.json");
		installAsset("hitl/request", "/adapters/hitl/request.json");
		installAsset("hitl/respond", "/adapters/hitl/respond.json");
		installAsset("hitl/list",    "/adapters/hitl/list.json");
		installAsset("hitl/expire",  "/adapters/hitl/expire.json");
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
				case "expire": {
					final ACell in = input;
					return CompletableFuture.supplyAsync(() -> handleExpire(ctx, in), VIRTUAL_EXECUTOR);
				}
				case "request":
					return CompletableFuture.failedFuture(new NotSupportedException(
						"hitl:request is Job-carried — invoke it as an operation, not internally"));
				default:
					return CompletableFuture.failedFuture(
						new NotSupportedException("Unknown hitl operation: " + subOp));
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
		if (title == null) throw new InvalidInputException("title is required");
		AVector<ACell> asks = HitlValidation.validateAsks(RT.getIn(input, Hitl.ASKS));
		validateOfferedGrantExpiries(asks, engine.currentTimestamp().longValue() / 1000);
		Long timeoutSecs = parseTimeout(RT.getIn(input, Hitl.TIMEOUT));

		requireDeliverable(ctx, caller, target);

		// Build and deliver the record (venue-mediated: `caller` is the verified
		// caller, and h/ is not writable via covia:write), then park the Job.
		long nowMs = engine.currentTimestamp().longValue();
		AString id = Strings.create(job.getID().toHexString());
		AMap<AString, ACell> record = buildRecord(id, caller, ctx.getUserDID(), ctx.getAgentId(),
			ctx.getSessionId(), title,
			RT.ensureString(RT.getIn(input, Hitl.DESCRIPTION)), asks, nowMs, timeoutSecs);

		final AString targetDID = target;
		User targetUser = engine.getVenueState().users().user(targetDID);
		if (targetUser == null) {
			throw new NotFoundException("HITL target user is not registered at this venue: "
				+ targetDID, targetDID.toString());
		}
		targetUser.inbox().put(id, record);
		trackPending(job, ctx.getTaskId());
		job.setCancelHook(() -> markResolved(targetDID, id, Hitl.CANCELLED, null));
		job.setStatus(Status.INPUT_REQUIRED);
		CVMLong expires = RT.ensureLong(record.get(Hitl.EXPIRES));
		if (expires != null) scheduleExpiry(targetDID, id, expires.longValue());
		log.info("HITL request {} delivered to {} (from {})", id, targetDID, caller);
	}

	/**
	 * HITL's result is delivered through the session inbox; pending holds only
	 * its handle, and the task whose cycle asked when there is one, so that
	 * task waits for the answer rather than failing as idle. Recovery passes
	 * no task and keeps the entry already recorded.
	 */
	private void trackPending(Job job, Blob taskId) {
		AString agentId = Engine.agentIdIn(job.getOwner(), job.getCaller());
		if (agentId == null) return;
		User owner = engine.getVenueState().users().user(job.getOwner());
		AgentState agent = owner == null ? null : owner.agent(agentId);
		if (agent == null || agent.getRecord() == null) return;
		if (taskId != null) {
			agent.addPending(job.getID(), Maps.of(Fields.TASK_ID, Strings.create(taskId.toHexString())));
		} else if (!agent.getPending().containsKey(job.getID())) {
			agent.addPending(job.getID(), Maps.empty());
		}
		var cleanup = new java.util.function.Consumer<Job>() {
			@Override public void accept(Job updated) {
				if (!updated.isFinished()) return;
				agent.removePending(updated.getID());
				updated.unsubscribe(this);
			}
		};
		job.subscribe(cleanup);
		cleanup.accept(job); // closes completion before listener registration
	}

	@Override
	public void recoverJob(Job job) {
		trackPending(job, null); // restores legacy waits and arms cleanup if recovery fails
		super.recoverJob(job);
		if (job.isFinished()) return;
		AMap<AString, ACell> data = job.getData();
		AString target = RT.ensureString(RT.getIn(data, Fields.INPUT, Hitl.USER));
		if (target == null) target = job.getOwner();
		AString targetDID = target;
		AString id = Strings.create(job.getID().toHexString());
		User user = engine.getVenueState().users().user(target);
		AMap<AString, ACell> record = user == null ? null : user.inbox().get(id);
		if (record != null && !Hitl.OPEN.equals(record.get(Hitl.STATUS))) {
			// Older records lack a prepared result. Never reissue a grant or pretend
			// a redacted token is the original answer.
			if (Hitl.CANCELLED.equals(record.get(Hitl.STATUS))) job.cancel();
			else if (Hitl.EXPIRED.equals(record.get(Hitl.STATUS))) job.fail(REQUEST_EXPIRED);
			else if (Hitl.REJECTED.equals(record.get(Hitl.STATUS))) job.fail(rejection(RT.getIn(record, Hitl.RESPONSE, Hitl.COMMENT)));
			else job.fail(RESOLVED_WITHOUT_RESULT);
			return;
		}
		job.setCancelHook(() -> markResolved(targetDID, id, Hitl.CANCELLED, null));
	}

	private static String rejection(ACell comment) {
		return REQUEST_REJECTED + (comment == null ? "" : ": " + comment);
	}

	/** An ask within the caller's own user family is always permitted — a user
	 *  asking themselves, or an agent asking the owner whose namespace it runs
	 *  in (the documented default, which needs no delegation). Delivering into
	 *  ANOTHER user's inbox requires hitl/request on {@code <target>/h/} —
	 *  checked against the caller's grant scope AND (cross-user) their presented
	 *  proofs. Proximity relaxes friction here, not authorisation: a foreign
	 *  target still needs a delegation. The family is that of the authority
	 *  the request acts with, which for an agent's harness may be its
	 *  controller's. */
	private void requireDeliverable(RequestContext ctx, AString caller, AString target) {
		if (ctx.authorityContext().relationTo(target).isSameUser()) return;
		AString resource = Strings.create(target + "/h/");
		// A foreign inbox is a local cross-user resource: enter the common gate
		// once so public policy/proofs and any delegated caveats are evaluated
		// exactly once.
		engine.requireLocalAccess(ctx, resource, ABILITY_HITL_REQUEST);
	}

	private static AMap<AString, ACell> buildRecord(AString id, AString caller,
			AString owner, AString agentId, Blob sessionId, AString title,
			AString description, AVector<ACell> asks, long nowMs, Long timeoutSecs) {
		AMap<AString, ACell> record = Attribution.attribute(Maps.of(
			Hitl.ID, id,
			Hitl.TITLE, title,
			Hitl.ASKS, asks,
			Hitl.STATUS, Hitl.OPEN,
			Hitl.CREATED, CVMLong.create(nowMs)), caller, owner);
		if (description != null) record = record.assoc(Hitl.DESCRIPTION, description);
		if (agentId != null) {
			record = record.assoc(Hitl.AGENT, agentId);
			if (sessionId != null) {
				record = record.assoc(Fields.SESSION_ID, Strings.create(sessionId.toHexString()));
			}
		}
		if (timeoutSecs != null) record = record.assoc(Hitl.EXPIRES, CVMLong.create(nowMs + timeoutSecs * 1000));
		return record;
	}

	private static Long parseTimeout(ACell timeoutCell) {
		if (timeoutCell == null) return null;
		CVMLong t = RT.ensureLong(timeoutCell);
		if (t == null || t.longValue() <= 0) {
			throw new InvalidInputException("timeout must be a positive number of seconds");
		}
		return t.longValue();
	}

	// ========== hitl:respond — resolve record and job ==========

	@SuppressWarnings("unchecked")
	private ACell handleRespond(RequestContext ctx, ACell input) {
		// Answering is a GRANTING SURFACE (COG-17): an approved ask mints a
		// venue-signed UCAN under the responder's authority. The responder is the
		// inbox owner, so an agent reaching here would answer its OWN request and
		// grant itself authority as its owner, with the owner's whole authority
		// rather than the agent's own caps, laundered through the one surface
		// that exists to put a human in the loop. A capability scope is no
		// defence: the grant is issued under the owner's context, whatever the
		// agent's scope. So the gate is on identity, not on scope.
		if (ctx.isSubPrincipal()) {
			throw new AuthException("Agents cannot answer HITL requests: "
				+ ctx.getCallerDID() + " would be responding on behalf of "
				+ ctx.getUserDID() + ", and an approved ask issues a capability grant. "
				+ "Only the human who owns the inbox can answer.");
		}
		// The inbox is the user's, and any grant approved here is issued under the
		// user's own identity.
		AString caller = ctx.getUserDID();
		if (caller == null) throw new AuthException("Authentication required");
		AString id = RT.ensureString(RT.getIn(input, Hitl.ID));
		if (id == null) throw new InvalidInputException("id is required");
		// Records key on bare hex, but REST renders job ids 0x-prefixed —
		// accept both, so a pasted job id just works.
		String idStr = id.toString();
		if (idStr.startsWith("0x") || idStr.startsWith("0X")) {
			id = Strings.create(idStr.substring(2));
		}

		// Structural authorisation: respond reads the CALLER's own inbox only.
		User user = engine.getVenueState().users().user(caller);
		if (user == null) throw new AuthException("User is not registered at this venue: " + caller);
		AMap<AString, ACell> record = user.inbox().get(id);
		if (record == null) {
			throw new NotFoundException("No HITL request " + id + " in your inbox", id.toString());
		}
		if (!Hitl.OPEN.equals(record.get(Hitl.STATUS))) {
			throw new NotSupportedException("HITL request " + id + " is not open (status: "
				+ record.get(Hitl.STATUS) + ")");
		}
		// Lazy expiry: an expiry event not yet fired (the venue has not been
		// updated past its time) must not let an expired request be answered.
		CVMLong expires = RT.ensureLong(record.get(Hitl.EXPIRES));
		if (expires != null && engine.currentTimestamp().longValue() > expires.longValue()) {
			expireRequest(caller, id, Blob.parse(id.toString()));
			throw new NotSupportedException("HITL request " + id + " has expired");
		}

		AString outcome = RT.ensureString(RT.getIn(input, Hitl.OUTCOME));
		AString comment = RT.ensureString(RT.getIn(input, Hitl.COMMENT));
		Job job = engine.jobs().getJob(Blob.parse(id.toString()));
		if (Hitl.REJECT.equals(outcome)) {
			return resolveReject(user, id, comment, job);
		}
		if (Hitl.ANSWER.equals(outcome)) {
			return resolveAnswer(user, id, record, input, comment, job);
		}
		throw new InvalidInputException("outcome must be 'answer' or 'reject'");
	}

	private ACell resolveReject(User user, AString id, AString comment, Job job) {
		AMap<AString, ACell> response = Maps.of(Hitl.OUTCOME, Hitl.REJECT);
		if (comment != null) response = response.assoc(Hitl.COMMENT, comment);
		if (!resolveRequest(user.getDID(), id, Hitl.REJECTED, response, user.getDID(),
				response.assoc(Hitl.ID, id).assoc(Hitl.STATUS, Hitl.REJECTED), job)) throw new ConflictException(REQUEST_ALREADY_RESOLVED);
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
			? issueGrants(user.getDID(), Attribution.caller(record), approved)
			: null;

		// Transported self-sovereign tokens (COG-19): a token ask's answer is a
		// UCAN the responder signed with their OWN key. Verify provenance (their
		// signature, the requested audience, a live expiry) and carry it to the
		// requester — never mint, never re-sign. The signed token is a secret, so
		// it is REDACTED from the durable inbox record and delivered only on the
		// requester's job output.
		AString requester = Attribution.caller(record);
		AMap<AString, ACell> transported = Maps.empty();
		AMap<AString, ACell> recordedAnswers = answers;
		long tnow = engine.currentTimestamp().longValue() / 1000;
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

		AMap<AString, ACell> output = response.assoc(Hitl.ID, id);
		if (token != null) output = output.assoc(Hitl.TOKEN, token);
		// The full signed tokens ride the requester's job output (never the
		// responder's inbox record); an agent requester receives the same value
		// in its own durable session.
		if (transported.count() > 0) output = output.assoc(Hitl.TOKENS, transported);
		AMap<AString, ACell> result = output;
		if (!resolveRequest(user.getDID(), id, Hitl.ANSWERED, response, user.getDID(), result, job)) throw new ConflictException(REQUEST_ALREADY_RESOLVED);
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
	 * @throws AuthException / InvalidInputException on any failure
	 */
	private void verifyTransportedToken(AString jwt, AString responder, AString expectedAud, long now) {
		UcanJwtValidator.Validation validation =
			UcanJwtValidator.validate(jwt, now, engine.didVerifier());
		UCAN token = validation.token();
		if (token == null) {
			throw new InvalidInputException(
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
			throw new InvalidInputException("token must carry an exp (bounded lifetime)");
		}
		if (!UCANValidator.checkTemporalBounds(token, now)) {
			throw new InvalidInputException("token is expired or not yet valid");
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
		long now = engine.currentTimestamp().longValue() / 1000;
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
						throw new InvalidInputException("approved grant " + i + " has expired");
					}
				} else {
					throw new InvalidInputException("approved grant " + i
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
		ACell result = await(engine.jobs().invokeInternal("v/ops/ucan/issue",
			Maps.of(Strings.intern("aud"), requester,
				Strings.intern("att"), approved,
				Hitl.EXP, (exp == null) ? null : CVMLong.create(exp)),
			engine.context(responder)), 30_000, "ucan:issue");
		AString token = RT.ensureString(RT.getIn(result, Hitl.TOKEN));
		if (token == null) throw new IllegalStateException("ucan:issue returned no token");
		return token;
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
				throw new InvalidInputException(where + ".grants[" + i
					+ "].exp must be unix seconds or null");
			}
			Long expiry = (exp == null) ? null : exp.longValue();
			if (expiry != null && expiry <= now) {
				throw new InvalidInputException(where + ".grants[" + i + "].exp is expired");
			}
			validateExpiryAgainstMaximum(expiry, maxLifetime, now,
				where + ".grants[" + i + "]");
		}
	}

	private static void validateExpiryAgainstMaximum(Long expiry, Long maxLifetime,
			long now, String where) {
		if (maxLifetime == null) return;
		if (expiry == null) {
			throw new InvalidInputException(where + " requests no expiry, exceeding this venue's "
				+ maxLifetime + "-second HITL grant ceiling");
		}
		if (expiry - now > maxLifetime) {
			throw new InvalidInputException(where + ".exp exceeds this venue's "
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
			throw new ConfigurationException("adapters.hitl.maxGrantLifetimeSecs must be "
				+ "a positive number of seconds or null");
		}
		return seconds.longValue();
	}

	// ========== hitl:list ==========

	/** Listing the inbox reads it, so the caller needs {@code crud/read} on {@code h/}. */
	@SuppressWarnings("unchecked")
	private ACell handleList(RequestContext ctx, ACell input) {
		AString caller = ctx.getUserDID();
		if (caller == null) throw new AuthException("Authentication required");
		engine.requireResourceAccess(ctx, INBOX, Capability.CRUD_READ);
		AString filter = RT.ensureString(RT.getIn(input, Hitl.STATUS));
		User user = engine.getVenueState().users().user(caller);
		if (user == null) throw new AuthException("User is not registered at this venue: " + caller);
		AMap<AString, AMap<AString, ACell>> all = user.inbox().getAll();
		AVector<ACell> items = Vectors.empty();
		for (long i = 0; i < all.count(); i++) {
			ACell v = all.entryAt(i).getValue();
			if (!(v instanceof AMap)) continue;
			AMap<AString, ACell> rec = (AMap<AString, ACell>) v;
			if (filter != null && !filter.equals(rec.get(Hitl.STATUS))) continue;
			AMap<AString, ACell> summary = Maps.of(
				Hitl.ID, rec.get(Hitl.ID),
				Hitl.CALLER, rec.get(Hitl.CALLER),
				Hitl.TITLE, rec.get(Hitl.TITLE),
				Hitl.STATUS, rec.get(Hitl.STATUS),
				Hitl.CREATED, rec.get(Hitl.CREATED));
			if (rec.get(Hitl.EXPIRES) != null) summary = summary.assoc(Hitl.EXPIRES, rec.get(Hitl.EXPIRES));
			items = items.conj(summary);
		}
		return Maps.of(Hitl.ITEMS, items, Hitl.COUNT, CVMLong.create(items.count()));
	}

	// ========== Expiry ==========

	/**
	 * Schedules the request's expiry at {@code expires}: a one-shot event the
	 * venue fires as itself, invoking {@link #EXPIRE_OP}. It lives in the
	 * venue's schedule, so it survives a restart (COG-16).
	 */
	private void scheduleExpiry(AString targetDID, AString id, long expires) {
		engine.gridScheduler().schedule(EXPIRE_OP, expiryInput(targetDID, id), engine.venueContext(),
			expires, null, Boolean.FALSE, Strings.create("Expires HITL request " + id));
	}

	/** The input of {@link #EXPIRE_OP} for request {@code id} in {@code targetDID}'s inbox. */
	public static AMap<AString, ACell> expiryInput(AString targetDID, AString id) {
		return Maps.of(Hitl.USER, targetDID, Hitl.ID, id);
	}

	// ========== hitl:expire ==========

	/**
	 * Expires an open request whose timeout has fallen due. Only the venue
	 * invokes it, from the event its request scheduled. Idempotent: a request
	 * already resolved, or gone, is left as it is. Returns the request's
	 * {@code id} and its {@code status} now, when it still exists.
	 */
	private ACell handleExpire(RequestContext ctx, ACell input) {
		if (!engine.isDirectVenue(ctx)) throw new AuthException(EXPIRE_VENUE_ONLY);
		AString target = RT.ensureString(RT.getIn(input, Hitl.USER));
		AString id = RT.ensureString(RT.getIn(input, Hitl.ID));
		if (target == null || id == null) throw new InvalidInputException("user and id are required");
		Blob jobId = Blob.parse(id.toString());
		if (jobId == null) throw new InvalidInputException("id is not a HITL request id: " + id);
		expireRequest(target, id, jobId);
		User user = engine.getVenueState().users().user(target);
		AMap<AString, ACell> record = (user == null) ? null : user.inbox().get(id);
		AMap<AString, ACell> result = Maps.of(Hitl.ID, id);
		return (record == null) ? result : result.assoc(Hitl.STATUS, record.get(Hitl.STATUS));
	}

	/** Expires an open request: record → expired, job → FAILED. Terminal-state
	 *  stickiness makes the race against a response commit exactly one winner. */
	private void expireRequest(AString targetDID, AString id, Blob jobId) {
		if (resolveRequest(targetDID, id, Hitl.EXPIRED, null, engine.getDIDString(), Maps.of(
				Hitl.ID, id, Hitl.STATUS, Hitl.EXPIRED, Fields.ERROR, REQUEST_EXPIRED),
				engine.jobs().getJob(jobId))) log.info("HITL request {} expired", id);
	}

	private record SessionWake(AString owner, AString agent) { }

	/**
	 * A resolved request: the notification its job's update runs after commit,
	 * or null; the asking agent to wake, or null; and whether the asking
	 * agent's session could not take the result.
	 */
	private record Resolution(Runnable notification, SessionWake wake, boolean unavailable) { }

	/** Resolve once and deliver to the asking task or session in the same native transaction.
	 * Tokens have already been prepared; no signing, I/O or live Job mutation is
	 * allowed inside the retryable draft callback. */
	private boolean resolveRequest(AString targetDID, AString id, AString status,
			AMap<AString, ACell> response, AString responder, ACell result, Job job) {
		AgentAdapter agents = engine.getAdapter("agent") instanceof AgentAdapter a ? a : null;
		AString requester = job == null ? null : job.getOwner();
		AString requestingAgent = job == null ? null : Engine.agentIdIn(requester, job.getCaller());
		User requesterAccount = requester == null ? null : engine.getVenueState().users().user(requester);
		// Null when the request was not open to resolve
		Resolution resolution = engine.getVenueState().transact(venue -> {
			var draft = venue.users();
			Runnable notification = null;
			Blob waitingTask = null;
			User target = draft.user(targetDID);
			AMap<AString, ACell> record = target == null ? null : target.inbox().get(id);
			if (record == null || !Hitl.OPEN.equals(record.get(Hitl.STATUS))) return null;
			AMap<AString, ACell> updated = record.assoc(Hitl.STATUS, status);
			if (response != null) updated = updated.assoc(Hitl.RESPONSE, response);
			target.inbox().put(id, updated);
			if (job != null) {
				Runnable changed = ((covia.venue.VenueJob) job).updateIn(venue, data -> {
					if (Hitl.ANSWERED.equals(status)) return data.assoc(Fields.STATUS, Status.COMPLETE).assoc(Fields.OUTPUT, result);
					String error = Hitl.REJECTED.equals(status) ? rejection(RT.getIn(result, Hitl.COMMENT)) : REQUEST_EXPIRED;
					return data.assoc(Fields.STATUS, Status.FAILED).assoc(Fields.ERROR, Strings.create(error)).dissoc(Fields.ERROR_CODE);
				});
				if (changed == null) throw new ConflictException(REQUEST_ALREADY_RESOLVED);
				notification = changed;
				// Terminal jobs are skipped at restart: no live cleanup listener will
				// survive to remove this wait, so settle it with the result itself.
				User owner = draft.user(requester);
				AgentState askingAgent = owner == null || requestingAgent == null ? null : owner.agent(requestingAgent);
				if (askingAgent != null && askingAgent.getRecord() != null) {
					AString taskHex = RT.ensureString(RT.getIn(askingAgent.getPending().get(job.getID()), Fields.TASK_ID));
					if (taskHex != null) waitingTask = Blob.fromHex(taskHex.toString());
					askingAgent.removePending(job.getID());
				}
			}
			if (result == null) return new Resolution(notification, null, false);
			AString agentId = RT.ensureString(record.get(Hitl.AGENT));
			AString sidHex = RT.ensureString(record.get(Fields.SESSION_ID));
			if (agentId == null || sidHex == null) return new Resolution(notification, null, false); // Human/API requester.
			Blob sid = Blob.fromHex(sidHex.toString());
			AString owner = Attribution.owner(record);
			// The result carries the request ID. This is an independent message,
			// not a chat waiter tied to the now-terminal HITL Job; it goes to the
			// task whose cycle asked, when there is one, else to the session.
			if (owner != null && sid != null && agents != null
					&& agents.deliverToWaiter(draft, owner, agentId, sid, waitingTask, responder,
						(job != null) ? Strings.create(job.getID().toHexString()) : null, result)) {
				return new Resolution(notification, new SessionWake(owner, agentId), false);
			}
			return new Resolution(notification, null, true);
		});
		if (resolution == null) return false;
		SessionWake destination = resolution.wake();
		// Notify live waiters even if the durability barrier fails: the in-memory
		// transaction is already committed. The responder still receives that error.
		try { engine.flush(); }
		finally { if (resolution.notification() != null) resolution.notification().run(); }
		if (destination != null && requesterAccount != null) synchronized (engine) {
			requesterAccount.get(); // Fence wake against account deletion/recreation.
			agents.wakeAgent(destination.owner(), destination.agent(), true);
		}
		if (resolution.unavailable()) log.warn("HITL request {} resolved but its asking agent session is unavailable", id);
		return true;
	}

	/** Cancellation uses the same terminal-state arbitration without a session reply. */
	private boolean markResolved(AString targetDID, AString id, AString status, AMap<AString, ACell> response) {
		return resolveRequest(targetDID, id, status, response, null, null, null);
	}
}
