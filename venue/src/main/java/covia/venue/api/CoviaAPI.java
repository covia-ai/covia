package covia.venue.api;

import static covia.venue.server.VenueRouteFeature.COVIA_API;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ABlob;
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
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Venue;
import covia.venue.api.model.ErrorResponse;
import covia.venue.api.model.InvokeRequest;
import covia.venue.api.model.InvokeResult;
import covia.venue.AssetStore;
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
	public static final String RUN = "runOperation";

	public static final String GET_ASSET = "getAsset";
	public static final String GET_ASSETS = "getAssets";

	public static final String ADD_ASSET="addAsset";

	private static final String GET_CONTENT = "getContent";
	private static final String GET_CONTENT_REF = "getContentByRef";

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
		routes.get(ROUTE+"status", this::getStatus, COVIA_API);
		// <id> matches slashes, so the asset metadata route accepts any lattice
		// address (a/<hash>, w/…, o/…, <DID>/…) as well as a bare hash. Content
		// puts its selector BEFORE the ref (assets/content/<ref>) so the
		// variable-length ref is always the tail wildcard: "content" is not a
		// ref namespace prefix, so the selector can never collide with a valid
		// metadata ref — unlike a trailing /content, which any workspace path
		// can end in (#368). The single-segment {id}/content route survives for
		// hash-form compatibility (covered by CoviaAssetRefTest).
		routes.get(ROUTE+"assets/content/<id>", this::getContentByRef, COVIA_API);
		routes.get(ROUTE+"assets/{id}/content", this::getContent, COVIA_API);
		// "mine" is never a valid asset ref (hash/DID/workspace path), so — same
		// trick as assets/content above — the static segment goes before the
		// <id> tail wildcard, which would otherwise swallow it as id="mine".
		routes.get(ROUTE+"assets/mine", this::getMyAssets, COVIA_API);
		routes.get(ROUTE+"assets/<id>", this::getAsset, COVIA_API);
		routes.put(ROUTE+"assets/{id}/content", this::putContent, COVIA_API);

		routes.get(ROUTE+"assets", this::getAssets, COVIA_API);
		routes.post(ROUTE+"assets", this::addAsset, COVIA_API);
		routes.post(ROUTE+"invoke", this::invokeOperation, COVIA_API);
		routes.post(ROUTE+"run", this::runOperation, COVIA_API);
		routes.get(ROUTE+"operations", this::getOperations, COVIA_API);
		routes.get(ROUTE+"operations/{name}", this::getOperation, COVIA_API);
		// Job ids are single-segment hex strings, so the job routes use the
		// segment matcher {id}: a greedy <id> on the GET route also matches
		// "…/sse", shadowing the SSE route and making job streaming
		// unreachable (#200).
		routes.get(ROUTE+"jobs/{id}", this::getJobStatus, COVIA_API);
		routes.post(ROUTE+"jobs/{id}", this::sendMessage, COVIA_API);
		routes.put(ROUTE+"jobs/{id}/cancel", this::cancelJob, COVIA_API);
		routes.put(ROUTE+"jobs/{id}/pause", this::pauseJob, COVIA_API);
		routes.put(ROUTE+"jobs/{id}/resume", this::resumeJob, COVIA_API);
		routes.put(ROUTE+"jobs/{id}/delete", this::deleteJob, COVIA_API);
		// Registered as a plain GET (not routes.sse): Javalin's SseHandler
		// silently no-ops into an empty 200 without an Accept:
		// text/event-stream header — the worst failure mode for an
		// integration surface (#222). The /sse path is unambiguous, so
		// jobSse defaults to streaming and 406s loudly on an explicit
		// non-SSE Accept.
		routes.get(ROUTE+"jobs/{id}/sse", this::jobSse, COVIA_API);
		routes.get(ROUTE+"jobs", this::getJobs, COVIA_API);

		// Job-free lattice value reads (#177) — synchronous, capability-checked,
		// no Job persisted. Shares CoviaAdapter's read accessors with covia:* ops.
		routes.get(ROUTE+"values/read", this::getValueRead, COVIA_API);
		routes.get(ROUTE+"values/list", this::getValueList, COVIA_API);
		routes.get(ROUTE+"values/slice", this::getValueSlice, COVIA_API);
		routes.get(ROUTE+"values/inspect", this::getValueInspect, COVIA_API);
		routes.get(ROUTE+"values/aggregate", this::getValueAggregate, COVIA_API);
		routes.get(ROUTE+"values/count", this::getValueCount, COVIA_API);

		// Agents — job-free reads (#180), the caller's own agents. Mirrors how
		// every other entity type is read via GET; no Job persisted.
		routes.get(ROUTE+"agents", this::getAgents, COVIA_API);
		routes.get(ROUTE+"agents/{id}", this::getAgentInfo, COVIA_API);

		// Secrets
		routes.get(ROUTE+"secrets", this::listSecrets, COVIA_API);
		routes.put(ROUTE+"secrets/{name}", this::putSecret, COVIA_API);
		routes.delete(ROUTE+"secrets/{name}", this::deleteSecret, COVIA_API);

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

		// Add the stats, plus the caller's own job count (#229) — anonymous
		// callers see the shared public identity's count.
		AMap<AString,ACell> stats = engine().getStats();
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		stats = stats.assoc(Strings.intern("userJobs"),
			RT.cvm(engine().jobs().getJobs(rctx).count()));
		result=result.assoc(STATS_FIELD,stats);

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

	@OpenApi(path = ROUTE + "assets/mine",
			methods = HttpMethod.GET,
			tags = { "Covia" },
			summary = "List the authenticated caller's own assets — the per-user a/ namespace "
					+ "populated by asset:store/asset:pin. Job-free (mirrors asset:list without "
					+ "invoking an operation).",
			operationId = "getMyAssets",
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
					description = "A JSON object with total, offset, limit, and an items array of {id, name, type, description} summaries.",
					content = {
						@OpenApiContent(
							type = "application/json",
							from = Object.class
						)
					}
				),
				@OpenApiResponse(
					status = "401",
					description = "Authentication required.",
					content = {
						@OpenApiContent(
							type = "application/json",
							from = ErrorResponse.class
						)
					}
				)
			})
	protected void getMyAssets(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AString callerDID = rctx.getCallerDID();
		if (callerDID == null) {
			buildError(ctx, 401, "Authentication required");
			return;
		}

		long offset;
		long limit;
		try {
			String off = ctx.queryParam("offset");
			offset = (off != null) ? Math.max(0, Long.parseLong(off)) : 0;
			String lim = ctx.queryParam("limit");
			if (lim != null) {
				limit = Long.parseLong(lim);
				if (limit < 0) throw new BadRequestResponse("Negative limit");
				if (limit > 1000) throw new BadRequestResponse("Too many assets requested: " + limit);
			} else {
				limit = 1000;
			}
		} catch (NumberFormatException e) {
			buildError(ctx, 400, "Invalid offset or limit");
			return;
		}

		try {
			// Same capability asset:list enforces (AssetAdapter.handleList) — read
			// authority on the caller's own namespace, not the venue-wide one.
			engine().requireAuthority(rctx, Strings.create(""), Abilities.ASSET_READ);
		} catch (AuthException e) {
			buildError(ctx, 403, "Not authorised to list your assets");
			return;
		}

		User user = engine().getVenueState().users().get(callerDID);
		@SuppressWarnings("unchecked")
		AMap<ABlob, AVector<?>> userAssets = (user != null) ? user.assets().getAll() : null;
		long rawTotal = (userAssets != null) ? userAssets.count() : 0;

		ArrayList<Object> items = new ArrayList<>();
		long matched = 0;
		long emitted = 0;
		if (userAssets != null) {
			for (long i = 0; i < rawTotal; i++) {
				var entry = userAssets.entryAt(i);
				if (entry == null) continue;
				@SuppressWarnings("unchecked")
				AVector<ACell> record = (AVector<ACell>) entry.getValue();
				AMap<AString, ACell> meta = RT.ensureMap(record.get(AssetStore.POS_META));
				if (meta == null) continue;

				matched++;
				if (matched <= offset) continue;
				if (emitted >= limit) continue;

				Hash h = Hash.wrap(entry.getKey().getBytes());
				Map<Object, Object> summary = new HashMap<>();
				summary.put(Fields.ID, h.toHexString());
				AString name = RT.ensureString(meta.get(Fields.NAME));
				summary.put(Fields.NAME, name != null ? name.toString() : null);
				AString type = RT.ensureString(meta.get(Fields.TYPE));
				summary.put(Fields.TYPE, type != null ? type.toString() : null);
				AString description = RT.ensureString(meta.get(Fields.DESCRIPTION));
				summary.put(Fields.DESCRIPTION, description != null ? description.toString() : null);
				items.add(summary);
				emitted++;
			}
		}

		Map<Object, Object> result = new HashMap<>();
		result.put(Fields.TOTAL, matched);
		result.put(Fields.OFFSET, offset);
		result.put(Fields.LIMIT, limit);
		result.put(Fields.ITEMS, items);
		buildResult(ctx, result);
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
			// Enforces the asset-store capability (consistent with the asset:store
			// operation, AssetAdapter.handleStore) — registering new metadata is a
			// write and must not be reachable by the default read-only public scope.
			RequestContext rctx = AuthMiddleware.callerContext(ctx);
			engine().requireAuthority(rctx, Strings.create(""), Abilities.ASSET_STORE);

			AString meta=Strings.fromStream(ctx.bodyInputStream());
			// A caller-created asset belongs to that caller's /a namespace. Venue
			// catalog assets are installed internally and addressed explicitly.
			Hash id=engine().storeUserAsset(meta, null, rctx);
			buildResult(ctx,201,id.toHexString());
			ctx.header("Location",ROUTE+"assets/"+id.toHexString());
		} catch (AuthException e) {
			buildError(ctx,403,"Not authorised to register asset metadata");
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
		if ("venue".equals(ctx.queryParam("namespace"))) {
			Hash hash = Hash.parse(ref);
			if (hash == null) {
				buildError(ctx,400,"Venue catalog lookup requires a bare asset hash");
				return;
			}
			try {
				Asset asset = venue.getAsset(hash);
				if (asset == null) {
					buildError(ctx,404,"Venue asset not found: "+ref);
					return;
				}
				ctx.header("ETag","\"0x"+hash.toHexString()+"\"");
				ctx.result(asset.getMetadata().toString());
				ctx.status(200);
			} catch (IOException e) {
				buildError(ctx,500,"Error retrieving venue asset: "+e.getMessage());
			}
			return;
		}

		serveAssetMeta(ctx, ref);
	}

	/**
	 * Serves the metadata for an asset reference (any lattice address form).
	 * The body of {@link #getAsset}, shared with the legacy content route's
	 * disambiguation path (a non-hash {@code {id}} there is really a metadata
	 * ref whose final segment is {@code content}, #368).
	 */
	private void serveAssetMeta(Context ctx, String ref) {
		// Caller identity + transport UCAN authority (a bearer UCAN supplies the
		// proof for cross-DID reads, per the IETF UCAN-HTTP convention).
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer, null, null, engine().didVerifier());

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
	 * @param ctx Request context (caller identity, grant scope, proofs)
	 * @return the resolved Asset, or null if the reference resolves to nothing
	 *         or to a non-asset value
	 */
	private Asset resolveAssetReference(String ref, RequestContext ctx) throws IOException {
		AString refStr = Strings.create(ref);
		engine().requireResourceAccess(ctx,refStr, Abilities.ASSET_READ);

		// Caller-relative content-addressed forms (bare hex and a/<hash>) — fetch
		// the stored record so the returned bytes are byte-identical to the legacy
		// assets/<hash> route, keeping content-addressed lookups self-verifying.
		Hash hash = parseCallerAssetId(ref);
		if (hash != null) {
			AString owner = engine().requireLocalAccess(ctx, refStr, Abilities.ASSET_READ);
			AVector<?> record = engine().getAssetRecord(hash, owner);
			if (record == null) return null;
			AString metadata = RT.ensureString(record.get(AssetStore.POS_JSON));
			return (metadata != null) ? Asset.create(hash, metadata) : null;
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
	 * Strict parse of a caller-relative content-addressed asset id: a bare hex
	 * CAD3 hash or {@code a/<hash>}. DID URLs and mutable lattice paths return
	 * null and are resolved with their explicit owner/path semantics.
	 */
	private static Hash parseCallerAssetId(String ref) {
		if (ref == null) return null;
		if (ref.startsWith("/a/")) return Hash.parse(ref.substring(3));
		if (ref.startsWith("a/")) return Hash.parse(ref.substring(2));
		if (ref.startsWith("did:")) return null;
		return Hash.parse(ref);
	}
	
	@OpenApi(path = ROUTE + "assets/{id}/content",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "Get the content of a Covia data asset by content-addressed hash. "
					+ "Legacy single-segment route: prefer assets/content/{id}, which accepts any asset reference.",
			deprecated = true,
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
		// {id} is single-segment, so only hash forms (bare hex, a/<hash>) ever
		// worked here. A non-hash id means this route's suffix grammar mis-ate a
		// metadata read for a ref whose final segment is "content" (e.g.
		// /assets/w/content is the metadata of w/content). Hash and path grammars
		// cannot collide, so the disambiguation is exact (#368).
		if (parseCallerAssetId(id)==null && !"venue".equals(ctx.queryParam("namespace"))) {
			serveAssetMeta(ctx, id+"/content");
			return;
		}
		serveContent(ctx, id);
	}

	@OpenApi(path = ROUTE + "assets/content/{id}",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "Get the content of a Covia data asset given any asset reference.",
			operationId = CoviaAPI.GET_CONTENT_REF,
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
							description = "Asset reference: a bare CAD3 hash, a content-addressed "
									+ "address (a/<hash>), a workspace/operation path (w/…, o/…), "
									+ "or a DID URL — every form the metadata route accepts.",
							required = true,
							type = String.class,
							example = "w/skills/reviewer") },
			responses = {
					@OpenApiResponse(
							status = "200",
							description = "Content returned")
					})
	protected void getContentByRef(Context ctx) {
		serveContent(ctx, ctx.pathParam("id"));
	}

	/**
	 * Serves asset content for any reference form: the canonical
	 * {@code assets/content/<ref>} route and the hash-form legacy route both
	 * land here. Hash-form refs read the caller's asset record directly
	 * (byte-identical to the historical route); every other lattice address
	 * resolves through the same universal resolver as the metadata route and
	 * serves whatever content the resolved value carries — inline, CAS blob,
	 * or provider-backed (DLFS), with a declared sha256 pin verified (#368).
	 */
	private void serveContent(Context ctx, String id) {
		try {
			if ("venue".equals(ctx.queryParam("namespace"))) {
				Hash venueAssetID=Hash.parse(id);
				if (venueAssetID==null) {
					buildError(ctx,400,"Venue catalog lookup requires a bare asset hash");
					return;
				}
				Asset asset=venue.getAsset(venueAssetID);
				if (asset==null) {
					buildError(ctx,404,"Venue asset not found: "+id);
					return;
				}
				AMap<AString,ACell> meta=asset.meta();
				if (!meta.containsKey(Fields.CONTENT)) {
					buildError(ctx,404,"Asset metadata does not specify any content object: "+id);
					return;
				}
				AContent content=asset.getContent();
				if (content==null) {
					buildError(ctx,404,"Asset did not have any content available: "+id);
					return;
				}
				sendContent(ctx, meta.get(Fields.CONTENT),
					new covia.venue.storage.ContentProvider.Resolved(content, null));
				return;
			}

			RequestContext rctx = AuthMiddleware.callerContext(ctx);
			AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
			rctx = AuthMiddleware.withTransportAuth(rctx, bearer, null, null, engine().didVerifier());
			AString ref = Strings.create(id);

			Hash assetID=parseCallerAssetId(id);
			if (assetID!=null) {
				AString readAs = engine().requireLocalAccess(rctx, ref, Abilities.ASSET_READ);
				AVector<?> record = engine().getAssetRecord(assetID, readAs);
				if (record==null) {
					buildError(ctx,404,"Asset not found: "+id);
					return;
				}

				AMap<AString,ACell> meta=RT.ensureMap(record.get(AssetStore.POS_META));
				if (meta==null) {
					buildError(ctx,500,"Stored asset metadata is invalid: "+id);
					return;
				}
				if (!meta.containsKey(Fields.CONTENT)) {
					buildError(ctx,404,"Asset metadata does not specify any content object: "+id);
					return;
				}

				covia.venue.storage.ContentProvider.Resolved resolved =
					engine().resolveContent(ref, rctx);
				if (resolved==null) {
					buildError(ctx,404,"Asset did not have any content available: "+id);
					return;
				}
				sendContent(ctx, meta.get(Fields.CONTENT), resolved);
				return;
			}

			// Any other lattice address (w/…, v/…, o/…, DID URL): the same gate
			// and resolver as the metadata route, then serve whatever content the
			// resolved value carries. Metadata is resolved separately only for
			// error specificity and the content.contentType / fileName headers —
			// a ref may also resolve to a raw blob with no metadata at all.
			engine().requireResourceAccess(rctx, ref, Abilities.ASSET_READ);
			AMap<AString,ACell> meta = RT.ensureMap(engine().resolvePath(ref, rctx));
			covia.venue.storage.ContentProvider.Resolved resolved =
				engine().resolveContent(ref, rctx);
			if (resolved==null) {
				if (meta==null) buildError(ctx,404,"Asset not found: "+id);
				else if (!meta.containsKey(Fields.CONTENT)) buildError(ctx,404,"Asset metadata does not specify any content object: "+id);
				else buildError(ctx,404,"Asset did not have any content available: "+id);
				return;
			}
			sendContent(ctx, (meta!=null)?meta.get(Fields.CONTENT):null, resolved);
		} catch (AuthException e) {
			buildError(ctx,403,"Not authorised to read asset content: "+id);
		} catch (IOException e) {
			buildError(ctx,500,"Error retrieving asset content: "+e.getMessage());
		}
	}

	/**
	 * Sets the content headers — {@code content.contentType} (falling back to
	 * the resolver's declared type), optional inline disposition and filename —
	 * and streams the resolved content.
	 */
	private static void sendContent(Context ctx, ACell contentMeta,
			covia.venue.storage.ContentProvider.Resolved resolved) throws IOException {
		ACell contentType=(contentMeta==null)?null:RT.getIn(contentMeta,Fields.CONTENT_TYPE);
		if (contentType instanceof AString ct) {
			ctx.contentType(ct.toString());
		} else if (resolved.contentType()!=null) {
			ctx.contentType(resolved.contentType());
		}
		if (ctx.queryParam("inline")!=null) {
			ctx.header("Content-Disposition","inline");
		}
		ACell fileName=(contentMeta==null)?null:RT.getIn(contentMeta,Fields.FILE_NAME);
		if (fileName instanceof AString fn) {
			ctx.header("filename",fn.toString());
		}
		ctx.result(resolved.content().getInputStream());
		ctx.status(200);
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
		try {
			Hash assetID=parseCallerAssetId(idString);
			if (assetID==null) {
				buildError(ctx,400,"Invalid caller asset reference: "+idString);
				return;
			}

			RequestContext rctx = AuthMiddleware.callerContext(ctx);
			AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
			rctx = AuthMiddleware.withTransportAuth(rctx, bearer, null, null, engine().didVerifier());
			AString ref = Strings.create(idString);
			AString writeAs=engine().requireLocalAccess(rctx, ref, Abilities.ASSET_STORE);
			AVector<?> record=engine().getAssetRecord(assetID, writeAs);
			if (record==null) {
				buildError(ctx,404,"Asset not found: "+idString);
				return;
			}
			AString metadata=RT.ensureString(record.get(AssetStore.POS_JSON));
			if (metadata==null) {
				buildError(ctx,500,"Stored asset metadata is invalid: "+idString);
				return;
			}
			Asset asset=Asset.create(assetID, metadata);

			AMap<AString,ACell> meta=asset.meta();
			if (!meta.containsKey(Fields.CONTENT)) {
				buildError(ctx,404,"Asset metadata does not specify any content object: "+idString);
				return;
			}

			InputStream is=ctx.bodyInputStream();
			Hash contentHash= engine().putContent(asset,is);
			buildResult(ctx,200,contentHash);

		} catch (AuthException e) {
			buildError(ctx,403,"Not authorised to store asset content: "+idString);
		} catch (IllegalArgumentException e) {
			this.buildError(ctx, 400, "Cannot PUT asset content: "+e.getMessage());
		} catch (IOException | OutOfMemoryError e) {
			this.buildError(ctx, 500,"Storage error trying to PUT asset content: "+e.getMessage());
		}
	}

	@OpenApi(path = ROUTE + "run",
			methods = HttpMethod.POST,
			tags = { "Covia"},
			summary = "Run a Covia operation and return its result",
			description = "Runs an operation and waits for its result. Unlike /invoke, "
					+ "this result-oriented API does not return a Job handle. Execution still "
					+ "uses the normal Job lifecycle internally: mutating or unclassified "
					+ "operations are recorded, while operation.readOnly=true may use a "
					+ "transient Job unless operation.internal=false or venue policy forces "
					+ "read-only recording. The Java API is asynchronous (CompletableFuture); "
					+ "this HTTP request remains open until the operation completes.",
			requestBody = @OpenApiRequestBody(
				description = "Run request: operation reference and its input",
				content = @OpenApiContent(type = "application/json", from = InvokeRequest.class)),
			operationId = CoviaAPI.RUN,
			responses = {
				@OpenApiResponse(status = "200", description = "Operation output",
					content = @OpenApiContent(type = "application/json", from = Object.class)),
				@OpenApiResponse(status = "400", description = "Malformed request or operation"),
				@OpenApiResponse(status = "403", description = "Operation not authorised")
			})
	protected void runOperation(Context ctx) {
		ACell req;
		try {
			req = JSON.parseJSON5(ctx.body());
		} catch (Exception e) {
			buildError(ctx, 400, "Request body is not valid JSON: " + e.getMessage());
			return;
		}

		AString op = RT.ensureString(RT.getIn(req, Fields.OPERATION));
		if (op == null) {
			buildError(ctx, 400, "Run request requires an 'operation' parameter as a String");
			return;
		}
		ACell input = RT.getIn(req, Fields.INPUT);
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AVector<ACell> ucans = RT.getIn(req, Fields.UCANS);
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer, ucans, engine().getDIDString(), engine().didVerifier());

		try {
			ACell result = engine().jobs().runOperation(op, input, rctx).join();
			buildResult(ctx, 200, result);
		} catch (java.util.concurrent.CompletionException e) {
			Throwable cause = (e.getCause() != null) ? e.getCause() : e;
			if (cause instanceof AuthException) {
				buildError(ctx, 403, cause.getMessage());
			} else if (cause instanceof covia.exception.RemoteFetchException) {
				buildError(ctx, 502, cause.getMessage());
			} else if (cause instanceof covia.exception.RateLimitException rate) {
				ctx.header("Retry-After", Long.toString(rate.getRetryAfterSeconds()));
				buildError(ctx, 429, rate.getMessage());
			} else if (cause instanceof IllegalArgumentException
					|| cause instanceof IllegalStateException) {
				buildError(ctx, 400, "Error running operation: " + cause.getMessage());
			} else {
				buildError(ctx, 500, "Operation failed: " + cause.getMessage());
			}
		} catch (Exception e) {
			buildError(ctx, 500, "Unexpected failure running operation: " + e);
			log.warn("Unexpected exception handling client run", e);
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
					+ "input for bounded waits). Invoke always creates a durable Job; callers "
					+ "that only want an operation result should use POST /api/v1/run.",
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

		// Attach transport UCAN authority — proofs are additive cross-user grants —
		// from both channels: the `ucans` envelope array and an
		// `Authorization: Bearer <ucan-jwt>` (IETF UCAN-HTTP) stashed by
		// AuthMiddleware as UCAN_BEARER_ATTR.
		AVector<ACell> ucans = RT.getIn(req, "ucans");
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		// venueDID enables identity-from-ucans: an anonymous transport carrying a
		// verified identity token audienced to this venue authenticates as its
		// issuer (relayed cross-venue callers, covia#100 C3a).
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer, ucans, engine().getDIDString(), engine().didVerifier());

		try {
			// Wait window: `wait` (query param or body field) is boolean or an
			// integer number of milliseconds — true = the full 120s window,
			// an integer = that many ms (clamped to the cap). Parsed BEFORE the
			// operation is invoked so a malformed value rejects with 400 without
			// creating a job. See parseWaitMs.
			long waitMs = parseWaitMs(ctx.queryParam("wait"), RT.getIn(req, "wait"));
			if (CVMBool.TRUE.equals(RT.getIn(req, Fields.PRIVATE))) {
				buildError(ctx, 400, "invoke always creates a durable Job; use /api/v1/run when only the result is required");
				return;
			}

			Job job=engine().jobs().invokeOperation(op,input,rctx);
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
		} catch (covia.exception.RateLimitException e) {
			// Caller is over an admission/rate limit (e.g. concurrent-job cap) and
			// the bounded wait elapsed — standard backpressure: 429 + Retry-After.
			ctx.header("Retry-After", Long.toString(e.getRetryAfterSeconds()));
			this.buildError(ctx, 429, e.getMessage());
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
		// Job reads are delegable and federated observation must carry the
		// caller's authority: attach transport proofs from the bearer and the
		// X-Covia-Ucans header (a GET has no body for the ucans array). With
		// venueDID set, an identity token authenticates a relayed caller —
		// this is how a grid hop observes the remote job it created.
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer,
			AuthMiddleware.headerUcans(ctx), engine().getDIDString(), engine().didVerifier());

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
					@OpenApiResponse(status = "403", description = "Caller is not the job owner"),
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
	
	@OpenApi(path = ROUTE + "jobs/{id}/sse",
			methods = HttpMethod.GET,
			tags = { "Covia" },
			summary = "Server-sent events stream of job status updates",
			description = "Streams the job's status record as SSE frames on every status change until "
					+ "the job reaches a terminal state (the final record is the last frame; the stream "
					+ "then closes). Poll-free alternative to GET /jobs/{id} for watching a running job. "
					+ "Authenticated clients send the normal Authorization header; browser clients should "
					+ "consume the stream with fetch/ReadableStream because native EventSource cannot set headers. "
					+ "A missing or */* Accept header is treated as text/event-stream — the /sse path is "
					+ "unambiguous; an explicit non-SSE Accept is rejected with 406.",
			pathParams = { @OpenApiParam(name = "id", description = "Job ID (hex)") },
			operationId = "jobSse")
	protected void jobSse(Context ctx) {
		Blob id = Blob.parse(ctx.pathParam("id"));
		if (id == null) {
			buildError(ctx, 400, "Job request requires a job ID as a valid hex string");
			return;
		}
		// Ownership applies to the stream exactly as to GET /jobs/{id}: the
		// record resolves under the caller's context or not at all.
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		try {
			if (engine().jobs().getJobData(id, rctx) == null) {
				buildError(ctx, 404, "Job not found: " + id);
				return;
			}
		} catch (AuthException e) {
			// Match GET /jobs/{id}: ownership denials are ordinary API errors,
			// never unhandled exceptions after an SSE client probes a foreign id.
			buildError(ctx, 403, e.getMessage());
			return;
		}

		// Accept negotiation (#222): default to SSE, never a silent 200.
		String accept = ctx.header("Accept");
		boolean acceptable = accept == null || accept.isBlank()
			|| accept.contains("text/event-stream") || accept.contains("*/*");
		if (!acceptable) {
			buildError(ctx, 406,
				"This endpoint streams Server-Sent Events; send Accept: text/event-stream (or omit the Accept header)");
			return;
		}

		// Mirror io.javalin.http.sse.SseHandler.handle — which hard-requires
		// the Accept header, hence this hand-rolled equivalent. (SseClient's
		// constructor is Kotlin-internal but public in bytecode; Javalin is
		// version-pinned.)
		ctx.res().setStatus(200);
		ctx.res().setCharacterEncoding("UTF-8");
		ctx.res().setContentType("text/event-stream");
		ctx.res().addHeader("Connection", "close");
		ctx.res().addHeader("Cache-Control", "no-cache");
		ctx.res().addHeader("X-Accel-Buffering", "no");
		try {
			ctx.res().flushBuffer();
		} catch (java.io.IOException e) {
			throw new RuntimeException(e);
		}
		ctx.async(cfg -> cfg.timeout = 0L,
			() -> sseServer.registerSSE.accept(new io.javalin.http.sse.SseClient(ctx)));
	}

	@OpenApi(path = ROUTE + "jobs",
			methods = HttpMethod.GET,
			tags = { "Covia"},
			summary = "List the caller's jobs — a paged {items, total, offset, limit} envelope of "
				+ "job ids in chronological order (#229). The listing is always scoped to the "
				+ "authenticated caller's own jobs.",
			queryParams = {
					@OpenApiParam(name = "offset", type = Integer.class,
							description = "Starting index (default 0)."),
					@OpenApiParam(name = "limit", type = Integer.class,
							description = "Maximum ids returned (default first page of 1000; explicit max 1000).")
			})
	protected void getJobs(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		long offset=-1;
		long limit=-1;
		try {
			String off = ctx.queryParam("offset");
			if (off!=null) offset=Long.parseLong(off);
			String lim = ctx.queryParam("limit");
			if (lim!=null) {
				limit=Long.parseLong(lim);
				if (limit<0) throw new BadRequestResponse("Negative limit");
			}
		} catch (NumberFormatException e) {
			buildError(ctx,400,"Invalid offset or limit");
			return;
		}
		try {
			Index<Blob, ACell> jobs = engine().jobs().getJobs(rctx);
			// Same envelope and paging rules as GET /assets (#229).
			long n = jobs.count();
			long start = Math.max(0, offset);
			long actualLimit;
			if (limit<0) {
				actualLimit = Math.min(Math.max(0, n-start), 1000);
			} else {
				if (limit>1000) throw new BadRequestResponse("Too many jobs requested: "+limit);
				actualLimit = limit;
			}
			Map<Object,Object> result = new HashMap<>();
			result.put(Fields.TOTAL, n);
			result.put(Fields.OFFSET, start);
			result.put(Fields.LIMIT, actualLimit);
			long end = Math.min(n, start + actualLimit);
			ArrayList<Object> items = new ArrayList<>((int) Math.max(0, end - start));
			for (long i = start; i < end; i++) {
				items.add(jobs.entryAt(i).getKey().toHexString());
			}
			result.put(Fields.ITEMS, items);
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
					@OpenApiParam(name = "agent", example = "researcher",
							description = "Required for n/, t/, and c/: a bare agent id under the caller, or a full agent DID."),
					@OpenApiParam(name = "task", example = "0198abcdef0123456789abcdef012345",
							description = "Required for t/: task id, which is the agent:request Job id."),
					@OpenApiParam(name = "session", example = "0198abcdef0123456789abcdef012345",
							description = "Required for c/: the session id."),
					@OpenApiParam(name = "maxSize", type = Long.class, example = "1000000",
							description = "Byte guard (default 1000000): a larger value is withheld and the response carries truncated:true with its size.")
			})
	protected void getValueRead(Context ctx) { handleValueRoute(ctx, "read"); }

	@OpenApi(path = ROUTE + "values/list", methods = HttpMethod.GET, tags = { "Values" },
			summary = "List the keys / structure of a lattice node (job-free)", operationId = "valueList",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "g",
							description = "Lattice path of the node to list."),
					@OpenApiParam(name = "agent", example = "researcher",
							description = "Required for n/, t/, and c/: a bare agent id under the caller, or a full agent DID."),
					@OpenApiParam(name = "task",
							description = "Required for t/: task id, which is the agent:request Job id."),
					@OpenApiParam(name = "session",
							description = "Required for c/: the session id."),
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
					@OpenApiParam(name = "agent", example = "researcher",
							description = "Required for n/, t/, and c/: a bare agent id under the caller, or a full agent DID."),
					@OpenApiParam(name = "task",
							description = "Required for t/: task id, which is the agent:request Job id."),
					@OpenApiParam(name = "session",
							description = "Required for c/: the session id."),
					@OpenApiParam(name = "offset", type = Long.class, example = "0",
							description = "Starting element index (default 0)."),
					@OpenApiParam(name = "limit", type = Long.class, example = "100",
							description = "Maximum elements to return (default 100)."),
					@OpenApiParam(name = "maxSize", type = Long.class,
							description = "Max CAD3 encoding bytes of the returned page (default 1000000); "
									+ "an oversize page is a 400 (reduce limit) — slice returns exact values, never summarised.")
			})
	protected void getValueSlice(Context ctx) { handleValueRoute(ctx, "slice"); }

	@OpenApi(path = ROUTE + "values/inspect", methods = HttpMethod.GET, tags = { "Values" },
			summary = "Budget-controlled JSON5 render of a lattice value (job-free)", operationId = "valueInspect",
			queryParams = {
					@OpenApiParam(name = "path", required = true, example = "g/my-agent",
							description = "Lattice path to render."),
					@OpenApiParam(name = "agent", example = "researcher",
							description = "Required for n/, t/, and c/: a bare agent id under the caller, or a full agent DID."),
					@OpenApiParam(name = "task",
							description = "Required for t/: task id, which is the agent:request Job id."),
					@OpenApiParam(name = "session",
							description = "Required for c/: the session id."),
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
					@OpenApiParam(name = "agent", example = "researcher",
							description = "Required for n/, t/, and c/: a bare agent id under the caller, or a full agent DID."),
					@OpenApiParam(name = "task",
							description = "Required for t/: task id, which is the agent:request Job id."),
					@OpenApiParam(name = "session",
							description = "Required for c/: the session id."),
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
					@OpenApiParam(name = "agent", example = "researcher",
							description = "Required for n/, t/, and c/: a bare agent id under the caller, or a full agent DID."),
					@OpenApiParam(name = "task",
							description = "Required for t/: task id, which is the agent:request Job id."),
					@OpenApiParam(name = "session",
							description = "Required for c/: the session id."),
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

		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		AString bearer = ctx.attribute(AuthMiddleware.UCAN_BEARER_ATTR);
		rctx = AuthMiddleware.withTransportAuth(rctx, bearer, null, null, engine().didVerifier());

		AMap<AString, ACell> input;
		try {
			path = expandValueScope(ctx, rctx, path);
			input = buildValueInput(ctx, path);
		} catch (IllegalArgumentException e) {
			buildError(ctx, 400, e.getMessage());
			return;
		}

		CoviaAdapter covia = (CoviaAdapter) engine().getAdapter("covia");
		try {
			if (isExecutionScopedRequest(ctx)) {
				// Scoped state can contain private working memory. It is also
				// mutable despite the GET surface, so shared/browser caches must
				// never retain it.
				ctx.header("Cache-Control", "private, no-store");
			}
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
		try {
			return m.assoc(Strings.intern(name), CVMLong.create(Long.parseLong(v.trim())));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid integer query parameter '" + name + "': " + v);
		}
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

	/** True when any explicit execution-scope selector is present. */
	private static boolean isExecutionScopedRequest(Context ctx) {
		return hasParam(ctx, "agent") || hasParam(ctx, "task") || hasParam(ctx, "session");
	}

	private static boolean hasParam(Context ctx, String name) {
		String value = ctx.queryParam(name);
		return value != null && !value.isBlank();
	}

	private record AgentScope(AString ownerDID, AString agentId) {}

	/**
	 * Expands the execution-scoped shorthands to the exact persistent lattice
	 * resource before the Covia accessor performs its capability check.
	 *
	 * <p>This deliberately does not mutate the authenticated RequestContext with
	 * caller-supplied scope ids. Authorisation therefore applies to the same
	 * canonical resource that is ultimately read, preventing selector
	 * substitution between sibling or foreign agents.</p>
	 */
	private static String expandValueScope(Context ctx, RequestContext rctx, String path) {
		String namespace = firstSegment(path);
		boolean scopedPath = isExecutionScopedNamespace(path);
		if (!scopedPath) {
			if (isExecutionScopedRequest(ctx)) {
				throw new IllegalArgumentException(
					"'agent', 'task', and 'session' are only valid with n/, t/, or c/ paths");
			}
			return path;
		}

		AgentScope agent = parseAgentScope(ctx.queryParam("agent"), rctx);
		String suffix = path.length() == namespace.length()
			? "" : path.substring(namespace.length()); // includes the leading '/'

		return switch (namespace) {
			case "n" -> {
				rejectParam(ctx, "task", "n/");
				rejectParam(ctx, "session", "n/");
				yield agent.ownerDID() + "/g/" + agent.agentId() + "/n" + suffix;
			}
			case "t" -> {
				rejectParam(ctx, "session", "t/");
				Blob taskId = parseBlobParam(ctx, "task", "t/");
				// A task is its agent:request Job. Its durable scratch belongs on
				// that Job record, while the agent task index remains a queue view.
				yield agent.ownerDID() + "/j/" + taskId.toHexString() + "/temp" + suffix;
			}
			case "c" -> {
				rejectParam(ctx, "task", "c/");
				Blob sessionId = parseBlobParam(ctx, "session", "c/");
				yield agent.ownerDID() + "/g/" + agent.agentId()
					+ "/sessions/" + sessionId.toHexString() + "/c" + suffix;
			}
			default -> throw new IllegalArgumentException("Unsupported scoped namespace: " + namespace);
		};
	}

	private static AgentScope parseAgentScope(String ref, RequestContext rctx) {
		if (ref == null || ref.isBlank()) {
			throw new IllegalArgumentException(
				"Missing 'agent' query parameter for execution-scoped path");
		}

		AString owner;
		AString agentId;
		if (ref.startsWith("did:")) {
			AString agentDID = Strings.create(ref);
			if (!Principals.isAgentDID(agentDID)) {
				throw new IllegalArgumentException(
					"'agent' must be a bare agent id or a full agent DID ending ':g:<agentId>'");
			}
			owner = Principals.userOf(agentDID);
			agentId = Principals.agentIdOf(agentDID);
		} else {
			owner = rctx.getUserDID();
			if (owner == null) {
				throw new IllegalArgumentException(
					"A bare 'agent' id requires an authenticated caller");
			}
			agentId = Strings.create(ref);
		}

		if (agentId == null || agentId.count() == 0
				|| agentId.toString().contains("/") || agentId.toString().contains(":")) {
			throw new IllegalArgumentException("'agent' must name a single agent id");
		}
		// Apply the canonical agent-DID syntax validation in one place.
		Principals.agentDID(owner, agentId);
		return new AgentScope(owner, agentId);
	}

	private static Blob parseBlobParam(Context ctx, String name, String namespace) {
		String raw = ctx.queryParam(name);
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException(
				"Missing '" + name + "' query parameter for " + namespace + " path");
		}
		try {
			Blob id = Blob.parse(raw.trim());
			if (id == null) throw new IllegalArgumentException();
			return id;
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(
				"Invalid '" + name + "' query parameter: expected a hex id");
		}
	}

	private static void rejectParam(Context ctx, String name, String namespace) {
		if (hasParam(ctx, name)) {
			throw new IllegalArgumentException(
				"'" + name + "' is not valid with a " + namespace + " path");
		}
	}

	// ========== Agent endpoints ==========

	@OpenApi(path = ROUTE + "agents",
			methods = HttpMethod.GET,
			tags = { "Covia" },
			summary = "List the authenticated caller's agents (job-free, #180)",
			operationId = "getAgents",
			queryParams = {
					@OpenApiParam(name = "status", type = Boolean.class,
							description = "false → bare agent ids instead of the default status-annotated form ({agentId, status, tasks, error?})."),
					@OpenApiParam(name = "includeTerminated", type = Boolean.class,
							description = "true → include terminated agents (hidden by default).")
			})
	protected void getAgents(Context ctx) {
		RequestContext rctx = AuthMiddleware.callerContext(ctx);
		if (rctx.getCallerDID() == null) {
			buildError(ctx, 401, "Authentication required");
			return;
		}
		// One canonical shape across both transports (#233): default to the
		// same enriched entries agent:list returns — clients read .agentId,
		// .status, .tasks without an N+1 fan-out. status=false opts into the
		// lean bare-id form.
		boolean annotated = !"false".equals(ctx.queryParam("status"));
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
			pathParams = {
					@OpenApiParam(
							name = "id",
							description = "Asset identifier",
							required = true)
			},
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
