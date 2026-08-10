package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import convex.auth.ucan.UCAN;
import convex.auth.ucan.UCANValidator;
import covia.grid.Grid;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.venue.LocalVenue;
import covia.venue.RequestContext;

/**
 * Adapter that proxies Covia grid operations to the local engine or a remote venue.
 */
public class GridAdapter extends AAdapter {

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

    /** The ability a caller grants this venue to relay a hop as itself:
     *  a token {@code {iss: caller, aud: <thisVenue>, att: [{…, can: "venue/relay"}]}}
     *  is both the instruction and the authorisation — no mode flag. */
    static final AString RELAY_ABILITY = convex.core.data.Strings.intern("venue/relay");

    /**
     * Resolves the target venue for a grid op, forwarding the caller's authority
     * (C3a, covia#100). Authority travels ONLY in the {@code ucans} proof channel
     * — never in operation input (which is persisted in job records):
     * <ul>
     *   <li><b>Proofs are relayed, audience-filtered.</b> Only tokens provably
     *       admissible at the target travel: audienced to the principal acting
     *       there, or to the target itself. Tokens audienced elsewhere are
     *       guaranteed inert at the target (the audience check fails at use), so
     *       forwarding them would be pure disclosure of the caller's other
     *       grants/relationships. The caller curates what it presents; the relay
     *       drops only the provably-irrelevant.</li>
     *   <li><b>Caller identity</b> travels as an identity token the caller minted
     *       (a UCAN with empty {@code att}, audienced to the TARGET venue) inside
     *       their {@code ucans}; the target's ingress verifies the caller's own
     *       signature — zero trust in this relay. Nothing to do here beyond
     *       forwarding it.</li>
     *   <li><b>Venue-as-delegate:</b> when the caller has granted THIS venue a
     *       {@code venue/relay} capability (token issued by the caller, audienced
     *       to this venue), the hop authenticates as the venue itself, exercising
     *       that delegation. The issuer-must-be-the-caller rule is inherent — a
     *       delegation someone else minted for this venue is not an instruction
     *       from this caller (confused-deputy safe).</li>
     * </ul>
     * No relay instruction and no identity token → anonymous hop (the explicit
     * choice for public operations). A local target carries the complete caller
     * context because there is no transport boundary at which to reconstruct it.
     */
    private Venue selectVenue(RequestContext ctx, AString venueSpec, ACell input) {
        if (venueSpec != null) {
            return connectRemote(ctx, venueSpec);
        }
        LocalVenue lv = new LocalVenue(engine);
        // An in-process hop has no transport boundary at which to reconstruct
        // authority. Carry the complete immutable context so agent caps,
        // sub-principal scope, proofs, cancellation and parent job scope survive.
        lv.setRequestContext(ctx);
        return lv;
    }

    private Venue connectRemote(RequestContext ctx, AString venueSpec) {
        AString venueDID = engine.getDIDString();
        java.util.List<UCAN> tokens = parsedRawUcans(ctx, engine.didVerifier());

        boolean relayAsSelf = hasRelayInstruction(tokens, ctx.getCallerDID(), venueDID);
        VenueAuth auth = relayAsSelf
            ? VenueAuth.keyPair(engine.getKeyPair())
            : VenueAuth.none();

        // The principal acting at the target: this venue when relaying as itself
        // (the delegation chain's leaf is audienced to us), else the caller.
        AString principal = relayAsSelf ? venueDID : ctx.getCallerDID();

        Venue venue = Grid.connect(venueSpec.toString(), auth);
        venue.setUcans(admissibleTokens(ctx, tokens, principal));
        return venue;
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
		engine.requireResourceAccess(writeCtx, path, convex.auth.ucan.Capability.CRUD_WRITE);
		Venue venue = connectRemote(ctx, venueSpec);
		return venue.invoke("v/ops/covia/write",
				writeInput)
			.thenCompose(Job::future);
	}

    /**
     * True when the caller has instructed this venue to relay as itself: a
     * presented token issued BY the caller, audienced TO this venue, carrying a
     * capability whose ability covers {@link #RELAY_ABILITY}, still in-date.
     * Signature/chain were verified at transport ingress.
     */
    static boolean hasRelayInstruction(java.util.List<UCAN> tokens,
            AString caller, AString venueDID) {
        if (tokens == null || caller == null) return false;
        long now = System.currentTimeMillis() / 1000;
        for (UCAN token : tokens) {
            if (!caller.equals(token.getIssuer())) continue;      // instruction must come from OUR caller
            if (!venueDID.equals(token.getAudience())) continue;  // ...and be addressed to US
            if (!UCANValidator.checkTemporalBounds(token, now)) continue;
            AVector<ACell> att = token.getCapabilities();
            if (att == null) continue;
            for (long i = 0; i < att.count(); i++) {
                AMap<AString, ACell> cap = RT.castMap(att.get(i));
                if (cap == null) continue;
                AString can = RT.ensureString(cap.get(convex.auth.ucan.Capability.CAN));
                if (convex.auth.ucan.Capability.abilityCovers(can, RELAY_ABILITY)) return true;
            }
        }
        return false;
    }

    /**
     * Filters the caller's raw tokens for a hop, relaying only what could be
     * admissible at the target. Provably inert tokens are dropped — forwarding
     * them would be pure disclosure of the caller's unrelated grants:
     * <ul>
     *   <li><b>expired / unparseable</b> — grant nothing anywhere;</li>
     *   <li><b>audienced to a non-principal</b> — a token audienced to the local
     *       caller when the hop's principal is this venue (relay-as-self) can
     *       authorise nothing at the target.</li>
     * </ul>
     * Tokens audienced to <em>other</em> identities are kept: the target's DID is
     * not generally known before contact (URL venue specs), so a token audienced
     * "elsewhere" may be the caller's identity token for the target. Presentation
     * remains the caller's disclosure choice — present per-request, not a wallet.
     * Returns raw JWT strings; {@code tokens} is index-aligned with
     * {@link RequestContext#getRawUcans()}.
     */
    static java.util.List<String> admissibleTokens(RequestContext ctx,
            java.util.List<UCAN> tokens, AString principal) {
        AVector<ACell> raw = ctx.getRawUcans();
        if (raw == null || tokens == null) return null;
        long now = System.currentTimeMillis() / 1000;
        AString caller = ctx.getCallerDID();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < raw.count(); i++) {
            AString jwt = RT.ensureString(raw.get(i));
            UCAN token = (i < tokens.size()) ? tokens.get(i) : null;
            if (jwt == null || token == null) continue;                    // unparseable → don't relay
            if (!UCANValidator.checkTemporalBounds(token, now)) continue;  // expired → inert
            AString aud = token.getAudience();
            if (aud == null) continue;
            // Provably inert: audienced to the local caller while the principal
            // at the target is someone else (this venue, relay-as-self mode).
            if (!aud.equals(principal) && aud.equals(caller)) continue;
            out.add(jwt.toString());
        }
        return out.isEmpty() ? null : out;
    }

    /** Parses the caller's raw transport tokens (already signature-verified at
     *  ingress) for audience/issuer inspection; null-padded on parse failure so
     *  indices align with {@link RequestContext#getRawUcans()}. */
    static java.util.List<UCAN> parsedRawUcans(RequestContext ctx,
            convex.auth.did.DIDVerifier verifier) {
        AVector<ACell> raw = ctx.getRawUcans();
        if (raw == null || raw.isEmpty()) return null;
        java.util.List<UCAN> out = new java.util.ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        for (long i = 0; i < raw.count(); i++) {
            UCAN token = null;
            AString jwt = RT.ensureString(raw.get(i));
            if (jwt != null) {
                try {
                    token = UCANValidator.validateJWT(jwt, now, verifier);
                } catch (Exception e) {
                    // defective token: null slot, grants nothing
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
