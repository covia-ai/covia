package covia.adapter.sonnylabs;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.exception.JobFailedException;
import covia.venue.RequestContext;

/** SonnyLabs AI-firewall adapter backed by {@code POST /v1/scans}. */
public class SonnyLabsAdapter extends AAdapter {

	public static final String NAME = "sonnylabs";
	static final String DEFAULT_BASE_URL = "https://api.sonnylabs.ai";
	static final String DEFAULT_API_KEY_REF = "s/SONNY_LABS";
	static final long DEFAULT_TIMEOUT_MILLIS = 30_000;
	private static final long MAX_TIMEOUT_MILLIS = 3_600_000;

	private static final AString K_ENABLED = Strings.intern("enabled");
	private static final AString K_BASE_URL = Strings.intern("baseUrl");
	private static final AString K_API_KEY = Strings.intern("apiKey");
	private static final AString K_API_VERSION = Strings.intern("apiVersion");
	private static final AString K_TIMEOUT_MILLIS = Strings.intern("timeoutMillis");
	private static final Set<AString> CONFIG_KEYS = Set.of(
		K_ENABLED, K_BASE_URL, K_API_KEY, K_API_VERSION, K_TIMEOUT_MILLIS);

	private static final AString K_TEXT = Strings.intern("text");
	private static final AString K_SURFACE = Strings.intern("surface");
	private static final AString K_TIER = Strings.intern("tier");
	private static final AString K_CAPTURE = Strings.intern("capture");
	private static final AString K_POLICY_ID = Strings.intern("policyId");
	private static final AString K_CONTEXT = Strings.intern("context");
	private static final AString K_IDEMPOTENCY_KEY = Strings.intern("idempotencyKey");

	private static final Set<String> SURFACES = Set.of(
		"user_message", "assistant_output", "tool_result", "tool_params",
		"document", "agent_message", "mcp_resource", "mcp_tool_description");
	private static final Set<String> TIERS = Set.of("fast", "accurate", "auto");
	private static final Set<String> ACTIONS = Set.of("blocked", "flagged", "warned", "allowed");

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.executor(VIRTUAL_EXECUTOR)
		.build();

	private volatile String baseUrl = DEFAULT_BASE_URL;
	private volatile String apiKeyRef = DEFAULT_API_KEY_REF;
	private volatile String apiVersion;
	private volatile long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Tests prompts and other LLM-facing text with the SonnyLabs AI firewall for "
			+ "prompt injection and related safety findings.";
	}

	@Override
	protected void installAssets() {
		installAsset("sonnylabs/scan", "/adapters/sonnylabs/scan.json");
		installSkill("ops-tools/sonnylabs", "/skills/sonnylabs.json");
	}

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) config = Maps.empty();
		if (strict) {
			for (long i = 0; i < config.count(); i++) {
				ACell key = config.entryAt(i).getKey();
				if (!(key instanceof AString text) || !CONFIG_KEYS.contains(text)) {
					throw new IllegalArgumentException("adapters.sonnylabs: unknown setting " + key
						+ " (known: baseUrl, apiKey, apiVersion, timeoutMillis, enabled)");
				}
			}
		}

		String nextBaseUrl = stringSetting(config, K_BASE_URL, DEFAULT_BASE_URL);
		String nextApiKeyRef = stringSetting(config, K_API_KEY, DEFAULT_API_KEY_REF);
		String nextApiVersion = optionalString(config, K_API_VERSION);
		long nextTimeout = longSetting(config, K_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS);

		validateBaseUrl(nextBaseUrl);
		validateSecretRef(nextApiKeyRef, "adapters.sonnylabs.apiKey");
		if (nextApiVersion != null && !nextApiVersion.matches("\\d{4}-\\d{2}-\\d{2}")) {
			throw new IllegalArgumentException(
				"adapters.sonnylabs.apiVersion must be a date in YYYY-MM-DD form");
		}
		if (nextTimeout < 1 || nextTimeout > MAX_TIMEOUT_MILLIS) {
			throw new IllegalArgumentException("adapters.sonnylabs.timeoutMillis must be between 1 and "
				+ MAX_TIMEOUT_MILLIS);
		}

		this.baseUrl = stripTrailingSlashes(nextBaseUrl);
		this.apiKeyRef = nextApiKeyRef;
		this.apiVersion = nextApiVersion;
		this.timeoutMillis = nextTimeout;
		return true;
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (!"scan".equals(subOp)) {
			throw new UnsupportedOperationException("Unsupported SonnyLabs operation: " + subOp);
		}
		return CompletableFuture.supplyAsync(() -> scan(ctx, input), VIRTUAL_EXECUTOR);
	}

	ACell scan(RequestContext ctx, ACell input) {
		AString textCell = RT.ensureString(RT.getIn(input, K_TEXT));
		if (textCell == null || textCell.isEmpty()) {
			throw new IllegalArgumentException("text must be a non-empty string");
		}

		String surface = optionalInputString(input, K_SURFACE, "user_message");
		if (!SURFACES.contains(surface)) {
			throw new IllegalArgumentException("surface must be one of " + SURFACES);
		}
		String tier = optionalInputString(input, K_TIER, null);
		if (tier != null && !TIERS.contains(tier)) {
			throw new IllegalArgumentException("tier must be fast, accurate, or auto");
		}

		ACell captureCell = RT.getIn(input, K_CAPTURE);
		if (captureCell != null && !(captureCell instanceof CVMBool)) {
			throw new IllegalArgumentException("capture must be a boolean");
		}
		boolean capture = captureCell != null && RT.bool(captureCell);
		AString policyId = optionalInputAString(input, K_POLICY_ID);
		AMap<AString, ACell> context = optionalInputMap(input, K_CONTEXT);

		AMap<AString, ACell> options = Maps.of("capture", capture);
		if (tier != null) options = options.assoc(K_TIER, Strings.create(tier));
		if (policyId != null) options = options.assoc(Strings.intern("policy_id"), policyId);
		AMap<AString, ACell> requestBody = Maps.of(
			"kind", "content",
			"surface", surface,
			"content", Maps.of("type", "text", "text", textCell),
			"options", options);
		if (context != null && !context.isEmpty()) requestBody = requestBody.assoc(K_CONTEXT, context);

		String token = resolveApiKey(ctx, input);
		String idempotencyKey = optionalInputString(input, K_IDEMPOTENCY_KEY, null);
		if (idempotencyKey == null) idempotencyKey = UUID.randomUUID().toString();
		if (idempotencyKey.isEmpty() || idempotencyKey.length() > 255) {
			throw new IllegalArgumentException("idempotencyKey must contain 1 to 255 characters");
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/v1/scans"))
			.timeout(Duration.ofMillis(timeoutMillis))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.header("Idempotency-Key", idempotencyKey)
			.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(requestBody)));
		String version = apiVersion;
		if (version != null) builder.header("Sonny-Api-Version", version);

		try {
			HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw apiFailure(response);
			}
			ACell result;
			try {
				result = JSON.parse(response.body());
			} catch (RuntimeException e) {
				throw new JobFailedException("SonnyLabs returned invalid JSON (HTTP "
					+ response.statusCode() + ")");
			}
			if (!(result instanceof AMap<?, ?>)) {
				throw new JobFailedException("SonnyLabs returned a non-object scan result");
			}
			AString action = RT.ensureString(RT.getIn(result, "decision", "action"));
			if (action == null || !ACTIONS.contains(action.toString())) {
				throw new JobFailedException("SonnyLabs returned a scan result without a valid decision");
			}
			return result;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new JobFailedException("SonnyLabs scan was interrupted");
		} catch (IOException e) {
			throw new JobFailedException("SonnyLabs scan request failed: " + rootMessage(e));
		}
	}

	private String resolveApiKey(RequestContext ctx, ACell input) {
		AString callerRef = optionalInputAString(input, K_API_KEY);
		String ref = callerRef == null ? apiKeyRef : callerRef.toString();
		validateSecretRef(ref, callerRef == null
			? "adapters.sonnylabs.apiKey" : "apiKey");
		RequestContext secretOwner = callerRef == null ? engine.venueContext() : ctx;
		String value = engine.resolveSecret(ref, secretOwner);
		if (value == null || value.isBlank()) {
			String location = callerRef == null
				? "the configured venue secret-store location"
				: "the caller secret store at " + ref;
			throw new JobFailedException("SonnyLabs API key not found in " + location
				+ "; store a scans:write key with secret:set");
		}
		return value;
	}

	private static JobFailedException apiFailure(HttpResponse<String> response) {
		String code = null;
		String detail = null;
		try {
			AMap<AString, ACell> problem = RT.ensureMap(JSON.parse(response.body()));
			if (problem != null) {
				AString codeCell = RT.ensureString(problem.get(Strings.intern("code")));
				AString detailCell = RT.ensureString(problem.get(Strings.intern("detail")));
				if (codeCell != null) code = codeCell.toString();
				if (detailCell != null) detail = detailCell.toString();
			}
		} catch (RuntimeException ignored) {
			// A non-problem response still reports its HTTP status and request id.
		}
		StringBuilder message = new StringBuilder("SonnyLabs scan failed (HTTP ")
			.append(response.statusCode());
		if (code != null) message.append(", ").append(code);
		message.append(')');
		if (detail != null && !detail.isBlank()) message.append(": ").append(detail);
		response.headers().firstValue("X-Request-Id")
			.ifPresent(id -> message.append(" [requestId=").append(id).append(']'));
		return new JobFailedException(message.toString());
	}

	private static String optionalInputString(ACell input, AString key, String fallback) {
		AString value = optionalInputAString(input, key);
		return value == null ? fallback : value.toString();
	}

	private static AString optionalInputAString(ACell input, AString key) {
		ACell value = RT.getIn(input, key);
		if (value == null) return null;
		AString text = RT.ensureString(value);
		if (text == null) throw new IllegalArgumentException(key + " must be a string");
		return text;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> optionalInputMap(ACell input, AString key) {
		ACell value = RT.getIn(input, key);
		if (value == null) return null;
		if (!(value instanceof AMap<?, ?> map)) {
			throw new IllegalArgumentException(key + " must be an object");
		}
		return (AMap<AString, ACell>) map;
	}

	private static String stringSetting(AMap<AString, ACell> config, AString key,
			String fallback) {
		String value = optionalString(config, key);
		return value == null ? fallback : value;
	}

	private static String optionalString(AMap<AString, ACell> config, AString key) {
		ACell value = config.get(key);
		if (value == null) return null;
		AString text = RT.ensureString(value);
		if (text == null || text.isEmpty()) {
			throw new IllegalArgumentException("adapters.sonnylabs." + key
				+ " must be a non-empty string");
		}
		return text.toString();
	}

	private static long longSetting(AMap<AString, ACell> config, AString key,
			long fallback) {
		ACell value = config.get(key);
		if (value == null) return fallback;
		CVMLong number = RT.ensureLong(value);
		if (number == null) {
			throw new IllegalArgumentException("adapters.sonnylabs." + key + " must be an integer");
		}
		return number.longValue();
	}

	private static void validateSecretRef(String ref, String field) {
		if (ref == null || !(ref.startsWith("s/") || ref.startsWith("/s/"))
				|| ref.length() <= (ref.startsWith("/") ? 3 : 2)) {
			throw new IllegalArgumentException(field
				+ " must be an s/NAME secret reference, never a raw API key");
		}
	}

	private static void validateBaseUrl(String value) {
		try {
			URI uri = new URI(value);
			if (!uri.isAbsolute() || uri.getHost() == null
					|| !("http".equalsIgnoreCase(uri.getScheme())
						|| "https".equalsIgnoreCase(uri.getScheme()))
					|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
				throw new IllegalArgumentException("adapters.sonnylabs.baseUrl must be an absolute "
					+ "HTTP(S) URL without credentials, query, or fragment");
			}
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("adapters.sonnylabs.baseUrl must be a valid HTTP(S) URL", e);
		}
	}

	private static String stripTrailingSlashes(String value) {
		int end = value.length();
		while (end > 0 && value.charAt(end - 1) == '/') end--;
		return value.substring(0, end);
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) current = current.getCause();
		String message = current.getMessage();
		return message == null || message.isBlank()
			? current.getClass().getSimpleName() : message;
	}
}
