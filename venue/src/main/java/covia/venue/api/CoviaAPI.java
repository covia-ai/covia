package covia.venue.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.adapter.CoviaAdapter;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.api.model.ErrorResponse;
import covia.venue.api.model.InvokeRequest;
import covia.venue.api.model.InvokeResult;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.User;
import covia.venue.server.AuthMiddleware;
import covia.venue.server.SseServer;
import io.javalin.config.RoutesConfig;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

public class CoviaAPI extends ACoviaAPI {
	
	public static final Logger log=LoggerFactory.getLogger(CoviaAPI.class);

	private static final String ROUTE = "/api/v1/";

	public static final String INVOKE = "invokeOperation";

	public static final String GET_ASSET = "getAsset";
	public static final String GET_ASSETS = "getAssets";

	public static final String ADD_ASSET="addAsset";

	private static final String GET_CONTENT = "getContent";

	private static final String PUT_CONTENT = "putContent";
	public static final String CANCEL_JOB = "cancelJob";
	public static final String PAUSE_JOB = "pauseJob";
	public static final String RESUME_JOB = "resumeJob";
	public static final String DELETE_JOB = "deleteJob";
	public static final String SEND_MESSAGE = "sendMessage";

	private static final String GET_JOB = "getJob";

	public static final AString SERVICE_TYPE = Strings.intern("Covia.API.v1");	
	public static final AString STATS_FIELD = Strings.intern("stats");	
	
	private final SseServer sseServer;
	
	public CoviaAPI(Venue venue) {
		super(venue);
		this.sseServer=new SseServer(engine());

		// Wire SSE broadcasting into engine's job update listeners
		engine().jobs().addJobUpdateListener(job -> {
			Blob jobId = job.getID();
			if (jobId != null) {
				sseServer.broadcastJobUpdate(jobId.toHexString(), job);
			}
		});
	}

	public void addRoutes(RoutesConfig routes) {
		routes.get(ROUTE+"status", this::getStatus);
		// <id> matches slashes, so the asset metadata route accepts any lattice
		// address (a/<hash>, w/…, o/…, <DID>/…) as well as a bare hash. The more
		// specific {id}/content route below is registered too; Javalin prefers
		// the more specific match (covered by CoviaAssetRefTest).
		routes.get(ROUTE+"assets/{id}/content", this::getContent);
		routes.get(ROUTE+"assets/<id>", this::getAsset);
		routes.put(ROUTE+"assets/{id}/content", this::putContent);

		routes.get(ROUTE+"assets", this::getAssets);
		routes.post(ROUTE+"assets", this::addAsset);
		routes.post(ROUTE+"invoke", this::invokeOperation);
		routes.get(ROUTE+"operations", this::getOperations);
		routes.get(ROUTE+"operations/{name}", this::getOperation);
		routes.get(ROUTE+"jobs/<id>", this::getJobStatus);
		routes.post(ROUTE+"jobs/<id>", this::sendMessage);
		routes.put(ROUTE+"jobs/<id>/cancel", this::cancelJob);
		routes.put(ROUTE+"jobs/<id>/pause", this::pauseJob);
		routes.put(ROUTE+"jobs/<id>/resume", this::resumeJob);
		routes.put(ROUTE+"jobs/<id>/delete", this::deleteJob);
		routes.sse(ROUTE+"jobs/<id>/sse", sseServer.registerSSE);
		routes.get(ROUTE+"jobs", this::getJobs);

		// Job-free lattice value reads (#177) — synchronous, capability-checked,
		// no Job persisted. Shares CoviaAdapter's read accessors with covia:* ops.
		routes.get(ROUTE+"values/read", this::getValueRead);
		routes.get(ROUTE+"values/list", this::getValueList);
		routes.get(ROUTE+"values/slice", this::getValueSlice);
		routes.get(ROUTE+"values/inspect", this::getValueInspect);
		routes.get(ROUTE+"values/aggregate", this::getValueAggregate);
		routes.get(ROUTE+"values/count", this::getValueCount);

		// Agents — job-free reads (#180), the caller's own agents. Mirrors how
		// every other entity type is read via GET; no Job persisted.
		routes.get(ROUTE+"agents", this::getAgents);
		routes.get(ROUTE+"agents/{id}", this::getAgentInfo);

		// Secrets
		routes.get(ROUTE+"secrets", this::listSecrets);
		routes.put(ROUTE+"secrets/{name}", this::putSecret);
		routes.delete(ROUTE+"secrets/{name}", this::deleteSecret);

		// DIDs
		routes.get("/.well-known/did.json", this::getDIDDocument);
		routes.get("/a/{id}/did.json", this::getAssetDIDDocument);
	}
	 
	@OpenApi(path = ROUTE + "status", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a quick Covia status report", 
			operationId = "status")	
	protected void getStatus(Context ctx) {
		AMap<AString,ACell> result=engine().getStatus();

		// Add the external base URL
		result=result.assoc(Fields.URL,RT.cvm(getExternalBaseUrl(ctx,null)));

		// Add the stats
		result=result.assoc(STATS_FIELD,engine().getStats());

		buildResult(ctx,200,result);
	}


	
	@OpenApi(path = ROUTE + "assets", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get a list of Covia assets.", 
			operationId = CoviaAPI.GET_ASSETS,
			queryParams = {
		            @OpenApiParam(
		                name = "offset",
		                type = Long.class,
		                description = "The starting index of the assets to retrieve (0-based). Defaults to 0 if not specified.",
		                required = false,
		                example = "0"
		            ),
		            @OpenApiParam(
		                name = "limit",
		                type = Long.class,
		                description = "The maximum number of assets to return. Must be non-negative and not exceed 1000. If not specified, returns the first page (up to 1000 assets).",
		                required = false,
		                example = "100"
		            )
		        },
		        responses = {
		            @OpenApiResponse(
		                status = "200",
		                description = "A JSON object with total, offset, limit, and an items array of asset ID hashes (hex strings).",
		                content = {
		                    @OpenApiContent(
		                        type = "application/json",
		                        from = Object.class
		                    )
		                }
		            ),
		            @OpenApiResponse(
		                status = "400",
		                description = "Bad request due to invalid offset, negative limit, or too many assets requested (exceeding 1000).",
		                content = {
		                    @OpenApiContent(
		                        type = "application/json",
		                        from = ErrorResponse.class
		                    )
		                }
		            )
		        })
	protected void getAssets(Context ctx) {
		long offset=-1;
		long limit=-1;
		Map<Object,Object> result=new HashMap<>();

		try {
			String off = ctx.queryParam("offset");
			if (off!=null) offset=Long.parseLong(off);
			String lim = ctx.queryParam("limit");
			if (lim!=null) {
				limit=Long.parseLong(lim);
				if (limit<0) throw new BadRequestResponse("Negative limit");
			}
		} catch (NumberFormatException | NullPointerException e) {
			buildError(ctx,400,"Invalid offset or limit");
			return;
		}

		long n=venue.getAssetCount();
		result.put(Fields.TOTAL, n);

		long start=Math.max(0, offset);
		// No explicit limit → return the first page (capped at 1000) rather than
		// erroring on catalogs larger than the cap. An explicit over-cap limit is
		// still rejected so callers don't silently receive fewer than requested.
		long actualLimit;
		if (limit<0) {
			actualLimit=Math.min(Math.max(0, n-start), 1000);
		} else {
			if (limit>1000) throw new BadRequestResponse("Too many assets requested: "+limit);
			actualLimit=limit;
		}
		result.put(Fields.OFFSET, start);
		result.put(Fields.LIMIT, actualLimit);

		List<Hash> assetIDs = venue.listAssetIDs(start, actualLimit);
		ArrayList<Object> assetsList=new ArrayList<>();
		for (Hash h : assetIDs) {
			// Bare hex (no 0x prefix) — consistent with asset:list and the jobs
			// endpoints; Hash.parse accepts it on the client (0x optional).
			assetsList.add(h.toHexString());
		}
		result.put(Fields.ITEMS, assetsList);

		buildResult(ctx,result);
	}
	
	@OpenApi(path = ROUTE + "assets", 
			methods = HttpMethod.POST, 
			tags = { "Covia"},
			summary = "Add a Covia asset", 
			requestBody = @OpenApiRequestBody(
					description = "Asset metadata",
					content= @OpenApiContent(
							type = "application/json" ,
							from = Object.class
					)),
			operationId = CoviaAPI.ADD_ASSET,
			responses = {
					@OpenApiResponse(
							status = "201", 
							description = "Account metadata registered", 
							content = {
								@OpenApiContent(
										type = "application/json", 
										from = String.class) })
					})	
	protected void addAsset(Context ctx) {
		try {
			AString meta=Strings.fromStream(ctx.bodyInputStream());
			Hash id=venue.registerAsset(meta);
			buildResult(ctx,201,id.toHexString());
			ctx.header("Location",ROUTE+"assets/"+id.toHexString());
		} catch (ClassCastException | IOException | ParseException e) {
			throw new BadRequestResponse("Unable to parse asset metadata: "+e.getMessage());
		}
	}
	

	@OpenApi(path = ROUTE + "assets/{id}",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "Get Covia asset metadata given an asset reference.",
			operationId = CoviaAPI.GET_ASSET,
			pathParams = {
					@OpenApiParam(
							name = "id",
							description = "Asset reference: a bare CAD3 hash, a content-addressed "
									+ "address (a/<hash>), a workspace/operation path (w/…, o/…), "
									+ "or a DID URL. Resolved the same way invoke resolves "
									+ "operation references.",
							required = true,
							type = String.class,
							example = "a/1234567812345678123456781234567812345678123456781234567812345678") })
	protected void getAsset(Context ctx) {
		String ref=ctx.pathParam("id");
		if (ref==null || ref.isEmpty()) throw new BadRequestResponse("Missing asset reference");

		// Caller identity + transport UCAN authority (a bearer UCAN supplies the
		// proof for cross-DID reads, per the IETF UCAN-HTTP convention).
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer, null);

		try {
			Asset asset=resolveAssetReference(ref, rctx);
			if (asset==null) {
				buildError(ctx,404,"Asset not found: "+ref);
				return;
			}
			// Return the resolved content-addressed id alongside the metadata, so a
			// caller who asked by mutable path still gets a verifiable handle. The
			// CAD3 value hash is formatting-independent — an ETag is the idiomatic
			// home for it.
			Hash resolvedId=asset.getID();
			if (resolvedId!=null) ctx.header("ETag","\"0x"+resolvedId.toHexString()+"\"");
			ctx.result(asset.getMetadata().toString());
			ctx.status(200);
		} catch (AuthException e) {
			buildError(ctx,403,"Not authorised to read asset: "+ref);
		} catch (IOException e) {
			buildError(ctx,500,"Error retrieving asset: "+e.getMessage());
		}
	}

	/**
	 * Resolves an asset reference to an Asset, accepting any lattice address —
	 * a bare hash, {@code a/<hash>}, a workspace/operation path ({@code w/…},
	 * {@code o/…}), or a DID URL — the same addressing scheme {@code invoke}
	 * uses. Enforces the asset-read capability (consistent with the
	 * {@code asset:get} operation); cross-DID reads are satisfied by a UCAN
	 * bearer proof on the request.
	 *
	 * @param ref Asset reference (lattice address or bare hash)
	 * @param ctx Request context (caller identity, capability ceiling, proofs)
	 * @return the resolved Asset, or null if the reference resolves to nothing
	 *         or to a non-asset value
	 */
	private Asset resolveAssetReference(String ref, RequestContext ctx) throws IOException {
		AString refStr = Strings.create(ref);
		ctx.requireCapability(refStr, Strings.intern("asset/read"));

		// Content-addressed forms (bare hex, a/<hash>, did:.../a/<hash>) — fetch
		// the stored record so the returned bytes are byte-identical to the legacy
		// assets/<hash> route, keeping content-addressed lookups self-verifying.
		Hash hash = parseContentAddressedId(ref);
		if (hash != null) {
			return venue.getAsset(hash);
		}

		// Any other lattice address — resolve via the universal resolver (the same
		// path invoke and covia:read use) and interpret the value as metadata.
		ACell value = engine().resolvePath(refStr, ctx);
		if (value instanceof AMap) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> meta = (AMap<AString, ACell>) value;
			return Asset.fromMeta(meta);
		}
		// Remote / named DID-URL bindings aren't in the local lattice — fetch the
		// hash-verified definition from the publishing venue.
		if (ref.startsWith("did:")) {
			return engine().resolveAsset(refStr, ctx);
		}
		return null;
	}

	/**
	 * Strict parse of a content-addressed asset id: a bare hex CAD3 hash,
	 * {@code a/<hash>}, or a DID URL ending in {@code /a/<hash>}. Returns null
	 * for mutable lattice paths ({@code w/…}, {@code o/…}), which are resolved
	 * through the lattice instead.
	 */
	private static Hash parseContentAddressedId(String ref) {
		int aPos = ref.indexOf("/a/");
		if (aPos >= 0) return Hash.parse(ref.substring(aPos + 3));
		if (ref.startsWith("a/")) return Hash.parse(ref.substring(2));
		return Hash.parse(ref);
	}
	
	@OpenApi(path = ROUTE + "assets/{id}/content", 
			methods = HttpMethod.GET, 
			tags = { "Covia"},
			summary = "Get the content of a Covia data asset", 
			operationId = CoviaAPI.GET_CONTENT,
			queryParams = {
					@OpenApiParam(
							name = "inline", 
							description = "Add if the Content-Disposition should be set to inline", 
							required = false, 
							type = String.class, 
							example = "true")
			},
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Asset ID, as a hex string.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") },
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Content returned")
					})	
	protected void getContent(Context ctx) {
		String id=ctx.pathParam("id");
		Hash assetID=Hash.parse(id);

		try {
			Asset asset=venue.getAsset(assetID);
			if (asset==null) {
				buildError(ctx,404,"Asset not found: "+id);
				return;
			}

			AMap<AString,ACell> meta=asset.meta();
			if (!meta.containsKey(Fields.CONTENT)) {
				buildError(ctx,404,"Asset metadata does not specify any content object: "+id);
				return;
			}

			ACell contentMeta=meta.get(Fields.CONTENT);
			AContent content = asset.getContent();
			if (content==null) {
				buildError(ctx,404,"Asset did not have any content available: "+id);
				return;
			}
			ACell contentType=RT.getIn(contentMeta,Fields.CONTENT_TYPE);
			if (contentType instanceof AString ct) {
				ctx.contentType(ct.toString());
			}
			if (ctx.queryParam("inline")!=null) {
				ctx.header("Content-Disposition","inline");
			}

			ACell fileName=RT.getIn(contentMeta,Fields.FILE_NAME);
			if (fileName instanceof AString ct) {
				ctx.header("filename",ct.toString());
			}

			ctx.result(content.getInputStream());
			ctx.status(200);
		} catch (IOException e) {
			ctx.status(500);
		}
	}
	
	@OpenApi(path = ROUTE + "assets/{id}/content", 
			methods = HttpMethod.PUT, 
			tags = { "Covia"},
			summary = "Put the content of a Covia asset. This must match the content-hash stored in the asset.", 
			operationId = CoviaAPI.PUT_CONTENT,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Asset ID, as a hex string.", 
							required = true, 
							type = String.class, 
							example = "0x1234567812345678123456781234567812345678123456781234567812345678") },
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Content stored")
					})	
	protected void putContent(Context ctx) {
		String idString=ctx.pathParam("id");
		Hash assetID=Hash.parse(idString);

		try {
			Asset asset=venue.getAsset(assetID);
			if (asset==null) {
				buildError(ctx,404,"Asset not found: "+idString);
				return;
			}

			AMap<AString,ACell> meta=asset.meta();
			if (!meta.containsKey(Fields.CONTENT)) {
				buildError(ctx,404,"Asset metadata does not specify any content object: "+idString);
				return;
			}

			InputStream is=ctx.bodyInputStream();
			Hash contentHash= venue.putAssetContent(asset,is);
			buildResult(ctx,200,contentHash);

		} catch (IllegalArgumentException e) {
			this.buildError(ctx, 400, "Cannot PUT asset content: "+e.getMessage());
		} catch (IOException | OutOfMemoryError e) {
			this.buildError(ctx, 500,"Storage error trying to PUT asset content: "+e.getMessage());
		}
	}
	
	@OpenApi(path = ROUTE + "invoke",
			methods = HttpMethod.POST,
			tags = { "Covia"},
			summary = "Invoke a Covia operation",
			description = "Invokes an operation, creating a Job that tracks its execution. "
					+ "EVERY invocation is asynchronous by default: the response is the job "
					+ "record (201, with a Location header for the job), which the caller polls "
					+ "via GET /jobs/{id} (or subscribes via /jobs/{id}/sse) until it reaches a "
					+ "terminal status carrying the output. Pass wait (query parameter or body "
					+ "field) for a synchronous response: true waits up to the 120s cap, a "
					+ "non-negative integer waits up to that many milliseconds (clamped to the "
					+ "cap); any other value is rejected with 400. If the job finishes within "
					+ "the window the finished record is returned directly (200); otherwise the "
					+ "current record is returned (201) and the caller "
					+ "continues polling. This contract applies uniformly, including to "
					+ "meta-operations: e.g. grid:job-result itself returns a local job that "
					+ "completes with the remote job's result (it supports an op-level timeout "
					+ "input for bounded waits). Pass private: true (body field) for a "
					+ "PRIVATE memory-only job (#192): never persisted, absent from the "
					+ "job index, no recovery, gone on venue restart — use wait to "
					+ "collect the result, since a completed private job is immediately "
					+ "forgotten. Requires enablePrivateJobs in the venue config; a "
					+ "private request against a venue without it fails, never silently "
					+ "downgrades to a persisted job.",
			queryParams = {
					@OpenApiParam(name = "wait", example = "true",
							description = "Synchronous invoke: 'true' waits up to the 120s cap; a non-negative integer waits up to that many milliseconds (clamped). May also be passed as a body field; any other value is a 400.")
			},
			requestBody = @OpenApiRequestBody(
					description = "Invoke request",
					content= @OpenApiContent(
							type = "application/json" ,
							from = InvokeRequest.class,
							exampleObjects = {
								@OpenApiExampleProperty(name = "operation", value = "random"),
								@OpenApiExampleProperty(name = "input",
										objects = {
												@OpenApiExampleProperty(name = "length", value = "8")
										})
							}
					)),
			operationId = CoviaAPI.INVOKE,
			responses = {
					@OpenApiResponse(
							status = "201",
							description = "Operation invoked asynchronously: a job status record is returned and the Location header names the job to poll. Job ID can be any string, but by convention 32 characters hex.",
							content = {
								@OpenApiContent(
										type = "application/json",
										from = InvokeResult.class) }),
					@OpenApiResponse(
							status = "200",
							description = "With wait=true: the job completed within the wait window and the finished record (including output) is returned directly.",
							content = {
								@OpenApiContent(
										type = "application/json",
										from = InvokeResult.class) })
					})
	protected void invokeOperation(Context ctx) {
		// A malformed body is the caller's error: 400, never the generic 500.
		ACell req;
		try {
			req = JSON.parseJSON5(ctx.body());
		} catch (Exception e) {
			buildError(ctx, 400, "Request body is not valid JSON: " + e.getMessage());
			return;
		}

		AString op=RT.ensureString(RT.getIn(req, "operation"));
		if (op==null) {
			this.buildError(ctx, 400, "Invoke request requires an 'operation' parameter as a String");
			return;
		}
		ACell input=RT.getIn(req, "input");
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		// Attach transport UCAN authority — proofs (cross-user grants) and the
		// self-attenuation ceiling (#131) — from both channels: the `ucans`
		// envelope array and an `Authorization: Bearer <ucan-jwt>` (IETF
		// UCAN-HTTP) stashed by AuthMiddleware as UCAN_BEARER_ATTR.
		AVector<ACell> ucans = RT.getIn(req, "ucans");
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer, ucans);

		try {
			// Wait window: `wait` (query param or body field) is boolean or an
			// integer number of milliseconds — true = the full 120s window,
			// an integer = that many ms (clamped to the cap). Parsed BEFORE the
			// operation is invoked so a malformed value rejects with 400 without
			// creating a job. See parseWaitMs.
			long waitMs = parseWaitMs(ctx.queryParam("wait"), RT.getIn(req, "wait"));
			// Private (memory-only) job (#192): never persisted; requires
			// enablePrivateJobs on the venue (else the invoke fails loudly).
			boolean privateJob = CVMBool.TRUE.equals(RT.getIn(req, "private"));

			Job job=engine().jobs().invokeOperation(op,input,rctx,privateJob);
			if (job==null) {
				buildError(ctx,404,"Operation does not exist");
				return;
			}

			if (waitMs > 0) {
				try {
					job.awaitResult(waitMs);
				} catch (Exception e) {
					// Timeout or failure — return current state
				}
			}

			this.buildResult(ctx, waitMs > 0 && job.isComplete() ? 200 : 201, job.getData());
			ctx.header("Location",ROUTE+"jobs/"+job.getID().toHexString());
		} catch (AuthException e) {
			this.buildError(ctx, 403, e.getMessage());
		} catch (covia.exception.RemoteFetchException e) {
			// Resolving the operation required fetching a definition from another
			// venue, and that upstream fetch failed — a gateway error, not "the
			// operation does not exist" (#174). The message names the venue.
			this.buildError(ctx, 502, e.getMessage());
		} catch (IllegalArgumentException | IllegalStateException e) {
			this.buildError(ctx, 400, "Error invoking operation: "+e.getClass().getSimpleName()+":"+e.getMessage());
			return;
		} catch (Exception e) {
			this.buildError(ctx, 500, "Unexpected failure invoking operation: "+e);
			log.warn("Unexpected exception handling client invoke",e);
		}
	}

	/** Max wait window for a synchronous invoke (ms). A waiting request holds a
	 *  server thread, so the cap is a resource guard, not a convenience — clients
	 *  wanting longer waits poll {@code /jobs/{id}} or subscribe via SSE. */
	static final long MAX_WAIT_MS = 120_000;

	/**
	 * Parses the invoke {@code wait} parameter, following the same convention as
	 * the op-level {@code wait} on {@code agent:request}/{@code agent:trigger}:
	 * absent/false = 0 (asynchronous, the default); boolean {@code true} = the
	 * full {@link #MAX_WAIT_MS} window; a non-negative integer = that many
	 * milliseconds, clamped to the cap. Accepted from the query string
	 * ({@code ?wait=true}, {@code ?wait=5000}) or the request body
	 * ({@code "wait": true}, {@code "wait": 5000}; boolean/numeric strings
	 * accepted for JSON-over-HTTP clients). Anything else — negative numbers,
	 * non-numeric strings, other types — is a malformed request and throws;
	 * bad inputs are rejected, never silently treated as absent.
	 *
	 * @param queryParam Raw {@code wait} query parameter (may be null)
	 * @param bodyField {@code wait} field from the request body (may be null)
	 * @return Wait window in ms; 0 for the default asynchronous behaviour
	 * @throws IllegalArgumentException if {@code wait} is present but malformed
	 */
	static long parseWaitMs(String queryParam, ACell bodyField) {
		if (queryParam != null) return parseWaitToken(queryParam.trim());
		if (bodyField == null) return 0;
		if (convex.core.data.prim.CVMBool.TRUE.equals(bodyField)) return MAX_WAIT_MS;
		if (convex.core.data.prim.CVMBool.FALSE.equals(bodyField)) return 0;
		if (bodyField instanceof convex.core.data.prim.CVMLong l) return clampWait(l.longValue());
		if (bodyField instanceof AString s) return parseWaitToken(s.toString().trim());
		throw new IllegalArgumentException(
			"Invalid wait value: expected boolean or milliseconds, got " + bodyField.getClass().getSimpleName());
	}

	/** Parses a textual {@code wait} token: {@code true}/{@code false} or a
	 *  non-negative integer (ms). Anything else throws. */
	private static long parseWaitToken(String token) {
		if ("true".equals(token)) return MAX_WAIT_MS;
		if ("false".equals(token)) return 0;
		try {
			return clampWait(Long.parseLong(token));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
				"Invalid wait value: expected true, false or milliseconds, got '" + token + "'");
		}
	}

	private static long clampWait(long ms) {
		if (ms < 0) throw new IllegalArgumentException("Invalid wait value: negative milliseconds " + ms);
		return Math.min(ms, MAX_WAIT_MS);
	}

	@OpenApi(path = ROUTE + "jobs/{id}",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "Get the current Covia job status.",
			operationId = CoviaAPI.GET_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Job ID, as created by invoke request.", 
							required = true, 
							type = String.class, 
							example = "0x12345678123456781234567812345678") })	
	protected void getJobStatus(Context ctx) {
		Blob id=Blob.parse(ctx.pathParam("id"));
		if (id==null) {
			buildError(ctx,400,"Job request requires a job ID as a valid hex string");
			return;
		}
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		try {
			AMap<AString,ACell> status=engine().jobs().getJobData(id, rctx);
			if (status==null) {
				buildError(ctx,404,"Job not found: "+id);
				return;
			}
			buildResult(ctx,200,status);
		} catch (AuthException e) {
			buildError(ctx,403,e.getMessage());
		} catch (Exception e) {
			buildError(ctx,404,"Job not found: "+id);
		}
	}
	
	@OpenApi(path = ROUTE + "jobs/{id}",
			methods = HttpMethod.POST,
			tags = { "Covia"},
			summary = "Send a message to a running job. Returns 202 Accepted once the message is queued.",
			operationId = CoviaAPI.SEND_MESSAGE,
			pathParams = {
					@OpenApiParam(
							name = "id",
							description = "Job ID, as created by invoke request.",
							required = true,
							type = String.class,
							example = "0x12345678123456781234567812345678") },
			requestBody = @OpenApiRequestBody(
					description = "Message content (arbitrary JSON)",
					content= @OpenApiContent(type = "application/json", from = Object.class)),
			responses = {
					@OpenApiResponse(status = "202", description = "Message accepted and queued"),
					@OpenApiResponse(status = "404", description = "Job not found"),
					@OpenApiResponse(status = "409", description = "Job is in terminal state")
					})
	protected void sendMessage(Context ctx) {
		Blob id = Blob.parse(ctx.pathParam("id"));
		// A malformed body is the caller's error: 400, never the generic 500.
		ACell message;
		try {
			message = JSON.parseJSON5(ctx.body());
		} catch (Exception e) {
			buildError(ctx, 400, "Request body is not valid JSON: " + e.getMessage());
			return;
		}
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> msgMap = (message instanceof AMap)
				? (AMap<AString, ACell>) message
				: Maps.of(Fields.CONTENT, message);

		try {
			int depth = engine().jobs().deliverMessage(id, msgMap, rctx);
			Map<String, Object> response = new HashMap<>();
			response.put("status", "queued");
			response.put("queueDepth", depth);
			buildResult(ctx, 202, response);
		} catch (AuthException e) {
			buildError(ctx, 403, e.getMessage());
		} catch (IllegalArgumentException e) {
			buildError(ctx, 404, e.getMessage());
		} catch (IllegalStateException e) {
			buildError(ctx, 409, e.getMessage());
		}
	}

	@OpenApi(path = ROUTE + "jobs/{id}/cancel", 
			methods = HttpMethod.PUT, 
			tags = { "Covia"},
			summary = "Cancels a job.", 
			operationId = CoviaAPI.CANCEL_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Job ID, as created by invoke request.", 
							required = true, 
							type = String.class, 
							example = "0x12345678123456781234567812345678") })	
	protected void cancelJob(Context ctx) {
		Blob id=Blob.parse(ctx.pathParam("id"));
		if (id==null) {
			buildError(ctx,400,"Job cancellation request requires a job ID as a valid hex string");
			return;
		}
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		try {
			AMap<AString, ACell> status = engine().jobs().cancelJob(id, rctx);
			if (status!=null) {
				buildResult(ctx,status);
				ctx.status(200);
			} else {
				ctx.status(404);
			}
		} catch (AuthException e) {
			buildError(ctx, 403, e.getMessage());
		}
	}
	
	@OpenApi(path = ROUTE + "jobs/{id}/pause",
			methods = HttpMethod.PUT,
			tags = { "Covia"},
			summary = "Pauses a running job.",
			operationId = CoviaAPI.PAUSE_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id",
							description = "Job ID, as created by invoke request.",
							required = true,
							type = String.class,
							example = "0x12345678123456781234567812345678") })
	protected void pauseJob(Context ctx) {
		Blob id=Blob.parse(ctx.pathParam("id"));
		if (id==null) {
			buildError(ctx,400,"Pause request requires a job ID");
			return;
		}
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		try {
			AMap<AString, ACell> status = engine().jobs().pauseJob(id, rctx);
			if (status!=null) {
				buildResult(ctx,status);
				ctx.status(200);
			} else {
				ctx.status(404);
			}
		} catch (AuthException e) {
			buildError(ctx, 403, e.getMessage());
		} catch (IllegalStateException e) {
			buildError(ctx, 409, e.getMessage());
		}
	}

	@OpenApi(path = ROUTE + "jobs/{id}/resume",
			methods = HttpMethod.PUT,
			tags = { "Covia"},
			summary = "Resumes a paused job.",
			operationId = CoviaAPI.RESUME_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id",
							description = "Job ID, as created by invoke request.",
							required = true,
							type = String.class,
							example = "0x12345678123456781234567812345678") })
	protected void resumeJob(Context ctx) {
		Blob id=Blob.parse(ctx.pathParam("id"));
		if (id==null) {
			buildError(ctx,400,"Resume request requires a job ID");
			return;
		}
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		try {
			AMap<AString, ACell> status = engine().jobs().resumeJob(id, rctx);
			if (status!=null) {
				buildResult(ctx,status);
				ctx.status(200);
			} else {
				ctx.status(404);
			}
		} catch (AuthException e) {
			buildError(ctx, 403, e.getMessage());
		} catch (IllegalStateException e) {
			buildError(ctx, 409, e.getMessage());
		}
	}

	@OpenApi(path = ROUTE + "jobs/{id}/delete",
			methods = HttpMethod.PUT,
			tags = { "Covia"},
			summary = "Deletes a job.",
			operationId = CoviaAPI.DELETE_JOB,
			pathParams = {
					@OpenApiParam(
							name = "id", 
							description = "Job ID, as created by invoke request.", 
							required = true, 
							type = String.class, 
							example = "0x12345678123456781234567812345678") })	
	protected void deleteJob(Context ctx) {
		Blob id=Blob.parse(ctx.pathParam("id"));
		if (id==null) {
			buildError(ctx,400,"Job deletion request requires a job ID as a valid hex string");
			return;
		}
		RequestContext rctx = AuthMiddleware.callerContext(ctx);

		try {
			boolean deleted=engine().jobs().deleteJob(id, rctx);
			if (deleted) {
				ctx.status(200);
			} else {
				ctx.status(404);
			}
		} catch (AuthException e) {
			buildError(ctx, 403, e.getMessage());
		}
	}
	
	/**
	 * Documentation holder for the SSE route registered via
	 * {@code routes.sse(ROUTE + "jobs/<id>/sse", ...)} in {@code addRoutes} —
	 * the annotation processor only scans {@code @OpenApi} annotations, and the
	 * SSE handler is not an annotatable method. Never invoked.
	 */
	@OpenApi(path = ROUTE + "jobs/{id}/sse",
			methods = HttpMethod.GET,
			tags = { "Covia" },
			summary = "Server-sent events stream of job status updates",
			description = "Streams the job's status record as SSE frames until the job reaches a "
					+ "terminal state. Poll-free alternative to GET /jobs/{id} for watching a running job.",
			pathParams = { @OpenApiParam(name = "id", description = "Job ID (hex)") },
			operationId = "jobSse")
	@SuppressWarnings("unused")
	private static void docJobSse() {}

	@OpenApi(path = ROUTE + "jobs",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "Get Covia jobs.")
	protected void getJobs(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		try {
			Index<Blob, ACell> jobs = engine().jobs().getJobs(rctx);
			// Return job IDs as a list
			long n = jobs.count();
			ArrayList<Object> result = new ArrayList<>((int) n);
			for (long i = 0; i < n; i++) {
				result.add(jobs.entryAt(i).getKey().toHexString());
			}
			buildResult(ctx, result);
		} catch (AuthException e) {
			buildError(ctx, 403, e.getMessage());
		}
	}

	@OpenApi(path = ROUTE + "operations",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "List all named operations available on this venue.",
			operationId = "getOperations")
	protected void getOperations(Context ctx) {
		// Enumerate all primitives from each adapter's catalog entries.
		// This is the canonical "what's in /v/ops/ and /v/test/ops/" list.
		ArrayList<Object> result = new ArrayList<>();
		for (var adapter : engine().getAdapterNames()) {
			AAdapter aa = engine().getAdapter(adapter);
			if (aa == null) continue;
			for (var entry : aa.pendingCatalogEntries.entrySet()) {
				String catalogPath = entry.getKey();
				Hash assetHash = entry.getValue();
				Map<String, Object> opInfo = new HashMap<>();
				opInfo.put("name", catalogPath);
				opInfo.put("asset", assetHash.toHexString());

				Asset asset = engine().getAsset(assetHash);
				if (asset != null) {
					AMap<AString, ACell> meta = asset.meta();
					ACell desc = meta.get(Fields.DESCRIPTION);
					if (desc != null) opInfo.put("description", desc.toString());
					ACell opMeta = meta.get(Fields.OPERATION);
					if (opMeta instanceof AMap<?,?> opMap) {
						ACell input = ((AMap<AString,ACell>) opMap).get(Fields.INPUT);
						if (input != null) opInfo.put("input", input);
						ACell output = ((AMap<AString,ACell>) opMap).get(Fields.OUTPUT);
						if (output != null) opInfo.put("output", output);
					}
				}
				result.add(opInfo);
			}
		}
		buildResult(ctx, 200, result);
	}

	@OpenApi(path = ROUTE + "operations/{name}",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "Get details of a named operation.",
			operationId = "getOperation",
			pathParams = {
					@OpenApiParam(
							name = "name",
							description = "Operation name (e.g. test:echo).",
							required = true,
							type = String.class,
							example = "v/test/ops/echo") })
	protected void getOperation(Context ctx) {
		String name = ctx.pathParam("name");
		try {
			Asset asset = venue.resolveAsset(name);
			if (asset == null) {
				buildError(ctx, 404, "Operation not found: " + name);
				return;
			}

			Map<String, Object> result = new HashMap<>();
			result.put("name", name);
			result.put("asset", asset.getID().toHexString());

			AMap<AString, ACell> meta = asset.meta();
			ACell desc = meta.get(Fields.DESCRIPTION);
			if (desc != null) result.put("description", desc.toString());
			ACell opMeta = meta.get(Fields.OPERATION);
			if (opMeta instanceof AMap<?,?> opMap) {
				ACell input = ((AMap<AString,ACell>) opMap).get(Fields.INPUT);
				if (input != null) result.put("input", input);
				ACell output = ((AMap<AString,ACell>) opMap).get(Fields.OUTPUT);
				if (output != null) result.put("output", output);
			}
			buildResult(ctx, 200, result);
		} catch (IOException e) {
			buildError(ctx, 500, "Error resolving operation: " + e.getMessage());
		}
	}

	// ========== Lattice value reads (#177) ==========

	@OpenApi(path = ROUTE + "values/read", methods = HttpMethod.GET, tags = { "Values" },
			summary = "Read the literal value at a lattice path (job-free)", operationId = "valueRead",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "w/notes",
							description = "Lattice path to read, e.g. 'w/notes', 'g/my-agent/status', 'v/info/adapters'."),
					@OpenApiParam(name = "maxSize", type = Long.class, example = "1000000",
							description = "Byte guard (default 1000000): a larger value is withheld and the response carries truncated:true with its size.")
			})
	protected void getValueRead(Context ctx) { handleValueRoute(ctx, "read"); }

	@OpenApi(path = ROUTE + "values/list", methods = HttpMethod.GET, tags = { "Values" },
			summary = "List the keys / structure of a lattice node (job-free)", operationId = "valueList",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "g",
							description = "Lattice path of the node to list."),
					@OpenApiParam(name = "limit", type = Long.class, example = "100",
							description = "Maximum keys to return (default 1000). Keyed nodes only."),
					@OpenApiParam(name = "offset", type = Long.class, example = "0",
							description = "Keys to skip (default 0). Keyed nodes only."),
					@OpenApiParam(name = "fields", example = "status,meta/updated",
							description = "Field projection (#191): comma-separated subpaths to read from each listed child. "
									+ "Adds a 'values' map — per key on the page, each field's read result {exists, value, truncated?}. "
									+ "Max 16 fields; applies after limit/offset; requires a keyed node (else 400). Output shape only — no filtering or ordering."),
					@OpenApiParam(name = "maxSize", type = Long.class,
							description = "Per-projected-field byte guard when 'fields' is given (default 1000000).")
			})
	protected void getValueList(Context ctx) { handleValueRoute(ctx, "list"); }

	@OpenApi(path = ROUTE + "values/slice", methods = HttpMethod.GET, tags = { "Values" },
			summary = "Read a paginated slice of a lattice sequence (job-free)", operationId = "valueSlice",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "w/events",
							description = "Lattice path of the vector/sequence to slice."),
					@OpenApiParam(name = "offset", type = Long.class, example = "0",
							description = "Starting element index (default 0)."),
					@OpenApiParam(name = "limit", type = Long.class, example = "100",
							description = "Maximum elements to return (default 100).")
			})
	protected void getValueSlice(Context ctx) { handleValueRoute(ctx, "slice"); }

	@OpenApi(path = ROUTE + "values/inspect", methods = HttpMethod.GET, tags = { "Values" },
			summary = "Budget-controlled JSON5 render of a lattice value (job-free)", operationId = "valueInspect",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "g/my-agent",
							description = "Lattice path to render."),
					@OpenApiParam(name = "budget", type = Long.class, example = "500",
							description = "Render budget in bytes (default 500): shape and sample values within the budget."),
					@OpenApiParam(name = "compact", type = Boolean.class,
							description = "Compact rendering (no whitespace).")
			})
	protected void getValueInspect(Context ctx) { handleValueRoute(ctx, "inspect"); }

	@OpenApi(path = ROUTE + "values/aggregate", methods = HttpMethod.GET, tags = { "Values" },
			summary = "Count entries at a depth, optionally grouped by a field (job-free)", operationId = "valueAggregate",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "j",
							description = "Lattice path of the collection to aggregate over."),
					@OpenApiParam(name = "depth", type = Long.class, example = "1",
							description = "Get-steps below the path to count at (default 1)."),
					@OpenApiParam(name = "groupBy", example = "status",
							description = "Optional field (relative subpath) to partition the count by; adds a 'groups' breakdown.")
			})
	protected void getValueAggregate(Context ctx) { handleValueRoute(ctx, "aggregate"); }

	@OpenApi(path = ROUTE + "values/count", methods = HttpMethod.GET, tags = { "Values" },
			summary = "Fast-path count of entries at a depth (job-free)", operationId = "valueCount",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "j",
							description = "Lattice path of the collection to count."),
					@OpenApiParam(name = "depth", type = Long.class, example = "1",
							description = "Get-steps below the path to count at (default 1).")
			})
	protected void getValueCount(Context ctx) { handleValueRoute(ctx, "count"); }

	/**
	 * Shared handler for the job-free {@code /values/*} read routes. Builds the
	 * caller context (identity + transport UCAN) exactly like the other GET reads,
	 * assembles the accessor input from query parameters, and dispatches to the
	 * matching {@link CoviaAdapter} read accessor — with <b>no Job created or
	 * persisted</b> (the fix for #177). Reads are local-only, so there is no
	 * remote-fetch surface here; capability enforcement is unchanged from the op path.
	 *
	 * @param op one of {@code read}, {@code list}, {@code slice}, {@code inspect}
	 */
	private void handleValueRoute(Context ctx, String op) {
		String path = ctx.queryParam("path");
		if (path == null || path.isEmpty()) {
			buildError(ctx, 400, "Missing 'path' query parameter");
			return;
		}
		// Execution-scoped virtual namespaces (t/, n/, c/) need a job/agent/session
		// context that a plain GET does not carry.
		if (isExecutionScopedNamespace(path)) {
			buildError(ctx, 400, "Namespace not readable via the values API: " + firstSegment(path) + "/");
			return;
		}

		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer, null);

		AMap<AString, ACell> input;
		try {
			input = buildValueInput(ctx, path);
		} catch (NumberFormatException e) {
			buildError(ctx, 400, "Invalid integer query parameter: " + e.getMessage());
			return;
		}

		CoviaAdapter covia = (CoviaAdapter) engine().getAdapter("covia");
		try {
			ACell result = switch (op) {
				case "read"      -> covia.handleRead(rctx, input);
				case "list"      -> covia.handleList(rctx, input);
				case "slice"     -> covia.handleSlice(rctx, input);
				case "inspect"   -> covia.handleInspect(rctx, input);
				case "aggregate" -> covia.handleAggregate(rctx, input);
				// The count fast path is aggregate with no grouping.
				case "count"     -> covia.handleAggregate(rctx, input.dissoc(Strings.intern("groupBy")));
				default -> throw new IllegalArgumentException("Unknown values op: " + op);
			};
			// Content-addressed conditional read — only for `read`, where a path
			// resolves to a single value whose CAD3 hash is a free, exact validator.
			// Not applied to list/slice/aggregate/inspect: their bodies depend on
			// query params, and we deliberately do not hash params or computed
			// results. Truncated/absent reads carry no value, so no ETag (and thus
			// no wrong-304 when maxSize varies).
			if ("read".equals(op)) {
				// ETag any present value — including a stored null, whose canonical
				// CAD3 hash is Hash.NULL_HASH (Cells.getHash(null)). An absent path
				// (exists:false) and a truncated read (value withheld) carry no ETag.
				boolean exists = CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("exists")));
				boolean truncated = CVMBool.TRUE.equals(RT.getIn(result, Strings.intern("truncated")));
				if (exists && !truncated) {
					ACell value = RT.getIn(result, Strings.intern("value"));
					String etag = "\"0x" + Cells.getHash(value).toHexString() + "\"";
					ctx.header("ETag", etag);
					if (etag.equals(ctx.header("If-None-Match"))) {
						ctx.status(304);
						return;
					}
				}
			}
			buildResult(ctx, 200, result);
		} catch (AuthException e) {
			buildError(ctx, 403, e.getMessage());
		} catch (IllegalArgumentException e) {
			// Bad request parameters (e.g. a fields projection on a non-keyed
			// node, or over the field cap) — the caller's error, not the venue's.
			buildError(ctx, 400, e.getMessage());
		} catch (RuntimeException e) {
			buildError(ctx, 500, "Read failed: " + e.getMessage());
		}
	}

	/** Builds the CoviaAdapter read-accessor input map from the request query params. */
	private static AMap<AString, ACell> buildValueInput(Context ctx, String path) {
		AMap<AString, ACell> m = Maps.empty();
		m = m.assoc(Fields.PATH, Strings.create(path));
		m = putLongParam(m, ctx, "maxSize");
		m = putLongParam(m, ctx, "limit");
		m = putLongParam(m, ctx, "offset");
		m = putLongParam(m, ctx, "budget");
		m = putLongParam(m, ctx, "depth");
		String compact = ctx.queryParam("compact");
		if (compact != null) {
			m = m.assoc(Strings.intern("compact"),
					Boolean.parseBoolean(compact) ? CVMBool.TRUE : CVMBool.FALSE);
		}
		String groupBy = ctx.queryParam("groupBy");
		if (groupBy != null && !groupBy.isBlank()) {
			m = m.assoc(Strings.intern("groupBy"), Strings.create(groupBy));
		}
		// Field projection on list (#191): comma-separated subpaths, e.g.
		// fields=status,meta/updated — parsed and capped by the accessor.
		String fields = ctx.queryParam("fields");
		if (fields != null && !fields.isBlank()) {
			m = m.assoc(Strings.intern("fields"), Strings.create(fields));
		}
		return m;
	}

	private static AMap<AString, ACell> putLongParam(AMap<AString, ACell> m, Context ctx, String name) {
		String v = ctx.queryParam(name);
		if (v == null || v.isBlank()) return m;
		return m.assoc(Strings.intern(name), CVMLong.create(Long.parseLong(v.trim())));
	}

	/** First path segment (namespace prefix) before the first {@code /}. */
	private static String firstSegment(String path) {
		int slash = path.indexOf('/');
		return (slash < 0) ? path : path.substring(0, slash);
	}

	/**
	 * True for the job/agent/session-scoped virtual namespaces ({@code t/},
	 * {@code n/}, {@code c/}) that are only meaningful inside a running job/agent.
	 */
	private static boolean isExecutionScopedNamespace(String path) {
		String seg = firstSegment(path);
		return seg.equals("t") || seg.equals("n") || seg.equals("c");
	}

	// ========== Agent endpoints ==========

	@OpenApi(path = ROUTE + "agents",
			methods = HttpMethod.GET,
			tags = { "Covia" },
			summary = "List the authenticated caller's agents (job-free, #180)",
			operationId = "getAgents",
			queryParams = {
					@OpenApiParam(name = "status", type = Boolean.class,
							description = "true → the status-annotated form ({agentId, status, tasks, error?}) instead of bare agent ids."),
					@OpenApiParam(name = "includeTerminated", type = Boolean.class,
							description = "true → include terminated agents (hidden by default).")
			})
	protected void getAgents(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		if (rctx.getCallerDID() == null) {
			buildError(ctx, 401, "Authentication required");
			return;
		}
		boolean annotated = "true".equals(ctx.queryParam("status"));
		boolean includeTerminated = "true".equals(ctx.queryParam("includeTerminated"));
		AgentAdapter agent = (AgentAdapter) engine().getAdapter("agent");
		buildResult(ctx, 200, agent.listAgents(rctx, includeTerminated, annotated));
	}

	@OpenApi(path = ROUTE + "agents/{id}",
			methods = HttpMethod.GET,
			tags = { "Covia" },
			summary = "Get one of the authenticated caller's agents — the agent:info payload (job-free, #180).",
			operationId = "getAgentInfo",
			pathParams = { @OpenApiParam(name = "id", description = "Agent id") })
	protected void getAgentInfo(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		if (rctx.getCallerDID() == null) {
			buildError(ctx, 401, "Authentication required");
			return;
		}
		String id = ctx.pathParam("id");
		AgentAdapter agent = (AgentAdapter) engine().getAdapter("agent");
		AMap<AString, ACell> info = agent.agentInfo(rctx, Strings.create(id));
		if (info == null) {
			buildError(ctx, 404, "Agent not found: " + id);
			return;
		}
		buildResult(ctx, 200, info);
	}

	// ========== Secret endpoints ==========

	@OpenApi(path = ROUTE + "secrets",
			methods = HttpMethod.GET,
			tags = { "Secrets"},
			summary = "List secret names for the authenticated caller. Returns names only, never values.",
			operationId = "listSecrets")
	protected void listSecrets(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AString callerDID = rctx.getCallerDID();
		if (callerDID == null) {
			buildError(ctx, 401, "Authentication required");
			return;
		}

		User user = engine().getVenueState().users().get(callerDID);
		if (user == null) {
			buildResult(ctx, 200, Maps.of(Fields.ITEMS, Strings.create("[]"), Fields.TOTAL, 0));
			return;
		}

		AVector<AString> names = user.secrets().list();
		ArrayList<Object> items = new ArrayList<>();
		for (long i = 0; i < names.count(); i++) {
			items.add(names.get(i).toString());
		}
		Map<String, Object> result = new HashMap<>();
		result.put("items", items);
		result.put("total", items.size());
		buildResult(ctx, 200, result);
	}

	@OpenApi(path = ROUTE + "secrets/{name}",
			methods = HttpMethod.PUT,
			tags = { "Secrets"},
			summary = "Store an encrypted secret under the caller's namespace.",
			operationId = "putSecret",
			pathParams = {
					@OpenApiParam(
							name = "name",
							description = "Secret name (e.g. openai-key).",
							required = true,
							type = String.class) },
			requestBody = @OpenApiRequestBody(
					description = "Secret value",
					content= @OpenApiContent(type = "application/json", from = Object.class)))
	protected void putSecret(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		if (rctx.getCallerDID() == null) {
			buildError(ctx, 401, "Authentication required");
			return;
		}

		String name = ctx.pathParam("name");
		// A malformed body is the caller's error: 400, never the generic 500.
		ACell body;
		try {
			body = JSON.parseJSON5(ctx.body());
		} catch (Exception e) {
			buildError(ctx, 400, "Request body is not valid JSON: " + e.getMessage());
			return;
		}
		ACell value = RT.getIn(body, "value");
		if (!(value instanceof AString)) {
			buildError(ctx, 400, "Request body must contain a 'value' string field");
			return;
		}

		ACell input = Maps.of("name", name, "value", value);
		Job job = engine().jobs().invokeOperation(Strings.create("v/ops/secret/set"), input, rctx);
		ACell result = job.awaitResult();
		if (result == null) {
			buildError(ctx, 500, "Failed to store secret");
			return;
		}
		buildResult(ctx, 200, result);
	}

	@OpenApi(path = ROUTE + "secrets/{name}",
			methods = HttpMethod.DELETE,
			tags = { "Secrets"},
			summary = "Delete a secret from the caller's namespace.",
			operationId = "deleteSecret",
			pathParams = {
					@OpenApiParam(
							name = "name",
							description = "Secret name to delete.",
							required = true,
							type = String.class) })
	protected void deleteSecret(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AString callerDID = rctx.getCallerDID();
		if (callerDID == null) {
			buildError(ctx, 401, "Authentication required");
			return;
		}

		String name = ctx.pathParam("name");
		User user = engine().getVenueState().users().get(callerDID);
		if (user == null) {
			buildError(ctx, 404, "Secret not found");
			return;
		}

		AString secretName = Strings.create(name);
		if (!user.secrets().exists(secretName)) {
			buildError(ctx, 404, "Secret not found: " + name);
			return;
		}
		user.secrets().delete(secretName);
		ctx.status(200);
	}

	@OpenApi(path = "/.well-known/did.json",
			methods = HttpMethod.GET,
			tags = { "DID"},
			summary = "Get the DID document for this venue",
			operationId = "getDIDDocument",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "DID document returned")
					})	
	protected void getDIDDocument(Context ctx) {
		// Create a complete DID document structure
		AMap<AString, ACell> didDocument = engine().getDIDDocument(getExternalBaseUrl(ctx,ROUTE));

		// Set content type to application/did+json
		ctx.header("Content-Type", "application/did+json");

		// Return the DID document
		buildResult(ctx, 200, didDocument);
	}
	
	@OpenApi(path = "/a/{id}/did.json", 
			methods = HttpMethod.GET, 
			tags = { "DID"},
			summary = "Get the DID document for an asset", 
			operationId = "getAssetDIDDocument",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "DID document returned")
					})	
	protected void getAssetDIDDocument(Context ctx) {
		String id=ctx.pathParam("id");

		AString baseDID=engine().getDIDString();
		AString did=baseDID.append(Strings.create("/a/"+id));
		
		AMap<AString, ACell> didDocument = Maps.of(
				"@context", "https://www.w3.org/ns/did/v1",
				Fields.ID,did);

		// Set content type to application/did+json
		ctx.header("Content-Type", "application/did+json");
		
		// Return the DID document
		buildResult(ctx, 200, didDocument);
	}
}
