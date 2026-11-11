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
import covia.grid.HexId;
import covia.grid.Job;
import covia.venue.Agent;
import covia.venue.Engine;
import covia.grid.Status;
import covia.grid.hitl.Hitl;
import covia.venue.RequestContext;
import covia.venue.HitlRequest;
import covia.venue.UcanJwtValidator;
import covia.venue.User;
import covia.venue.VenueJob;
import covia.venue.VenueState;

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
	/** A request's job error when the person asked declines it; {@code %s} is their DID. */
	public static final String REQUEST_REJECTED = "The person you asked (%s) declined this request, so no answer will come.";
	/** Follows {@link #REQUEST_REJECTED} with the comment the person gave. */
	public static final String THEIR_COMMENT = " Their comment: ";
	/** A request's job error when nobody answers before it expires. */
	public static final String REQUEST_EXPIRED = "No one answered this request before it expired, so no answer will come.";
	/** Refusal of a request to a user the venue does not host. */
	public static final String TARGET_NOT_REGISTERED = "HITL target user is not registered at this venue: ";
	/** A request's job error when the person asked loses their account; {@code %s} is their DID. */
	public static final String TARGET_DELETED = "The person you asked (%s) no longer has an account on this venue, so no answer will come.";
	/** A request's job ended before the request could wait for an answer. */
	public static final String JOB_ENDED = "The job carrying this HITL request has already ended: ";
	/** An inbox already holds a request under the id of a job opening one. */
	public static final String REQUEST_EXISTS = "A HITL request is already open under the id of job ";

	/** Refusal of an approved grant when the venue has no granting surface. */
	public static final String GRANTS_UNAVAILABLE = "This venue cannot issue capability grants: its ucan adapter is not running";

	/** Why a request may not answer an ask: it holds less than the inbox owner's whole authority. */
	public static final String ANSWER_NEEDS_WHOLE_AUTHORITY = "Answering a HITL request needs all of the inbox owner's authority, which this request (through an MCP token) does not hold";

	/** In a request job's adapter data: the DID of the user it was sent to. */
	static final AString TARGET = Hitl.USER;

	/** In a request job's adapter data: the handle of the scheduled event that expires it. */
	static final AString EXPIRY_EVENT = Strings.intern("expiryEvent");

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

		// Open the request (venue-mediated: `caller` is the verified caller, and
		// h/ is not writable via covia:write) and park its Job in one transaction,
		// so the request is open exactly when its Job waits for it.
		long nowMs = engine.currentTimestamp().longValue();
		AString id = Strings.create(job.getID().toHexString());
		AMap<AString, ACell> record = HitlRequest.record(id, caller, ctx.getUserDID(), ctx.getAgentId(),
			ctx.getSessionId(), title, RT.ensureString(RT.getIn(input, Hitl.DESCRIPTION)), asks, nowMs,
			(timeoutSecs == null) ? null : nowMs + timeoutSecs * 1000);

		final AString targetDID = target;
		Blob taskId = ctx.getTaskId();
		CVMLong expires = RT.ensureLong(record.get(Hitl.EXPIRES));
		Runnable parked = engine.getVenueState().transact(venue -> {
			User targetUser = venue.users().user(targetDID);
			if (targetUser == null) {
				throw new NotFoundException(TARGET_NOT_REGISTERED + targetDID, targetDID.toString());
			}
			if (!targetUser.inbox().open(record)) throw new IllegalStateException(REQUEST_EXISTS + id);
			addPending(venue, job, taskId);
			// The session goes with the request if the target's account does
			AString askingSession = RT.ensureString(record.get(Fields.SESSION_ID));
			Blob expiry = (expires == null) ? null : scheduleExpiry(venue, targetDID, id, expires);
			venue.jobIndex().updateAdapterData(job.getID(), data -> {
				AMap<AString, ACell> kept = data.assoc(TARGET, targetDID);
				if (askingSession != null) kept = kept.assoc(Fields.SESSION_ID, askingSession);
				return (expiry == null) ? kept : kept.assoc(EXPIRY_EVENT, expiry);
			});
			Runnable changed = ((VenueJob) job).updateIn(venue, data -> data.assoc(Fields.STATUS, Status.INPUT_REQUIRED));
			if (changed == null) throw new ConflictException(JOB_ENDED + id);
			return changed;
		});
		parked.run();
		if (expires != null) engine.gridScheduler().scheduleChanged();
		log.info("HITL request {} delivered to {} (from {})", id, targetDID, caller);
	}

	/**
	 * HITL's result is delivered through the session inbox; pending holds only
	 * its handle, and the task whose cycle asked when there is one, so that
	 * task waits for the answer rather than failing as idle. Recovery passes
	 * no task and keeps the entry already recorded. A pure edit of the draft.
	 */
	private static void addPending(VenueState venue, Job job, Blob taskId) {
		AString agentId = Engine.agentIdIn(job.getOwner(), job.getCaller());
		if (agentId == null) return;
		User owner = venue.users().user(job.getOwner());
		Agent agent = owner == null ? null : owner.agent(agentId);
		if (agent == null || agent.getRecord() == null) return;
		if (taskId != null) {
			agent.addPending(job.getID(), Maps.of(Fields.TASK_ID, Strings.create(taskId.toHexString())));
		} else if (!agent.getPending().containsKey(job.getID())) {
			agent.addPending(job.getID(), Maps.empty());
		}
	}

	/** Takes the asking agent's pending entry away with the job that ended. A pure edit of the draft. */
	private static void removePending(VenueState venue, Job job) {
		AString agentId = Engine.agentIdIn(job.getOwner(), job.getCaller());
		if (agentId == null) return;
		User owner = venue.users().user(job.getOwner());
		Agent agent = owner == null ? null : owner.agent(agentId);
		if (agent != null && agent.getRecord() != null) agent.removePending(job.getID());
	}

	/**
	 * The user a request job asked. Requests opened before the adapter kept its
	 * target name it only in the job's input.
	 */
	private static AString askedBy(Job job, AMap<AString, ACell> adapterData) {
		AString kept = (adapterData == null) ? null : RT.ensureString(adapterData.get(TARGET));
		if (kept != null) return kept;
		AString target = RT.ensureString(RT.getIn(job.getData(), Fields.INPUT, Hitl.USER));
		return (target != null) ? target : job.getOwner();
	}

	/** Cancelling a request job resolves its request as cancelled and ends the asking agent's wait. */
	@Override
	public void cancelIn(VenueState draft, Job job, AMap<AString, ACell> adapterData) {
		User user = draft.users().user(askedBy(job, adapterData));
		if (user != null) user.inbox().request(Strings.create(job.getID().toHexString())).resolve(Hitl.CANCELLED, null);
		removePending(draft, job);
		cancelExpiry(draft, adapterData);
	}

	@Override
	public void recoverJob(Job job) {
		super.recoverJob(job);
		if (job.isFinished()) {
			engine.getVenueState().execute(venue -> removePending(venue, job));
			return;
		}
		// Restores waits recorded before they were opened with the request
		engine.getVenueState().execute(venue -> addPending(venue, job, null));
		AMap<AString, ACell> kept = engine.jobs().adapterData(job);
		AString target = askedBy(job, kept);
		AString id = Strings.create(job.getID().toHexString());
		User user = engine.getVenueState().users().user(target);
		if (user == null) {
			// The account went before this job's handle was restored. Launch wakes
			// agents with work once every job is back, so this one wakes nothing.
			failForDeletedTarget(target, job, kept, null);
			return;
		}
		HitlRequest request = user.inbox().request(id);
		AString status = request.status();
		if (status == null) return;
		if (Hitl.OPEN.equals(status)) {
			ensureExpiry(job, target, id, RT.ensureLong(request.getRecord().get(Hitl.EXPIRES)));
			return;
		}
		// Older records lack a prepared result. Never reissue a grant or pretend
		// a redacted token is the original answer.
		if (Hitl.CANCELLED.equals(status)) job.cancel();
		else if (Hitl.EXPIRED.equals(status)) failWithRequest(job, REQUEST_EXPIRED);
		else if (Hitl.REJECTED.equals(status)) failWithRequest(job, rejection(target, RT.getIn(request.getRecord(), Hitl.RESPONSE, Hitl.COMMENT)));
		else failWithRequest(job, RESOLVED_WITHOUT_RESULT);
	}

	/**
	 * The person a request asked can no longer answer it, so its job fails and
	 * the agent that asked is told. Idempotent, as the deletion's retry requires.
	 */
	@Override
	public void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
		for (var entry : engine.getVenueState().jobIndex().all().entrySet()) {
			if (!(engine.jobs().getJob(entry.getKey()) instanceof VenueJob job) || job.adapter() != this) continue;
			if (!Status.INPUT_REQUIRED.equals(job.getStatus())) continue;
			AMap<AString, ACell> kept = engine.jobs().adapterData(job);
			if (did.equals(askedBy(job, kept))) {
				failForDeletedTarget(did, job, kept, engine.getVenueState().users().user(job.getOwner()));
			}
		}
	}

	/**
	 * Fails a request whose target has no account, ends the asking agent's wait
	 * and tells it why. The account took the request's record with it, so
	 * nothing else would end the wait.
	 *
	 * @param ownerAccount The job owner's account, to wake the agent that asked, or null to wake nothing
	 */
	private void failForDeletedTarget(AString target, Job job, AMap<AString, ACell> kept, User ownerAccount) {
		String error = String.format(TARGET_DELETED, target);
		AString id = Strings.create(job.getID().toHexString());
		AString owner = job.getOwner();
		AString agentId = Engine.agentIdIn(owner, job.getCaller());
		AString session = (kept == null) ? null : RT.ensureString(kept.get(Fields.SESSION_ID));
		ACell result = Maps.of(Hitl.ID, id, Hitl.STATUS, Hitl.CANCELLED, Fields.ERROR, error);
		// Null when the job finished first
		Resolution resolution = engine.getVenueState().transact(venue ->
			settle(venue, job, data -> failed(data, error), owner, agentId, session, engine.getDIDString(), result));
		if (resolution != null) afterResolution(resolution, id, ownerAccount);
	}

	private static AMap<AString, ACell> failed(AMap<AString, ACell> data, String error) {
		return data.assoc(Fields.STATUS, Status.FAILED).assoc(Fields.ERROR, Strings.create(error)).dissoc(Fields.ERROR_CODE);
	}

	/** Fails a request job and ends the asking agent's wait in one transaction. */
	private void failWithRequest(Job job, String message) {
		Runnable failed = engine.getVenueState().transact(venue -> {
			Runnable changed = ((VenueJob) job).failIn(venue, message);
			if (changed != null) removePending(venue, job);
			return changed;
		});
		if (failed != null) failed.run();
	}

	/** The job error for a request {@code responder} declined, with their comment if any. */
	static String rejection(AString responder, ACell comment) {
		return String.format(REQUEST_REJECTED, responder) + (comment == null ? "" : THEIR_COMMENT + comment);
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
		// For the same reason, only a request with all of that user's authority
		// answers: not one an MCP token narrows to less.
		if (!engine.holdsAuthorityOf(ctx, caller)) throw new AuthException(ANSWER_NEEDS_WHOLE_AUTHORITY);
		AString id = RT.ensureString(RT.getIn(input, Hitl.ID));
		if (id == null) throw new InvalidInputException("id is required");
		// Records key on bare hex, but REST renders job ids 0x-prefixed —
		// accept both, so a pasted job id just works.
		Blob given = HexId.parse(id);
		if (given != null) id = Strings.create(given.toHexString());

		// Structural authorisation: respond reads the CALLER's own inbox only.
		User user = engine.getVenueState().users().user(caller);
		if (user == null) throw new AuthException("User is not registered at this venue: " + caller);
		HitlRequest request = user.inbox().request(id);
		AMap<AString, ACell> record = request.getRecord();
		if (record == null) {
			throw new NotFoundException("No HITL request " + id + " in your inbox", id.toString());
		}
		if (!request.isOpen()) {
			throw new NotSupportedException("HITL request " + id + " is not open (status: "
				+ request.status() + ")");
		}
		// Lazy expiry: an expiry event not yet fired (the venue has not been
		// updated past its time) must not let an expired request be answered.
		if (request.isExpired(engine.currentTimestamp().longValue())) {
			expireRequest(caller, id, Blob.fromHex(id.toString()));
			throw new NotSupportedException("HITL request " + id + " has expired");
		}

		AString outcome = RT.ensureString(RT.getIn(input, Hitl.OUTCOME));
		AString comment = RT.ensureString(RT.getIn(input, Hitl.COMMENT));
		Job job = engine.jobs().getJob(Blob.fromHex(id.toString()));
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
				response.assoc(Hitl.ID, id).assoc(Hitl.STATUS, Hitl.REJECTED), job, null)) throw new ConflictException(REQUEST_ALREADY_RESOLVED);
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
		UCANAdapter.Grant grant = (approved.count() > 0)
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
		if (grant != null) output = output.assoc(Hitl.TOKEN, grant.token());
		// The full signed tokens ride the requester's job output (never the
		// responder's inbox record); an agent requester receives the same value
		// in its own durable session.
		if (transported.count() > 0) output = output.assoc(Hitl.TOKENS, transported);
		AMap<AString, ACell> result = output;
		if (!resolveRequest(user.getDID(), id, Hitl.ANSWERED, response, user.getDID(), result, job, grant)) throw new ConflictException(REQUEST_ALREADY_RESOLVED);
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

	/** Mints the approved grants as a single token, as the granting surface
	 *  does (ucan:issue under the RESPONDER's authority — bare paths canonicalise
	 *  to the responder's namespace), but unrecorded: the answer records it once
	 *  it commits ({@link UCANAdapter#audit}). Token exp = earliest grant exp,
	 *  defaulted; explicit null is unbounded and mints a genuinely non-expiring
	 *  token (exp: null, Convex #678) — permitted only when no venue ceiling is set. */
	private UCANAdapter.Grant issueGrants(AString responder, AString requester, AVector<ACell> approved) {
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
		if (!(engine.getAdapter("ucan") instanceof UCANAdapter ucan)) throw new ConfigurationException(GRANTS_UNAVAILABLE);
		RequestContext issuer = engine.context(responder);
		return ucan.mint(issuer, Maps.of(UCAN.AUD, requester, UCAN.ATT, approved,
			UCAN.EXP, (exp == null) ? null : CVMLong.create(exp)));
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
	 * Adds the request's expiry to the venue's schedule in {@code venue}'s
	 * transaction: a one-shot event the venue fires as itself at
	 * {@code expires}, invoking {@link #EXPIRE_OP}. The request's {@code expires}
	 * is the durable fact and this event only its alarm. Its handle stays in
	 * the job's adapter data, so the transaction that ends the job removes it.
	 *
	 * @return The event's handle
	 */
	private Blob scheduleExpiry(VenueState venue, AString targetDID, AString id, CVMLong expires) {
		AString did = engine.getDIDString();
		return venue.schedule().schedule(EXPIRE_OP, expiryInput(targetDID, id), did, did, null, null,
			expires.longValue(), null, Boolean.FALSE, Strings.create("Expires HITL request " + id),
			engine.currentTimestamp().longValue());
	}

	/** The handle of a request job's expiry event in its adapter data, or null when it has none. */
	private static Blob expiryEvent(AMap<AString, ACell> adapterData) {
		return (adapterData != null && adapterData.get(EXPIRY_EVENT) instanceof Blob handle) ? handle : null;
	}

	/** Removes a request job's expiry event, if it has one still, in {@code venue}'s transaction. */
	private static void cancelExpiry(VenueState venue, AMap<AString, ACell> adapterData) {
		Blob handle = expiryEvent(adapterData);
		if (handle != null) venue.schedule().cancel(handle);
	}

	/**
	 * Restores the alarm of an open request a recovered job waits on, when its
	 * expiry event is gone: fired without resolving it because the venue
	 * stopped, or never made because the request came from an older venue
	 * (COG-16). An overdue request expires at the venue's first update.
	 */
	private void ensureExpiry(Job job, AString target, AString id, CVMLong expires) {
		if (expires == null) return;
		boolean restored = engine.getVenueState().transact(venue -> {
			Blob held = expiryEvent(venue.jobIndex().adapterData(job.getID()));
			if (held != null && venue.schedule().find(held) != null) return false;
			Blob expiry = scheduleExpiry(venue, target, id, expires);
			venue.jobIndex().updateAdapterData(job.getID(), data -> data.assoc(EXPIRY_EVENT, expiry));
			return true;
		});
		if (restored) engine.gridScheduler().scheduleChanged();
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
		Blob jobId = Blob.fromHex(id.toString());
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
				engine.jobs().getJob(jobId), null)) log.info("HITL request {} expired", id);
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
	 * allowed inside the retryable draft callback. A minted {@code grant} the
	 * result carries is recorded once the resolution commits, and only then. */
	private boolean resolveRequest(AString targetDID, AString id, AString status,
			AMap<AString, ACell> response, AString responder, ACell result, Job job, UCANAdapter.Grant grant) {
		AString requester = job == null ? null : job.getOwner();
		User requesterAccount = requester == null ? null : engine.getVenueState().users().user(requester);
		// Null when the request was not open to resolve
		Resolution resolution = engine.getVenueState().transact(venue -> {
			User target = venue.users().user(targetDID);
			HitlRequest request = (target == null) ? null : target.inbox().request(id);
			AMap<AString, ACell> record = (request == null) ? null : request.getRecord();
			if (record == null || !request.resolve(status, response)) return null;
			Resolution settled = settle(venue, job, data -> {
				if (Hitl.ANSWERED.equals(status)) return data.assoc(Fields.STATUS, Status.COMPLETE).assoc(Fields.OUTPUT, result);
				return failed(data, Hitl.REJECTED.equals(status) ? rejection(targetDID, RT.getIn(result, Hitl.COMMENT)) : REQUEST_EXPIRED);
			}, Attribution.owner(record), RT.ensureString(record.get(Hitl.AGENT)),
				RT.ensureString(record.get(Fields.SESSION_ID)), responder, result);
			if (settled == null) throw new ConflictException(REQUEST_ALREADY_RESOLVED);
			return settled;
		});
		if (resolution == null) return false;
		if (grant != null) grant.audit(engine.audit());
		afterResolution(resolution, id, requesterAccount);
		return true;
	}

	/**
	 * Finishes a request's job with {@code outcome}, ends the asking agent's
	 * wait, and delivers {@code result} to the task or session that asked. A
	 * pure edit of the draft.
	 *
	 * @param job The request's job, or null when it has gone
	 * @param agentId The agent that asked, or null when a person did
	 * @param sidHex The session it asked in, or null
	 * @return What to do after commit, or null when the job had already finished
	 */
	private Resolution settle(VenueState venue, Job job, java.util.function.UnaryOperator<AMap<AString, ACell>> outcome,
			AString askingOwner, AString agentId, AString sidHex, AString responder, ACell result) {
		var draft = venue.users();
		Runnable notification = null;
		Blob waitingTask = null;
		if (job != null) {
			// Read first: the job's entry goes as it finishes
			AMap<AString, ACell> kept = venue.jobIndex().adapterData(job.getID());
			notification = ((VenueJob) job).updateIn(venue, outcome);
			if (notification == null) return null;
			cancelExpiry(venue, kept);
			// Terminal jobs are skipped at restart, so the wait ends with the result
			AString requestingAgent = Engine.agentIdIn(job.getOwner(), job.getCaller());
			User owner = draft.user(job.getOwner());
			Agent askingAgent = owner == null || requestingAgent == null ? null : owner.agent(requestingAgent);
			if (askingAgent != null && askingAgent.getRecord() != null) {
				AString taskHex = RT.ensureString(RT.getIn(askingAgent.getPending().get(job.getID()), Fields.TASK_ID));
				if (taskHex != null) waitingTask = Blob.fromHex(taskHex.toString());
				askingAgent.removePending(job.getID());
			}
		}
		if (agentId == null || sidHex == null) return new Resolution(notification, null, false); // Human/API requester.
		AgentAdapter agents = engine.getAdapter("agent") instanceof AgentAdapter a ? a : null;
		// The result carries the request ID. This is an independent message,
		// not a chat waiter tied to the now-terminal HITL Job; it goes to the
		// task whose cycle asked, when there is one, else to the session.
		if (askingOwner != null && agents != null
				&& agents.deliverToWaiter(draft, askingOwner, agentId, Blob.fromHex(sidHex.toString()), waitingTask, responder,
					(job != null) ? Strings.create(job.getID().toHexString()) : null, result)) {
			return new Resolution(notification, new SessionWake(askingOwner, agentId), false);
		}
		return new Resolution(notification, null, true);
	}

	/** Makes a committed resolution durable, tells the job's observers and wakes the agent that asked. */
	private void afterResolution(Resolution resolution, AString id, User requesterAccount) {
		SessionWake destination = resolution.wake();
		// Notify live waiters even if the durability barrier fails: the in-memory
		// transaction is already committed. The responder still receives that error.
		try { engine.flush(); }
		finally { if (resolution.notification() != null) resolution.notification().run(); }
		if (destination != null && requesterAccount != null && engine.getAdapter("agent") instanceof AgentAdapter agents) synchronized (engine) {
			requesterAccount.get(); // Fence wake against account deletion/recreation.
			agents.wakeAgent(destination.owner(), destination.agent(), true);
		}
		if (resolution.unavailable()) log.warn("HITL request {} resolved but its asking agent session is unavailable", id);
	}
}
