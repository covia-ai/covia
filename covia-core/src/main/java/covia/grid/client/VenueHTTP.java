package covia.grid.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.core.data.ACell;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.ConversionException;
import covia.exception.RateLimitException;
import covia.exception.ResponseException;
import covia.grid.AContent;
import covia.grid.Asset;
import covia.grid.Assets;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.auth.VenueAuth;
import covia.grid.impl.BlobContent;

public class VenueHTTP extends Venue {

	public static Logger log=LoggerFactory.getLogger(VenueHTTP.class);

	private static final double BACKOFF_FACTOR = 1.5;
	/**
	 * Initial poll delay in ms. Kept low so short operations finish in
	 * sub-tens-of-ms instead of waiting a full poll cycle. Long-running jobs
	 * are unaffected — exponential backoff (1.5x) reaches the previous
	 * 300ms gap by the 10th poll, and the MAX_POLL_DELAY ceiling is the
	 * steady-state rate, not this value.
	 */
	private static final long INITIAL_POLL_DELAY = 10;
	private static final long MAX_POLL_DELAY = 10000;
	private static final long DEFAULT_TIMEOUT = 30000; // 30 seconds, allows for slowish LLM responses

	private long timeout=DEFAULT_TIMEOUT;
	private final HttpClient httpClient;
	private final URI baseURI;
	private final VenueAuth auth;

	// ---- 429 backpressure handling ----

	/** Retry policy for HTTP 429 responses. */
	private RetryPolicy retryPolicy = RetryPolicy.defaults();
	/** Sleep hook, injectable for deterministic tests (default {@link Thread#sleep}). */
	private Sleeper sleeper = Thread::sleep;
	/** Random [0,1) source for full jitter, injectable for tests. */
	private DoubleSupplier rng = Math::random;
	/** Millisecond clock, injectable for tests. */
	private LongSupplier clock = System::currentTimeMillis;
	/** Optional client-side limit on concurrent in-flight requests; null = unlimited. */
	private volatile Semaphore requestPermits = null;

	/**
	 * UCAN proof tokens (JWT strings) presented with every invoke via the
	 * request-body {@code ucans} array — the transport channel that survives
	 * cross-venue hops (UCAN.md §4.3). Null = none. These are delegation
	 * proofs (e.g. a resource owner's grant audienced to this client's
	 * identity); the venue verifies them at ingress — presenting them costs
	 * nothing when they don't apply.
	 */
	private volatile AVector<ACell> ucans = null;

	/**
	 * Connection-level private-jobs mode (#192) — see {@link #setPrivate(boolean)}.
	 */
	private volatile boolean privateJobs = false;

	/** A single HTTP send attempt (may throw the checked exceptions {@code HttpClient.send} throws). */
	@FunctionalInterface
	interface HttpCall { HttpResponse<String> call() throws IOException, InterruptedException; }

	/** Sleep hook, so tests can advance time without waiting. */
	@FunctionalInterface
	interface Sleeper { void sleep(long ms) throws InterruptedException; }

	/** Sets the retry policy for 429 responses (null → no retry). */
	public void setRetryPolicy(RetryPolicy policy) {
		this.retryPolicy = (policy != null) ? policy : RetryPolicy.noRetry();
	}

	/**
	 * Bounds the number of concurrent in-flight requests this client issues —
	 * coarse client-side backpressure that reduces how often the venue's limits
	 * are hit. {@code n <= 0} removes the limit (the default).
	 */
	public void setMaxConcurrentRequests(int n) {
		this.requestPermits = (n > 0) ? new Semaphore(n) : null;
	}

	/**
	 * Sets the UCAN proof tokens (JWT strings) this client presents with every
	 * invoke, via the request-body {@code ucans} array. Pass null or an empty
	 * list to clear. Use for cross-user / cross-venue delegated access: the
	 * proofs must be audienced to this client's authenticated identity.
	 */
	@Override
	public void setUcans(java.util.List<String> jwts) {
		if (jwts == null || jwts.isEmpty()) { this.ucans = null; return; }
		AVector<ACell> v = Vectors.empty();
		for (String jwt : jwts) v = v.conj(Strings.create(jwt));
		this.ucans = v;
	}

	/**
	 * Puts this connection in <b>private-jobs mode</b> (#192): every subsequent
	 * invoke executes as a memory-only job — never persisted to the venue's job
	 * index, no durable record, gone on venue restart. Requires
	 * {@code enablePrivateJobs} on the venue; a private request against a venue
	 * without it fails, never silently downgrades to a persisted job.
	 *
	 * <p>Because a completed private job is immediately forgotten by the venue,
	 * results are collected through the invoke {@code wait} window rather than
	 * polling — so private mode works with the run-style entry points
	 * ({@link #invokeAndWait(AString, ACell)}, {@link #invokeSync(String, ACell)});
	 * the poll-style {@link #invoke(AString, ACell)} throws.</p>
	 */
	public void setPrivate(boolean enabled) {
		this.privateJobs = enabled;
	}

	// Package-private test seams (deterministic retry tests):
	void setSleeper(Sleeper s) { this.sleeper = s; }
	void setClock(LongSupplier c) { this.clock = c; }
	void setRng(DoubleSupplier r) { this.rng = r; }

	public VenueHTTP(URI host) {
		this(host, VenueAuth.none());
	}

	public VenueHTTP(URI host, VenueAuth auth) {
		this.baseURI = host.resolve("/api/v1/");
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		this.auth = auth;

		// Auto-set user identity from auth if available
		String did = auth.getDID();
		if (did != null) {
			setUser(did);
		}
	}
	
	/**
	 * Gets the base URI for API requests
	 * @return The base URI
	 */
	protected URI getBaseURI() {
		return baseURI;
	}

	/**
	 * Create a new HTTP request builder with auth headers applied.
	 * @param uri The request URI
	 * @return Pre-authenticated request builder
	 */
	/**
	 * Header carrying the caller's UCAN proofs on body-less requests (job
	 * observation GETs): comma-separated JWTs, verified at venue ingress
	 * exactly like the request-body {@code ucans} array. Commas cannot occur
	 * inside a JWT, so the encoding is unambiguous.
	 */
	public static final String UCANS_HEADER = "X-Covia-Ucans";

	private HttpRequest.Builder requestBuilder(URI uri) {
		HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
		// Per-request total deadline. connectTimeout only bounds establishing the
		// TCP connection; without this a connected-but-stalled venue (slow/hung
		// remote, half-open socket) leaves the request — and any caller that
		// .join()s it (federation, orchestration) — pending forever. Generous
		// floor so large content transfers and slow LLM-backed invokes still
		// complete; grows with the caller's configured job-wait timeout.
		builder.timeout(Duration.ofMillis(Math.max(timeout, 120_000L)));
		auth.apply(builder);
		// Proofs also ride a header so body-less requests (GET /jobs/{id} and
		// friends) carry the caller's authority — without this, a federated
		// hop can invoke a remote job but never observe it (identity tokens
		// and delegations only travelled in the invoke body).
		AVector<ACell> proofTokens = this.ucans;
		if (proofTokens != null) {
			StringBuilder sb = new StringBuilder();
			for (long i = 0; i < proofTokens.count(); i++) {
				if (i > 0) sb.append(',');
				sb.append(proofTokens.get(i).toString());
			}
			builder.header(UCANS_HEADER, sb.toString());
		}
		return builder;
	}

	/**
	 * Create a new HTTP request builder for an API path with auth headers applied.
	 * @param path The API path relative to the base URI
	 * @return Pre-authenticated request builder
	 */
	private HttpRequest.Builder requestBuilder(String path) {
		return requestBuilder(getBaseURI().resolve(path));
	}

	/**
	 * Sends a request, mapping a low-level connection failure to a clear
	 * {@link ResponseException} that names the venue. A bare
	 * {@link java.net.ConnectException} — the server down, unreachable, or its
	 * accept queue full under load — is otherwise opaque to callers.
	 */
	private <T> CompletableFuture<HttpResponse<T>> dispatch(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
		long deadline = clock.getAsLong() + retryPolicy.maxTotalWaitMs();
		Semaphore p = requestPermits;
		if (p == null) return dispatchAttempt(req, handler, 1, deadline);
		// Client-side concurrency limit: acquire off the caller thread, hold the
		// permit across this request's retries, release when it finally settles.
		return CompletableFuture.runAsync(p::acquireUninterruptibly)
			.thenCompose(x -> dispatchAttempt(req, handler, 1, deadline))
			.whenComplete((r, e) -> p.release());
	}

	/**
	 * One attempt of an async request, retrying on 429 per {@link #retryPolicy}:
	 * honours {@code Retry-After}, backs off with full jitter, and fails with
	 * {@link RateLimitException} once attempts / wait budget are exhausted. A bare
	 * {@link java.net.ConnectException} is mapped to a clear {@link ResponseException}
	 * naming the venue. Non-429 responses pass through for the caller's own handling.
	 */
	private <T> CompletableFuture<HttpResponse<T>> dispatchAttempt(
			HttpRequest req, HttpResponse.BodyHandler<T> handler, int attempt, long deadline) {
		return httpClient.sendAsync(req, handler).exceptionallyCompose(ex -> {
			Throwable cause = (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null)
				? ex.getCause() : ex;
			if (cause instanceof java.net.ConnectException) {
				return CompletableFuture.failedFuture(new ResponseException(
					"Cannot reach venue at " + baseURI + ": " + cause.getMessage()
					+ " — server is down, unreachable, or refused the connection "
					+ "(its accept queue may be full under load)", cause));
			}
			return CompletableFuture.failedFuture(ex);
		}).thenCompose(resp -> {
			if (resp.statusCode() != 429) return CompletableFuture.completedFuture(resp);
			long now = clock.getAsLong();
			long retryAfterMs = RetryPolicy.parseRetryAfterMs(
				resp.headers().firstValue("Retry-After").orElse(null), now);
			long delay = retryPolicy.retryDelayMs(attempt, retryAfterMs, deadline - now, rng.getAsDouble());
			if (delay < 0) return CompletableFuture.failedFuture(rateLimited(retryAfterMs));
			Executor delayed = CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS);
			return CompletableFuture.supplyAsync(() -> null, delayed)
				.thenCompose(x -> dispatchAttempt(req, handler, attempt + 1, deadline));
		});
	}

	/**
	 * Executes a synchronous request with 429 retry (Retry-After floor, full-jitter
	 * backoff, bounded attempts / wait budget). Non-429 responses are returned as-is
	 * for the caller's own status handling; exhausted 429s throw {@link RateLimitException}.
	 */
	HttpResponse<String> retrying(HttpCall exec) throws IOException, InterruptedException {
		long deadline = clock.getAsLong() + retryPolicy.maxTotalWaitMs();
		for (int attempt = 1; ; attempt++) {
			HttpResponse<String> resp = exec.call();
			if (resp.statusCode() != 429) return resp;
			long now = clock.getAsLong();
			long retryAfterMs = RetryPolicy.parseRetryAfterMs(
				resp.headers().firstValue("Retry-After").orElse(null), now);
			long delay = retryPolicy.retryDelayMs(attempt, retryAfterMs, deadline - now, rng.getAsDouble());
			if (delay < 0) throw rateLimited(retryAfterMs);
			sleeper.sleep(delay);
		}
	}

	/** Central synchronous send: 429 retry + optional client-side concurrency limit. */
	private HttpResponse<String> sendSync(HttpRequest req) throws IOException, InterruptedException {
		Semaphore p = requestPermits;
		if (p != null) p.acquire();
		try {
			return retrying(() -> httpClient.send(req, HttpResponse.BodyHandlers.ofString()));
		} finally {
			if (p != null) p.release();
		}
	}

	private RateLimitException rateLimited(long retryAfterMs) {
		return new RateLimitException(
			"Rate limited by venue (HTTP 429) after " + retryPolicy.maxAttempts() + " attempt(s)",
			Math.max(1, retryAfterMs / 1000));
	}

	/**
	 * Adds an asset to the connected venue
	 * 
	 * @param jsonMeta JSON metadata string for the asset
	 */
	public CompletableFuture<Hash> addAsset(String jsonMeta) {
		ACell meta=JSON.parseJSON5(jsonMeta);
		return addAsset(meta);
	}
	
	/**
	 * Adds an asset to the connected venue
	 */
	public CompletableFuture<Hash> addAsset(ACell meta) {
		HttpRequest req = requestBuilder("assets")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.printPretty(meta).toString()))
			.build();
		
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
			if (response.statusCode() != 201) {
				throw new ResponseException("Failed to add asset: " + response.statusCode() + " - " + response.body(), response);
			}
			ACell body=JSON.parseJSON5(response.body());
			Hash v=Hash.parse(body);
			if (v!=null) return v;
			throw new ConversionException("Result did not contain a valid Hash: got "+body); 
		});
	} 

	/**
	 * Create a Covia grid client using the given venue URI
	 * @param host Host Venue
	 * @return Covia client instance
	 */
	public static VenueHTTP create(URI host) {
		return new VenueHTTP(host);
	}

	/**
	 * Create a Covia grid client with authentication
	 * @param host Host Venue
	 * @param auth Authentication provider
	 * @return Covia client instance
	 */
	public static VenueHTTP create(URI host, VenueAuth auth) {
		return new VenueHTTP(host, auth);
	}

	/**
	 * Gets metadata for a given asset on the connected venue
	 * @param asset ID for asset. Can be a full asset DID
	 * @return Asset metadata as a String, or null if asset is not found
	 */
	public CompletableFuture<String> getMeta(String asset) {
		Hash h=Assets.parseAssetID(asset);
		if (h==null) throw new IllegalArgumentException("Bad asset ID format");
		
		HttpRequest request=requestBuilder("assets/"+h.toHexString())
			.GET()
			.build();
		
		return dispatch(request, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response-> {
			if (response.statusCode()!=200) return null;
			return response.body();
		});
	}
	
	/**
	 * Gets status of connected venue
	 * @return Status map
	 */
	public CompletableFuture<AMap<AString,ACell>> getStatus() {
		
		HttpRequest request=requestBuilder("status")
			.GET()
			.build();
		
		return dispatch(request, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response-> {
			int code=response.statusCode();
			if (code!=200) {
				throw new ResponseException("getStatus returned code: "+code,response);
			}
			return RT.castMap(JSON.parse(response.body()));
		});
	}

	/**
	 * Invokes an operation on the connected venue, returning a submitted Job.
	 * The returned future completes when the job is submitted (typically PENDING).
	 * Callers can use {@code job.future()} or {@code job.awaitResult()} to wait for completion.
	 *
	 * @param assetID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future for the submitted Job (likely PENDING)
	 */
	@Override
	public CompletableFuture<Job> invoke(String assetID, ACell input)  {
		AString opID=Strings.create(assetID);
		return invoke(opID,input);
	}

	@Override
	public CompletableFuture<Job> getJob(Blob jobId) {
		return getJobData(jobId).thenApply(status -> {
			if (status == null) {
				throw new IllegalArgumentException("Job not found: " + jobId.toHexString());
			}
			return Job.create(status);
		});
	}

	@Override
	public CompletableFuture<AMap<AString, ACell>> getJobStatus(Blob jobId) {
		return getJobData(jobId).thenApply(status -> {
			if (status == null) {
				throw new IllegalArgumentException("Job not found: " + jobId.toHexString());
			}
			return status;
		});
	}

	@Override
	public CompletableFuture<ACell> awaitJobResult(Blob jobId) {
		return getJob(jobId).thenCompose(job -> {
			startBackgroundPolling(job);
			return job.future();
		});
	}

	@Override
	public CompletableFuture<Job> invoke(Hash assetID, ACell input)  {
		return invoke(assetID.toCVMHexString(),input);
	}
	
	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke 
	 * @param input
	 * @return Finished Job
	 * @throws TimeoutException 
	 * @throws ExecutionException 
	 * @throws InterruptedException 
	 */
	public Job invokeSync(String opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(Strings.create(opID), input);
		waitForFinish(job);
		return job;
	}

	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke
	 * @param input Input parameters
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Finished Job
	 * @throws TimeoutException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public Job invokeSync(String opID, ACell input, long timeoutMs) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(Strings.create(opID), input);
		waitForFinish(job, timeoutMs);
		return job;
	}

	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke as an Asset ID or adapter operation alias
	 * @param input
	 * @return Finished Job
	 * @throws TimeoutException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public Job invokeSync(Hash opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(opID, input);
		waitForFinish(job);
		return job;
	}

	/**
	 * Invokes an operation, returning a finished Job once complete
	 * @param opID Operation to invoke as an Asset ID
	 * @param input Input parameters
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Finished Job
	 * @throws TimeoutException
	 * @throws ExecutionException
	 * @throws InterruptedException
	 */
	public Job invokeSync(Hash opID, ACell input, long timeoutMs) throws InterruptedException, ExecutionException, TimeoutException {
		Job job=startJob(opID, input);
		waitForFinish(job, timeoutMs);
		return job;
	}
	
	/**
	 * Invokes an operation on the connected venue. Submits the job and starts
	 * background polling on a virtual thread. The returned future completes when
	 * the job is submitted (typically PENDING). Callers can use {@code job.future()}
	 * or {@code job.awaitResult()} to wait for completion.
	 * @param opID The AssetID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the submitted Job (likely PENDING)
	 */
	public CompletableFuture<Job> invoke(AString opID, ACell input)  {
		if (privateJobs) {
			throw new IllegalStateException(
				"Private-jobs mode requires a run-style call (invokeAndWait / invokeSync): "
				+ "a completed private job is immediately forgotten by the venue, so a "
				+ "poll-style Job cannot collect its result.");
		}
		return startJobAsync(opID, input).thenApply(job -> {
			startBackgroundPolling(job);
			return job;
		});
	}
	
	/**
	 * Starts a operation on the Grid. Does not start polling.
	 * @param opID Operation ID
	 * @param input Operation input
	 * @return Job instance (likely to be PENDING)
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public Job startJob(AString opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		return startJobAsync(opID,input).get(submitTimeoutSecs(),TimeUnit.SECONDS);
	}
	
	/**
	 * Starts a operation on the Grid. Does not start polling.
	 * @param opID Operation ID
	 * @param input Operation input
	 * @return Job instance (likely to be PENDING)
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public Job startJob(Hash opID, ACell input) throws InterruptedException, ExecutionException, TimeoutException {
		return startJobAsync(opID.toCVMHexString(),input).get(submitTimeoutSecs(),TimeUnit.SECONDS);
	}

	/**
	 * How long a synchronous submit may block. Under private-jobs mode the
	 * invoke response arrives only when the server-side wait window closes
	 * (up to the venue's 120s cap), so the old 5s submit cap would misfire.
	 */
	private long submitTimeoutSecs() {
		return privateJobs ? 150 : 5;
	}
	
	/**
	 * Starts an operation on the connected venue. Does not start polling.
	 * @param opID The Asset ID of the operation to invoke
	 * @param input The input parameters for the operation as an ACell
	 * @return Future containing the job status, likely to be PENDING
	 */
	public CompletableFuture<Job> startJobAsync(AString opID, ACell input) {
		AMap<AString,ACell> reqBody = Maps.of(
			"operation", opID,
			"input", input);
		// Delegation proofs travel in the body `ucans` array (survives hops,
		// unlike headers) — verified by the venue at ingress.
		AVector<ACell> proofTokens = this.ucans;
		if (proofTokens != null) reqBody = reqBody.assoc(Strings.intern("ucans"), proofTokens);
		if (privateJobs) {
			// Memory-only job (#192): the result is collected through the invoke
			// wait window — a completed private job is immediately forgotten, so
			// polling cannot be relied on. The venue clamps the wait to its cap.
			reqBody = reqBody.assoc(Fields.PRIVATE, CVMBool.TRUE);
			reqBody = reqBody.assoc(Fields.WAIT, CVMBool.TRUE);
		}
		HttpRequest req = requestBuilder("invoke")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(reqBody)))
			.build();

		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			// 201 = job submitted; 200 = wait window closed with the finished record
			if (code != 201 && code != 200) {
				throw new ResponseException("Failed to start operation: " + response+" = "+response.body(),response);
			}
			AMap<AString,ACell> body=RT.ensureMap(JSON.parseJSON5(response.body()));
			if (body==null) {
				throw new ResponseException("Invalid response body",response);
			}
			return Job.create(body);
		});
	}
	
	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(AString opID, ACell input) throws InterruptedException {
		Job job=startJobAsync(opID,input).join();
		waitForFinish(job);
		return job;
	}

	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(AString opID, ACell input, long timeoutMs) throws InterruptedException {
		Job job=startJobAsync(opID,input).join();
		waitForFinish(job, timeoutMs);
		return job;
	}

	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(Hash opID, ACell input) throws InterruptedException {
		return invokeAndWait(opID.toCVMHexString(),input);
	}

	/**
	 * Invokes a Job and waits for it to finish
	 * @param opID Identifier of operation
	 * @param input Input to the operation
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return Job in finished state (COMPLETE, FAILED or CANCELLED)
	 * @throws InterruptedException
	 */
	public Job invokeAndWait(Hash opID, ACell input, long timeoutMs) throws InterruptedException {
		return invokeAndWait(opID.toCVMHexString(), input, timeoutMs);
	}
	
	/**
	 * Invokes an operation on the connected venue
	 * @return Future containing the operation execution result
	 */
	public CompletableFuture<List<Hash>> getAssets() {
		HttpRequest req = requestBuilder("assets")
			.GET()
			.build();
		
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code=response.statusCode();
			if (code != 200) {
				throw new ConversionException("assets API returned status: "+code);
			}
			ACell body=JSON.parseJSON5(response.body());
			AVector<?> items=null;
			if (body instanceof AVector v) {
				// Support for legacy API version that returned a list of asset IDs
				items=v;
			} else if (body instanceof AMap m) {
				items=RT.ensureVector(m.get(Fields.ITEMS));
			} 
			
			if (items==null) {
				throw new ConversionException("assets API did not return a list of assets");
			}
			
			ArrayList<Hash> al=new ArrayList<>();
			long n=items.count();
			for (int i=0; i<n; i++) {
				al.add(Hash.parse(items.get(i)));
			}
			return al;
			
		});
		

	}
	
	/**
	 * Starts background polling for job completion on a virtual thread.
	 * When polling detects the job is finished, the Job's lazy future is completed,
	 * unblocking any callers awaiting {@code job.future()} or {@code job.awaitResult()}.
	 * @param job Job to poll for
	 */
	private void startBackgroundPolling(Job job) {
		if (job.isFinished()) return;
		Thread.ofVirtual().start(() -> {
			try {
				waitForFinish(job);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				job.cancel();
			} catch (covia.exception.ResponseException e) {
				// Polling stopped before the Job reached a terminal state
				// (timeout or transport error). Do NOT mark the Job as
				// failed — the remote work is still going as far as we
				// know. Just signal to awaiters that we lost visibility.
				log.debug("Polling for job {} ended: {}", job.getID(), e.getMessage());
				job.pollingFailed(e);
			}
		});
	}

	/**
	 * Waits for a remote job to finish, polling with exponential backoff.
	 * Uses the default timeout configured via {@link #setTimeout}.
	 *
	 * <p><b>Timeout semantics:</b> This is a <em>client-side</em> polling timeout only.
	 * The remote job continues running on the venue regardless of whether this
	 * client times out. After a timeout, callers can re-acquire the latest job
	 * status by calling {@link #getJobStatus(Blob)} with the job ID — the job
	 * may have completed, failed, or still be running.
	 *
	 * @param job Any Job, presumably not yet finished
	 * @return true if successfully completed, false if failed
	 * @throws InterruptedException if interrupted while waiting
	 * @throws ResponseException if polling times out (job may still be running remotely)
	 */
	public boolean waitForFinish(Job job) throws InterruptedException {
		return waitForFinish(job, timeout);
	}

	/**
	 * Waits for a remote job to finish, polling with exponential backoff.
	 *
	 * <p><b>Timeout semantics:</b> This is a <em>client-side</em> polling timeout only.
	 * The remote job continues running on the venue regardless of whether this
	 * client times out. After a timeout, callers can re-acquire the latest job
	 * status by calling {@link #getJobStatus(Blob)} with the job ID — the job
	 * may have completed, failed, or still be running.
	 *
	 * @param job Any Job, presumably not yet finished
	 * @param timeoutMs Maximum time to wait in milliseconds
	 * @return true if successfully completed, false if failed or timed out
	 * @throws InterruptedException if interrupted while waiting
	 * @throws ResponseException if polling times out (job may still be running remotely)
	 */
	public boolean waitForFinish(Job job, long timeoutMs) throws InterruptedException {
		Blob id=job.getID();
		if (id==null) throw new IllegalStateException("Job has no ID");
		long currentDelay = INITIAL_POLL_DELAY;
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!job.isFinished()) {
			if (System.currentTimeMillis() > deadline) {
				throw new ResponseException("Job polling timed out after "+timeoutMs+"ms for job: "+id);
			}
			try {
				updateJobStatus(job);
				if (job.isFinished()) break;
			} catch (ExecutionException | TimeoutException e) {
				throw new ResponseException("Job status polling failed", (Throwable) e);
			}
			Thread.sleep(currentDelay);
			currentDelay = Math.min((long) (currentDelay * BACKOFF_FACTOR), MAX_POLL_DELAY);
		}
		return job.isComplete();
	}

	/**
	 * Sets the timeout in milliseconds for polling operations like {@link #waitForFinish}.
	 * @param timeoutMs Timeout in milliseconds
	 */
	public void setTimeout(long timeoutMs) {
		this.timeout = timeoutMs;
	}

	/**
	 * Gets the timeout in milliseconds for polling operations.
	 * @return Timeout in milliseconds
	 */
	public long getTimeout() {
		return timeout;
	}

	/**
	 * Adds content to an asset using the PUT API endpoint.
	 * 
	 * @param assetID The asset ID to add content to
	 * @param content The content to add
	 * @return Future that completes when the content is successfully added
	 * @throws IOException If not possible to obtain the content
	 */
	public CompletableFuture<Hash> addContent(String assetID, AContent content) throws IOException {
		Hash h = Assets.parseAssetID(assetID);
		if (h == null) throw new IllegalArgumentException("Bad asset ID format");
		
		HttpRequest req = requestBuilder("assets/" + h.toHexString() + "/content")
			.header("Content-Type", "application/octet-stream")
			.PUT(HttpRequest.BodyPublishers.ofByteArray(content.getBlob().getBytes()))
			.build();
		
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApplyAsync(response -> {
			if (response.statusCode() == 200 || response.statusCode() == 201) {
				ACell json=JSON.parse(response.body());
				if (json instanceof AString hashString) {
					Hash hash=Hash.parse(hashString);
					if (hash!=null) {
						return hash;
					}
				}
				throw new ConversionException("Failed to parse content hash from response: " + response.body());
			} else {
				throw new ResponseException("Failed to add content: " + response.statusCode() + " - " + response.body(), response);
			}
		});
	
	}
	
	/**
	 * Gets content for an asset using the GET API endpoint.
	 * 
	 * @param assetID The asset ID to get content for
	 * @return Future containing the content as an AContent, or null if not found
	 */
	public CompletableFuture<AContent> getContent(String assetID) {
		Hash id = Assets.parseAssetID(assetID);
		return getContent(id);
	}
	
	/**
	 * Gets content for an asset using the GET API endpoint.
	 * 
	 * @param assetID The asset ID to get content for
	 * @return Future containing the content as an AContent, or null if not found
	 */
	public CompletableFuture<AContent> getContent(Hash assetID) {

		HttpRequest req = requestBuilder("assets/" + assetID.toHexString() + "/content")
			.GET()
			.build();

		return dispatch(req, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
			int code=response.statusCode();
			if (code != 200) {
				throw new ResponseException("Content get failed with status: " +code+" -- asset ID: "+assetID);
			}

			byte[] data = response.body();
			convex.core.data.Blob blob = convex.core.data.Blob.wrap(data);
			return BlobContent.of(blob);
		});
	}
	
	@Override
	protected AContent getAssetContent(Hash assetID) {
		return getContent(assetID).join();
	}

	/**
	 * Cancels a job on the connected venue
	 * @param jobId The job ID to cancel
	 * @return Future that completes when the job is successfully cancelled, or completes exceptionally if the job doesn't exist
	 */
	public CompletableFuture<AMap<AString,ACell>> cancelJob(String jobId) {
		HttpRequest req = requestBuilder("jobs/" + jobId + "/cancel")
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();
		
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return JSON.parse(response.body()); // Success
			} else if (code == 404) {
				throw new ResponseException("Job not found: " + jobId, response);
			} else {
				throw new ResponseException("Failed to cancel job: " + code + " - " + response.body(), response);
			}
		});
	}

	/**
	 * Pauses a running job on the connected venue
	 * @param jobId The job ID to pause
	 * @return Future containing the updated job status
	 */
	public CompletableFuture<AMap<AString,ACell>> pauseJob(String jobId) {
		HttpRequest req = requestBuilder("jobs/" + jobId + "/pause")
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();

		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return JSON.parse(response.body());
			} else if (code == 404) {
				throw new ResponseException("Job not found: " + jobId, response);
			} else {
				throw new ResponseException("Failed to pause job: " + code + " - " + response.body(), response);
			}
		});
	}

	/**
	 * Resumes a paused job on the connected venue
	 * @param jobId The job ID to resume
	 * @return Future containing the updated job status
	 */
	public CompletableFuture<AMap<AString,ACell>> resumeJob(String jobId) {
		HttpRequest req = requestBuilder("jobs/" + jobId + "/resume")
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();

		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return JSON.parse(response.body());
			} else if (code == 404) {
				throw new ResponseException("Job not found: " + jobId, response);
			} else {
				throw new ResponseException("Failed to resume job: " + code + " - " + response.body(), response);
			}
		});
	}

	/**
	 * Deletes a job from the connected venue
	 * @param jobId The job ID to delete
	 * @return Future that completes when the job is successfully deleted, or completes exceptionally if the job doesn't exist
	 */
	public CompletableFuture<Void> deleteJob(String jobId) {
		HttpRequest req = requestBuilder("jobs/" + jobId + "/delete")
			.PUT(HttpRequest.BodyPublishers.ofString(""))
			.build();
		
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code = response.statusCode();
			if (code == 200) {
				return null; // Success
			} else if (code == 404) {
				throw new ResponseException("Job not found: " + jobId, response);
			} else {
				throw new ResponseException("Failed to delete job: " + code + " - " + response.body(), response);
			}
		});
	}

	/**
	 * Gets the status of a job by job ID.
	 * @param jobID The job ID to check
	 * @return Future containing the job status as an AMap, or null if not found
	 */
	public CompletableFuture<AMap<AString, ACell>> getJobData(Blob jobID) {
		HttpRequest req = requestBuilder("jobs/" + jobID.toHexString())
			.GET()
			.build();
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			int code=response.statusCode();
			if (code == 200) {
				return RT.castMap(JSON.parse(response.body()));
			} else if (code == 404) {
				return null;
			} else {
				throw new ResponseException("Failed to get job status: " + response.statusCode() + " - " + response.body(), response);
			}
		});
	}
	
	/**
	 * Gets the status of a job by job ID.
	 * @param jobID The job ID to check
	 * @return Future containing the job status as an AMap, or null if not found
	 */
	public CompletableFuture<AMap<AString, ACell>> getJobData(Object jobID) {
		return getJobData(Job.parseID(jobID));
	}
	
	/**
	 * Perform a remote status update for the given job
	 * @param job Job to update
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws TimeoutException
	 */
	public void updateJobStatus(Job job) throws InterruptedException, ExecutionException, TimeoutException {
		Blob jobID=job.getID();
		CompletableFuture<AMap<AString, ACell>> future = getJobData(jobID).thenApply(data->{
			job.updateData(data);
			// System.out.println(JSONUtils.toJSONPretty(data));
			return data;
		});
		future.get(5,TimeUnit.SECONDS);
	}

	@Override
	public Hash getOperationId(String name) throws IOException {
		// Catalog names contain slashes; the operations/{name} route takes a
		// single path segment, so the name travels percent-encoded.
		String encoded=java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8);
		HttpRequest request=requestBuilder("operations/"+encoded)
				.GET()
				.build();

		try {
			HttpResponse<String> response = sendSync(request);
			if (response.statusCode()!=200) return null;
			ACell result=JSON.parse(response.body());
			ACell idHex=RT.getIn(result, "asset");
			return (idHex==null) ? null : Hash.parse(idHex.toString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	@Override
	public Asset getAsset(Hash id) throws IOException {
		HttpRequest request=requestBuilder("assets/"+id.toHexString())
				.GET()
				.build();
			
		HttpResponse<String> response;
		try {
			response = sendSync(request);
			if (response.statusCode()!=200) return null;
			AString metadata=Strings.create(response.body());
			
			Asset asset=Asset.forString(metadata);
			asset.setVenue(this);
			return asset;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		
	}

	/**
	 * Resolves an asset by any lattice address — a bare hash, {@code a/<hash>},
	 * a workspace/operation path ({@code w/…}, {@code o/…}), or a DID URL — by
	 * delegating resolution to the venue's {@code GET assets/<ref>} endpoint
	 * (#150). The venue resolves the address and returns the canonical metadata;
	 * the returned Asset's {@link Asset#getID()} is the resolved content-addressed
	 * id, which self-verifies for content-addressed references.
	 *
	 * @param ref Asset reference (lattice address or bare hash)
	 * @return the resolved Asset, or null if not found
	 */
	@Override
	public Asset resolveAsset(String ref) throws IOException {
		if (ref==null) return null;
		HttpRequest request=requestBuilder("assets/"+ref)
				.GET()
				.build();
		try {
			HttpResponse<String> response = sendSync(request);
			if (response.statusCode()!=200) return null;
			Asset asset=Asset.forString(Strings.create(response.body()));
			asset.setVenue(this);
			return asset;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	@Override
	public DID getDID() {
		return DID.create("web",baseURI.getHost());
	}

	// ------------------------------------------------------------------
	// Abstract Venue method implementations
	// ------------------------------------------------------------------

	@Override
	public Hash registerAsset(AString metadata) throws IOException {
		return addAsset(metadata.toString()).join();
	}

	@Override
	public long getAssetCount() {
		return getAssets().join().size();
	}

	@Override
	public List<Hash> listAssetIDs(long offset, long limit) {
		List<Hash> all = getAssets().join();
		int start = (int) Math.max(0, offset);
		int end = (int) Math.min(all.size(), start + limit);
		if (start >= all.size()) return List.of();
		return all.subList(start, end);
	}

	@Override
	public Hash putAssetContent(Asset asset, InputStream content) throws IOException {
		byte[] data = content.readAllBytes();
		convex.core.data.Blob blob = convex.core.data.Blob.wrap(data);
		AContent acontent = BlobContent.of(blob);
		return addContent(asset.getID().toHexString(), acontent).join();
	}

	@Override
	public AMap<AString, ACell> cancelJob(Blob jobId) {
		return cancelJob(jobId.toHexString()).join();
	}

	@Override
	public AMap<AString, ACell> pauseJob(Blob jobId) {
		return pauseJob(jobId.toHexString()).join();
	}

	@Override
	public AMap<AString, ACell> resumeJob(Blob jobId) {
		return resumeJob(jobId.toHexString()).join();
	}

	@Override
	public boolean deleteJob(Blob jobId) {
		try {
			deleteJob(jobId.toHexString()).join();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public List<Blob> listJobs() {
		HttpRequest req = requestBuilder("jobs")
			.GET()
			.build();

		try {
			HttpResponse<String> response = sendSync(req);
			if (response.statusCode() != 200) {
				throw new ResponseException("Failed to list jobs: " + response.statusCode());
			}
			ACell body = JSON.parseJSON5(response.body());
			AVector<?> items = null;
			if (body instanceof AVector<?> v) {
				items = v;
			} else if (body instanceof AMap<?,?> m) {
				items = RT.ensureVector(m.get(Fields.ITEMS));
			}
			ArrayList<Blob> result = new ArrayList<>();
			if (items != null) {
				for (long i = 0; i < items.count(); i++) {
					result.add(Job.parseID(items.get(i)));
				}
			}
			return result;
		} catch (IOException | InterruptedException e) {
			throw new ResponseException("Failed to list jobs", e);
		}
	}

	@Override
	public int sendMessage(String jobId, AMap<AString, ACell> message) {
		HttpRequest req = requestBuilder("jobs/" + jobId)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(message)))
			.build();

		try {
			HttpResponse<String> response = sendSync(req);
			int code = response.statusCode();
			if (code == 202) {
				AMap<AString, ACell> body = RT.ensureMap(JSON.parseJSON5(response.body()));
				if (body != null) {
					ACell depth = body.get(Strings.create("queueDepth"));
					CVMLong cl = RT.ensureLong(depth);
					if (cl != null) return (int) cl.longValue();
				}
				return 0;
			} else if (code == 404) {
				throw new IllegalArgumentException("Job not found: " + jobId);
			} else if (code == 409) {
				throw new IllegalStateException("Job is in terminal state: " + jobId);
			}
			throw new ResponseException("Failed to send message: HTTP " + code);
		} catch (IOException | InterruptedException e) {
			throw new ResponseException("Failed to send message to job " + jobId, e);
		}
	}

	// ========== Job-free reads (#177, #180) ==========

	/**
	 * Job-free lattice value read ({@code GET /api/v1/values/<accessor>}) —
	 * synchronous, capability-checked, and <b>no job is persisted</b> on the
	 * venue. Use for reads instead of invoking {@code covia:*} ops, which mint
	 * a durable job record each.
	 *
	 * @param accessor One of {@code read}, {@code list}, {@code slice},
	 *        {@code inspect}, {@code aggregate}, {@code count}
	 * @param path Lattice path, e.g. {@code "w/notes"} or {@code "v/info/adapters"}
	 * @return Future for the structured result map
	 */
	public CompletableFuture<AMap<AString,ACell>> getValue(String accessor, String path) {
		String encoded = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8);
		HttpRequest req = requestBuilder("values/" + accessor + "?path=" + encoded)
			.GET()
			.build();
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new ResponseException("Value read failed: " + response + " = " + response.body(), response);
			}
			return RT.castMap(JSON.parse(response.body()));
		});
	}

	/**
	 * Job-free listing of the authenticated caller's agents
	 * ({@code GET /api/v1/agents}, #180) — the {@code agent:list} payload with
	 * <b>no job persisted</b>. Terminated agents are hidden by default.
	 *
	 * @return Future for the agent list result map
	 */
	public CompletableFuture<AMap<AString,ACell>> getAgents() {
		HttpRequest req = requestBuilder("agents").GET().build();
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new ResponseException("Agent list failed: " + response + " = " + response.body(), response);
			}
			return RT.castMap(JSON.parse(response.body()));
		});
	}

	/**
	 * Job-free read of one of the authenticated caller's agents
	 * ({@code GET /api/v1/agents/<id>}, #180) — the {@code agent:info} payload
	 * with <b>no job persisted</b>.
	 *
	 * @param agentId Agent id
	 * @return Future for the agent info map
	 */
	public CompletableFuture<AMap<AString,ACell>> getAgent(String agentId) {
		HttpRequest req = requestBuilder("agents/" + agentId).GET().build();
		return dispatch(req, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() != 200) {
				throw new ResponseException("Agent info failed: " + response + " = " + response.body(), response);
			}
			return RT.castMap(JSON.parse(response.body()));
		});
	}

	// ========== UCAN verify ==========

	/**
	 * Verifies a UCAN token against the venue's trust policy via the
	 * {@code ucan:verify} diagnostic op. The result map carries
	 * {@code valid} / {@code reason}, {@code chainDepth}, {@code rootIssuer},
	 * and per-capability {@code rootAuthority} verdicts
	 * ({@code owner} / {@code venue} / {@code refused}).
	 *
	 * @param token The UCAN JWT to verify
	 * @return The verification result map
	 * @throws InterruptedException if interrupted while waiting
	 */
	public AMap<AString,ACell> verifyUcan(String token) throws InterruptedException {
		return verifyUcan(token, null, null, null);
	}

	/**
	 * Verifies a UCAN token, additionally asking whether it would authorise a
	 * specific request: the result map's {@code authorises} field reports
	 * whether {@code (with, can)} is covered for the presenting audience
	 * {@code aud} (venue-side default: the caller).
	 *
	 * @param token The UCAN JWT to verify
	 * @param with Resource for the would-it-authorise check (null to omit)
	 * @param can Ability for the would-it-authorise check (null to omit)
	 * @param aud Presenting audience DID (null to omit)
	 * @return The verification result map
	 * @throws InterruptedException if interrupted while waiting
	 */
	public AMap<AString,ACell> verifyUcan(String token, String with, String can, String aud) throws InterruptedException {
		AMap<AString,ACell> input = Maps.of(Strings.intern("token"), Strings.create(token));
		if (with != null) input = input.assoc(Strings.intern("with"), Strings.create(with));
		if (can != null) input = input.assoc(Strings.intern("can"), Strings.create(can));
		if (aud != null) input = input.assoc(Strings.intern("aud"), Strings.create(aud));
		Job job = invokeAndWait(Strings.create("v/ops/ucan/verify"), input);
		return RT.ensureMap(job.getOutput());
	}
}
