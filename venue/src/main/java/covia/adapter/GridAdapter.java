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
import convex.core.util.JSON;
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
		job.setStatus(Status.STARTED);
		bridgeToJob(job, invokeFuture(ctx, meta, input));
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

        ACell operationInput = coerceOperationInput(RT.getIn(input, Fields.INPUT));
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

        ACell operationInput = coerceOperationInput(RT.getIn(input, Fields.INPUT));
        AString venueSpec = resolveVenue(meta, input);

        Venue venue = selectVenue(ctx, venueSpec, input);

        CompletableFuture<Job> jobFuture = venue.invoke(targetOperation.toString(), operationInput);
        return jobFuture.thenApply(Job::getData);
	}

	/**
	 * Workaround for MCP clients that serialise nested object/array arguments
	 * as JSON strings. The {@code grid_run}/{@code grid_invoke} tool schema
	 * deliberately accepts polymorphic input (string is a valid member type),
	 * so the MCP-boundary coercion in {@link covia.venue.api.MCP} cannot fix
	 * this case. We re-parse here when the inner input arrives as a JSON-
	 * shaped string. Gated by {@code Config.fixMcpStrings} (default true).
	 */
	private ACell coerceOperationInput(ACell operationInput) {
		if (!(operationInput instanceof AString s)) return operationInput;
		if (engine == null || !engine.config().isFixMcpStrings()) return operationInput;
		String str = s.toString();
		if (str.isEmpty()) return operationInput;
		char c = str.charAt(0);
		if (c != '{' && c != '[') return operationInput;
		try {
			ACell parsed = JSON.parse(str);
			if (parsed instanceof AMap || parsed instanceof AVector) return parsed;
		} catch (Exception ignored) {
		}
		return operationInput;
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
		AString venueDID = engine.getDIDString();
		List<UCAN> tokens = parsedRawUcans(ctx, engine.didVerifier());
		AString modeCell = RT.ensureString(RT.getIn(input, AUTHENTICATE_AS));
		String mode = (modeCell != null) ? modeCell.toString() : "anonymous";

		VenueAuth auth;
		AString principal;
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
				principal = venueDID;
			}
			default -> throw new IllegalArgumentException(
				"authenticateAs must be one of: anonymous, caller, venue");
		}

		Venue venue = Grid.connect(venueSpec.toString(), auth);
		venue.setUcans(admissibleGrants(ctx, tokens, principal));
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
