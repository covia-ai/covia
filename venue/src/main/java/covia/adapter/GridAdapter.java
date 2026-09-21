package covia.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.auth.did.DIDVerifier;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.grid.auth.VenueDID;
import covia.venue.LocalVenue;
import covia.venue.RequestContext;
import covia.venue.RemoteJobs;
import covia.grid.client.VenueHTTP;
import covia.venue.UcanJwtValidator;

/**
 * Adapter that proxies Covia grid operations to the local engine or a remote venue.
 */
public class GridAdapter extends AAdapter {
	private static final AString AUTHENTICATE_AS = Strings.intern("authenticateAs");

    /** Asset hash for synchronous grid run operation. */
    public static Hash RUN_OPERATION;
    /** Asset hash for asynchronous grid invoke operation. */
    public static Hash INVOKE_OPERATION;
    /** Asset hash for job status lookup operation. */
    public static Hash JOB_STATUS_OPERATION;
    /** Asset hash for job result retrieval operation. */
    public static Hash JOB_RESULT_OPERATION;

	@Override
	public String getName() {
		return "grid";
	}
	
	@Override
	public String getDescription() {
		return "Enables distributed processing and resource sharing across the Covia network via grid operations. " +
		   "Provides access to remote venues, distributed job execution, and collaborative computing capabilities. " +
		   "Perfect for scaling computational tasks, leveraging distributed resources, and building resilient, distributed AI applications.";
	}

    @Override
    protected void installAssets() {
    	// The adapter's own skill: v/skills/grid lives and dies with this adapter.
    	installSkill("grid/grid", "/skills/grid.json");
		installSkill("root/grid", "/skills/grid.json");
        RUN_OPERATION        = installAsset("grid/run",        "/adapters/grid/run.json");
        INVOKE_OPERATION     = installAsset("grid/invoke",     "/adapters/grid/invoke.json");
        JOB_STATUS_OPERATION = installAsset("grid/job-status", "/adapters/grid/jobStatus.json");
        JOB_RESULT_OPERATION = installAsset("grid/job-result", "/adapters/grid/jobResult.json");
    }

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String gridOp = getSubOperation(meta);
		if (("run".equals(gridOp) || ("jobResult".equals(gridOp) && parseTimeoutMs(input) <= 0))
				&& resolveVenue(meta, input) != null) {
			return engine.jobs().invokeInternal(meta, input, ctx);
		}
		if (gridOp == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid grid operation: no sub-operation in metadata"));
		}
		return switch (gridOp) {
			case "run"       -> invokeRun(ctx, meta, input);
			case "invoke"    -> invokeAsync(ctx, meta, input);
			case "jobStatus" -> invokeJobStatus(ctx, meta, input);
			case "jobResult" -> invokeJobResult(ctx, meta, input);
			default          -> CompletableFuture.failedFuture(new IllegalArgumentException("Unrecognised grid operation: " + gridOp));
		};
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String operation = getSubOperation(meta);
		if (("run".equals(operation) || ("jobResult".equals(operation) && parseTimeoutMs(input) <= 0))
				&& resolveVenue(meta, input) != null) {
			requireInvoke(ctx);
			job.start(() -> delegate(job, ctx, meta, input));
		} else {
			super.invoke(job, ctx, meta, input);
		}
	}

	private void delegate(Job job, RequestContext ctx, ACell meta, ACell input) {
		AString operation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		boolean existing = "jobResult".equals(getSubOperation(RT.ensureMap(meta)));
		Blob existingID = existing ? parseJobId(RT.getIn(input, Fields.ID)) : null;
		if (existing && existingID == null) throw new IllegalArgumentException("'id' is required");
		if (!existing && operation == null) throw new IllegalArgumentException("'operation' is required");
		AString target = resolveVenue(meta, input);
		RemoteConnection remote = remoteConnection(ctx, target, input);
		RemoteJobs observer = engine.remoteJobs();
		observer.prepare(job, "covia", target.toString(), remote.credentials());
		if (job.isFinished()) return;
		if (existing) {
			try { observer.accepted(job, existingID.toHexString()); }
			finally { watch(job, remote.venue(), existingID); }
			return;
		}
		// Submit once and retain the handle. SDK background polling is a bounded
		// caller convenience and must not define the delegated Job's lifetime.
		CompletableFuture<Job> submitted = remote.venue() instanceof VenueHTTP http
			? http.startJobAsync(operation, RT.getIn(input, Fields.INPUT))
			: remote.venue().invoke(operation.toString(), RT.getIn(input, Fields.INPUT));
		submitted.whenComplete((accepted, error) -> {
			observer.received(job, () -> {
			if (error != null) { observer.submissionFailed(job, error); return; }
			try {
				observer.accepted(job, accepted.getID().toHexString());
				applyRemote(job, accepted.getData());
			} catch (RuntimeException | Error failure) {
				observer.submissionUnknown(job, failure);
			} finally {
				ACell id = RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID);
				if (id != null) watch(job, remote.venue(), Job.parseID(id));
			}
			});
		});
	}

	private void watch(Job job, Venue venue, Blob id) {
		engine.remoteJobs().observe(job, () -> venue.getJobStatus(id), snapshot -> applyRemote(job, snapshot));
	}

	private void applyRemote(Job job, AMap<AString, ACell> snapshot) {
		AString status = RT.ensureString(snapshot.get(Fields.STATUS));
		if (status == null) throw new IllegalArgumentException("Remote Job has no status");
		AMap<AString, ACell> record = RemoteJobs.descriptor(job);
		Blob expected = Job.parseID(record.get(RemoteJobs.REMOTE_ID));
		if (!expected.equals(Job.parseID(snapshot.get(Fields.ID)))) throw new IllegalArgumentException("Remote Job ID mismatch");
		if (Status.COMPLETE.equals(status)) {
			job.completeWith(snapshot.get(Fields.OUTPUT), data -> RemoteJobs.observed(data, status));
			return;
		}
		if (!(Status.PENDING.equals(status) || Status.STARTED.equals(status)
			|| Status.PAUSED.equals(status) || Status.INPUT_REQUIRED.equals(status)
			|| Status.AUTH_REQUIRED.equals(status) || Status.FAILED.equals(status)
			|| Status.CANCELLED.equals(status) || Status.REJECTED.equals(status))) {
			throw new IllegalArgumentException("Invalid remote Job status");
		}
		// Local processing is STARTED even while the remote worker is queued.
		AString localStatus = Status.PENDING.equals(status) ? Status.STARTED : status;
		job.update(data -> {
			AMap<AString, ACell> next = RemoteJobs.observed(data, status).assoc(Fields.STATUS, localStatus);
			if (Job.isFinished(snapshot)) next = next.assoc(Fields.ERROR, snapshot.get(Fields.ERROR));
			return next.equals(data) ? null : next;
		});
	}

	@Override public void recoverJob(Job job) {
		AMap<AString, ACell> record = RemoteJobs.descriptor(job);
		if (record == null) { super.recoverJob(job); return; }
		ACell id = record.get(RemoteJobs.REMOTE_ID);
		if (id == null) { engine.remoteJobs().submissionUnknown(job, null); return; }
		try {
			Venue venue = restoreConnection(RT.ensureString(record.get(RemoteJobs.TARGET)), engine.remoteJobs().credentials(job));
			watch(job, venue, Job.parseID(id));
		} catch (RuntimeException e) { engine.remoteJobs().unavailable(job, e); }
	}

	@Override public void suspendJob(Job job) {
		if (RemoteJobs.descriptor(job) != null) engine.remoteJobs().suspend(job);
		else super.suspendJob(job);
	}

	/**
	 * Executes a grid operation and waits for completion, returning the finished result.
	 */
	private CompletableFuture<ACell> invokeRun(RequestContext ctx, ACell meta, ACell input) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"'operation' is required; pass an operation path or asset hash"));
		}

        ACell operationInput = RT.getIn(input, Fields.INPUT);
        AString venueSpec = resolveVenue(meta, input);

        Venue venue = selectVenue(ctx, venueSpec, input);

		return venue.run(targetOperation.toString(), operationInput);
	}

	/**
	 * Submits a grid operation but returns immediately with the job status payload.
	 */
	private CompletableFuture<ACell> invokeAsync(RequestContext ctx, ACell meta, ACell input) {
		AString targetOperation = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (targetOperation == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"'operation' is required; pass an operation path or asset hash"));
		}

        // The inner input is the target operation's input and is passed
        // verbatim. A Covia operation may take any JSON value, so a string here
        // is a valid input, not a serialised object to repair: string-to-object
        // parsing belongs only where a schema admits nothing but an object
        // (tool-call arguments at the MCP and provider boundaries, #508).
        ACell operationInput = RT.getIn(input, Fields.INPUT);
        AString venueSpec = resolveVenue(meta, input);

        Venue venue = selectVenue(ctx, venueSpec, input);

        CompletableFuture<Job> jobFuture = venue.invoke(targetOperation.toString(), operationInput);
        return jobFuture.thenApply(Job::getData);
	}

	private CompletableFuture<ACell> invokeJobStatus(RequestContext ctx, ACell meta, ACell input) {
		Blob jobId = parseJobId(RT.getIn(input, Fields.ID));
		if (jobId == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"'id' is required; use the job ID returned by grid:invoke"));
		}

		Venue venue = selectVenue(ctx, resolveVenue(meta, input), input);
		return venue.getJobStatus(jobId).thenApply(status -> status);
	}

	private CompletableFuture<ACell> invokeJobResult(RequestContext ctx, ACell meta, ACell input) {
		Blob jobId = parseJobId(RT.getIn(input, Fields.ID));
		if (jobId == null) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(
				"'id' is required; use the job ID returned by grid:invoke"));
		}

		Venue venue = selectVenue(ctx, resolveVenue(meta, input), input);
		CompletableFuture<ACell> jobFuture = venue.awaitJobResult(jobId);

		long timeoutMs = parseTimeoutMs(input);
		if (timeoutMs <= 0) return jobFuture;

		// Derive a new future so the timeout doesn't corrupt the underlying Job's future.
		// thenApply(x -> x) creates an independent CompletableFuture that completes
		// when jobFuture does; applying orTimeout to it fails only this derivative.
		return jobFuture.thenApply(x -> x)
				.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
				.exceptionallyCompose(ex -> {
					if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
						return CompletableFuture.failedFuture(
							new TimeoutException("grid:jobResult timed out after " + timeoutMs + "ms waiting for job " + jobId.toHexString()));
					}
					return CompletableFuture.failedFuture(ex);
				});
	}

	private static long parseTimeoutMs(ACell input) {
		ACell v = RT.getIn(input, Fields.TIMEOUT);
		if (v instanceof CVMLong l) return l.longValue();
		return 0;
	}

	/** Ability authorising an explicit {@code authenticateAs:"venue"} request. */
	static final AString RELAY_ABILITY = Strings.intern("venue/relay");

	/**
	 * Resolves the target venue for a grid op. The operation input explicitly
	 * chooses authentication behaviour with {@code authenticateAs}; credentials
	 * authenticate and UCANs authorise, with no token-presence mode switch:
	 * <ul>
	 *   <li>{@code anonymous} (default): no authentication and no grants.</li>
	 *   <li>{@code caller}: requires a target-audienced, empty-att identity
	 *       credential from the caller; it travels in Authorization, while only
	 *       non-empty grants audienced to that caller travel in {@code ucans}.</li>
	 *   <li>{@code venue}: explicitly asks this venue to authenticate as itself;
	 *       a caller-issued {@code venue/relay} grant must authorise that request.
	 *       Only grants audienced to this venue are forwarded.</li>
	 * </ul>
	 * A local target carries the complete caller context because there is no
	 * transport boundary at which to reconstruct it.
	 */
	private Venue selectVenue(RequestContext ctx, AString venueSpec, ACell input) {
		if (venueSpec != null) return connectRemote(ctx, venueSpec, input);
		LocalVenue lv = new LocalVenue(engine);
		// An in-process hop has no transport boundary at which to reconstruct
		// authority. Carry the complete immutable context so agent caps,
		// sub-principal scope, proofs, cancellation and parent job scope survive.
		lv.setRequestContext(ctx);
		return lv;
	}

	private Venue connectRemote(RequestContext ctx, AString venueSpec, ACell input) {
		return remoteConnection(ctx, venueSpec, input).venue();
	}

	private record RemoteConnection(Venue venue, AMap<AString, ACell> credentials) {}

	private RemoteConnection remoteConnection(RequestContext ctx, AString venueSpec, ACell input) {
		AString venueDID = engine.getDIDString();
		List<UCAN> tokens = parsedRawUcans(ctx, engine.didVerifier());
		AString modeCell = RT.ensureString(RT.getIn(input, AUTHENTICATE_AS));
		String mode = (modeCell != null) ? modeCell.toString() : "anonymous";

		VenueAuth auth;
		AString principal;
		AMap<AString, ACell> saved = Maps.of(AUTHENTICATE_AS, Strings.create(mode));
		switch (mode) {
			case "anonymous" -> {
				auth = VenueAuth.none();
				principal = null;
			}
			case "caller" -> {
				AString targetDID = Strings.create(targetVenueDID(venueSpec));
				String credential = identityCredential(ctx, tokens, ctx.getCallerDID(), targetDID);
				if (credential == null) {
					throw new AuthException("authenticateAs=caller requires an in-date, empty-att "
						+ "identity credential issued by the caller to " + targetDID);
				}
				auth = VenueAuth.bearer(credential);
				saved = saved.assoc(Fields.BEARER_TOKEN, Strings.create(credential));
				principal = ctx.getCallerDID();
			}
			case "venue" -> {
				AString targetDID = Strings.create(targetVenueDID(venueSpec));
				if (!hasRelayGrant(tokens, ctx.getCallerDID(), venueDID)) {
					throw new AuthException("authenticateAs=venue requires venue/relay on "
						+ ctx.getCallerDID() + " granted to " + venueDID);
				}
				auth = VenueAuth.identityKeyPair(engine.getKeyPair(), venueDID.toString(),
					targetDID.toString());
				saved = saved.assoc(Fields.VENUE, targetDID);
				principal = venueDID;
			}
			default -> throw new IllegalArgumentException(
				"authenticateAs must be one of: anonymous, caller, venue");
		}

		Venue venue = Grid.connect(venueSpec.toString(), auth);
		List<String> grants = admissibleGrants(ctx, tokens, principal);
		venue.setUcans(grants);
		if (grants != null) saved = saved.assoc(Fields.UCANS, convex.core.data.Vectors.create(grants.stream().map(Strings::create).toList()));
		return new RemoteConnection(venue, saved);
	}

	private Venue restoreConnection(AString target, AMap<AString, ACell> saved) {
		String mode = RT.ensureString(saved.get(AUTHENTICATE_AS)).toString();
		VenueAuth auth = switch (mode) {
			case "anonymous" -> VenueAuth.none();
			case "caller" -> VenueAuth.bearer(RT.ensureString(saved.get(Fields.BEARER_TOKEN)).toString());
			case "venue" -> VenueAuth.identityKeyPair(engine.getKeyPair(), engine.getDIDString().toString(),
				RT.ensureString(saved.get(Fields.VENUE)).toString());
			default -> throw new IllegalArgumentException("Invalid saved remote authentication mode");
		};
		Venue venue = Grid.connect(target.toString(), auth);
		AVector<ACell> grants = RT.ensureVector(saved.get(Fields.UCANS));
		if (grants != null) {
			List<String> raw = new ArrayList<>();
			for (ACell grant : grants) raw.add(grant.toString());
			venue.setUcans(raw);
		}
		return venue;
	}

	/** Resolve the target identity once for audience-bound venue authentication. */
	private static String targetVenueDID(AString venueSpec) {
		String target = venueSpec.toString();
		if (target.startsWith("did:")) return target;
		return VenueDID.discover(target);
	}

	/**
	 * Writes a value at a mutable path hosted by another venue.
	 *
	 * <p>The caller's authority is checked locally against the exact destination
	 * before any network request, then the ordinary {@code covia:write}
	 * operation checks it again at the destination. Raw UCANs are forwarded by
	 * {@link #connectRemote}; no framework-only bypass exists on either side.</p>
	 */
	CompletableFuture<ACell> writeRemotePath(RequestContext ctx, AString venueSpec,
			AString path, ACell value) {
		ACell writeInput = Maps.of(Fields.PATH, path, Fields.VALUE, value);
		RequestContext writeCtx = ctx.withInvocation(writeInput, null);
		engine.requireResourceAccess(writeCtx, path, Capability.CRUD_WRITE);
		// A foreign output path is itself an explicit request to write remotely as
		// the current caller. The identity credential authenticates that caller;
		// its mere presence still cannot select this behaviour.
		Venue venue = connectRemote(ctx, venueSpec,
			Maps.of(AUTHENTICATE_AS, Strings.create("caller")));
		return venue.invoke("v/ops/covia/write",
				writeInput)
			.thenCompose(Job::future);
	}

	/**
	 * True when the caller has granted this venue permission to relay as itself.
	 * This is an authorisation predicate only; callers must separately request
	 * {@code authenticateAs:"venue"} in the grid operation input.
	 */
	static boolean hasRelayGrant(List<UCAN> tokens, AString caller, AString venueDID) {
		if (tokens == null || caller == null) return false;
		long now = System.currentTimeMillis() / 1000;
		for (UCAN token : tokens) {
			if (!caller.equals(token.getIssuer())) continue;      // grant must come from OUR caller
			if (!venueDID.equals(token.getAudience())) continue;  // ...and be addressed to US
			if (!UCANValidator.checkTemporalBounds(token, now)) continue;
			AVector<ACell> att = token.getCapabilities();
			if (att == null) continue;
			for (long i = 0; i < att.count(); i++) {
				AMap<AString, ACell> cap = RT.castMap(att.get(i));
				if (cap == null) continue;
				if (!caller.equals(RT.ensureString(cap.get(Capability.WITH)))) continue;
				AString can = RT.ensureString(cap.get(Capability.CAN));
				if (Capability.abilityCovers(can, RELAY_ABILITY)) return true;
			}
		}
		return false;
	}

	/**
	 * Filters raw tokens down to in-date, non-empty capability grants whose
	 * audience is exactly the principal authenticated at the target. Identity
	 * credentials never enter the grant channel. Returns raw JWT strings;
	 * {@code tokens} is index-aligned with {@link RequestContext#getRawUcans()}.
	 */
	static List<String> admissibleGrants(RequestContext ctx,
			List<UCAN> tokens, AString principal) {
		AVector<ACell> raw = ctx.getRawUcans();
		if (raw == null || tokens == null || principal == null) return null;
		long now = System.currentTimeMillis() / 1000;
		List<String> out = new ArrayList<>();
		for (int i = 0; i < raw.count(); i++) {
			AString jwt = RT.ensureString(raw.get(i));
			UCAN token = (i < tokens.size()) ? tokens.get(i) : null;
			if (jwt == null || token == null) continue;                    // unparseable → don't relay
			if (!UCANValidator.checkTemporalBounds(token, now)) continue;  // expired → inert
			AString aud = token.getAudience();
			AVector<ACell> capabilities = token.getCapabilities();
			if (aud == null || !aud.equals(principal)
					|| capabilities == null || capabilities.isEmpty()) continue;
			out.add(jwt.toString());
		}
		return out.isEmpty() ? null : out;
	}

	/** Finds the caller's explicit, target-audienced identity credential. */
	static String identityCredential(RequestContext ctx, List<UCAN> tokens,
			AString caller, AString targetVenueDID) {
		AVector<ACell> raw = ctx.getRawUcans();
		if (raw == null || tokens == null || caller == null || targetVenueDID == null) return null;
		long now = System.currentTimeMillis() / 1000;
		String found = null;
		for (int i = 0; i < raw.count(); i++) {
			AString jwt = RT.ensureString(raw.get(i));
			UCAN token = (i < tokens.size()) ? tokens.get(i) : null;
			if (jwt == null || token == null
					|| !UCANValidator.checkTemporalBounds(token, now)
					|| !caller.equals(token.getIssuer())
					|| !targetVenueDID.equals(token.getAudience())) continue;
			AVector<ACell> capabilities = token.getCapabilities();
			if (capabilities == null || !capabilities.isEmpty()) continue;
			if (found != null && !found.equals(jwt.toString())) {
				throw new IllegalArgumentException(
					"Multiple caller identity credentials were supplied for " + targetVenueDID);
			}
			found = jwt.toString();
		}
		return found;
	}

	/**
	 * Parses the caller's raw transport tokens for audience/issuer inspection;
	 * null-padded on failure so indices align with {@link RequestContext#getRawUcans()}.
	 */
	static List<UCAN> parsedRawUcans(RequestContext ctx, DIDVerifier verifier) {
		AVector<ACell> raw = ctx.getRawUcans();
		if (raw == null || raw.isEmpty()) return null;
		List<UCAN> out = new ArrayList<>();
		long now = System.currentTimeMillis() / 1000;
		for (long i = 0; i < raw.count(); i++) {
			UCAN token = null;
			AString jwt = RT.ensureString(raw.get(i));
			if (jwt != null) {
				try {
					token = UcanJwtValidator.validateJWT(jwt, now, verifier);
				} catch (Exception e) {
					// Defective token: null slot, grants nothing.
				}
			}
			out.add(token);
		}
		return out;
	}

    private Blob parseJobId(ACell jobIdCell) {
        if (jobIdCell == null) return null;
        try {
            Blob id = Job.parseID(jobIdCell);
            if (id == null) {
                throw new IllegalArgumentException("Invalid job ID format: " + jobIdCell);
            }
            return id;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid job ID format: " + jobIdCell, e);
        }
    }

	/**
	 * Finds the venue specification from input (or metadata) if provided.
	 */
	private AString resolveVenue(ACell meta, ACell input) {
		AString venue = RT.ensureString(RT.getIn(input, Fields.VENUE));
		if (venue != null) return venue;
		venue = RT.ensureString(RT.getIn(meta, Fields.OPERATION, Fields.VENUE));
		return venue;
	}

}
