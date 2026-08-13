package covia.venue.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.did.DID;
import convex.auth.did.DIDVerifier;
import convex.core.crypto.ASignature;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.Config;
import covia.venue.Engine;

/**
 * DID signature verifier for the identities a venue accepts (covia#343).
 *
 * <p>Resolution order, all fail-closed per the {@link DIDVerifier} contract
 * (unresolvable or erroring verifies {@code false}, never throws):</p>
 *
 * <ol>
 *   <li>The venue's own declared identity — the venue's current key, without
 *       branching on its DID method.</li>
 *   <li>Locally managed users ({@code did:web:<host>:u:<name>}) — the user's
 *       <b>active</b> registered authentication keys, match-any, resolved
 *       from venue state with no outbound fetch.</li>
 *   <li>Remote identities — dispatched to a verifier registered for the DID
 *       method. Built-ins cover stateless {@code did:key} and HTTPS-resolved
 *       {@code did:web}; another method such as {@code did:convex} can be added
 *       without changing authentication, UCAN, or federation code.</li>
 * </ol>
 */
public final class VenueDIDVerifier implements DIDVerifier {

	private static final Logger log = LoggerFactory.getLogger(VenueDIDVerifier.class);

	/** Successful remote resolutions are cached this long — the bound on how
	 *  stale a rotated remote key set can be at this venue. */
	private static final long REMOTE_CACHE_TTL_MILLIS = 300_000;
	private static final long MISMATCH_REFRESH_COOLDOWN_MILLIS = 30_000;

	private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(FETCH_TIMEOUT)
		.build();

	private final Engine engine;
	private final ConcurrentHashMap<String, DIDVerifier> methodVerifiers =
		new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, CachedKeys> remoteCache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> mismatchRefreshes = new ConcurrentHashMap<>();

	private record CachedKeys(List<AccountKey> keys, long expiresAtMillis) {}

	public VenueDIDVerifier(Engine engine) {
		if (engine == null) throw new IllegalArgumentException("engine is required");
		this.engine = engine;
		methodVerifiers.put("key", DIDVerifier.CONVEX);
		methodVerifiers.put("web", this::verifyRemoteWeb);
	}

	/**
	 * Registers the trusted signature verifier for one DID method. Registration
	 * changes resolution only; the DID string remains the principal everywhere
	 * else. This is the extension seam for methods supplied by future venue
	 * modules (for example {@code did:convex}).
	 */
	public void registerMethod(String method, DIDVerifier verifier) {
		if (method == null || !method.matches("[a-z0-9]+")) {
			throw new IllegalArgumentException("DID method must be lower-case letters/digits");
		}
		if (verifier == null) throw new IllegalArgumentException("verifier is required");
		methodVerifiers.put(method, verifier);
	}

	@Override
	public boolean verifies(AString did, Blob message, Blob signature) {
		if (did == null || message == null || signature == null) return false;
		try {
			if (isOwnVenue(did)) {
				return verifyWith(engine.getAccountKey(), message, signature);
			}
			AString userId = engine.managedUserName(did);
			if (userId != null) {
				return verifyManagedUser(userId, message, signature);
			}
			DID parsed = DID.fromString(did.toString());
			if (parsed == null) return false;
			DIDVerifier verifier = methodVerifiers.get(parsed.getMethod());
			return verifier != null && verifier.verifies(did, message, signature);
		} catch (Throwable t) {
			return false; // fail closed, never throw
		}
	}

	/** The venue's declared identity, plus its legacy published web alias. */
	private boolean isOwnVenue(AString did) {
		if (did.equals(engine.getDIDString())) return true;
		AString web = engine.config().getWebDID();
		return web != null && web.equals(did);
	}

	private boolean verifyManagedUser(AString userId, Blob message, Blob signature) {
		AVector<ACell> active = engine.getAuth().getActiveAuthenticationKeys(userId);
		for (long i = 0; i < active.count(); i++) {
			AString keyDID = RT.ensureString(active.get(i));
			if (keyDID == null || !keyDID.toString().startsWith("did:key:")) continue;
			AccountKey key = Multikey.decodePublicKey(
				keyDID.toString().substring("did:key:".length()));
			if (verifyWith(key, message, signature)) return true;
		}
		return false;
	}

	private boolean verifyRemoteWeb(AString did, Blob message, Blob signature) {
		String value = did.toString();
		if (!value.startsWith("did:web:")) return false;
		long now = System.currentTimeMillis();
		CachedKeys before = remoteCache.get(value);
		boolean usedFreshCache = before != null && now < before.expiresAtMillis();
		List<AccountKey> keys = resolveRemoteKeys(value, false);
		if (keys == null) return false;
		for (AccountKey key : keys) {
			if (verifyWith(key, message, signature)) return true;
		}
		// A just-fetched authoritative document already gave us its current keys;
		// do not immediately fetch it a second time for an invalid signature.
		if (!usedFreshCache) return false;
		// A valid token signed immediately after rotation may arrive before the
		// five-minute success cache expires. Refresh on key mismatch, throttled so
		// forged signatures cannot turn verification into an outbound-fetch loop.
		Long prior = mismatchRefreshes.putIfAbsent(value, now);
		if (prior != null) {
			if (now - prior < MISMATCH_REFRESH_COOLDOWN_MILLIS) return false;
			if (!mismatchRefreshes.replace(value, prior, now)) return false;
		}
		List<AccountKey> refreshed = resolveRemoteKeys(value, true);
		if (refreshed == null) return false;
		for (AccountKey key : refreshed) {
			if (verifyWith(key, message, signature)) return true;
		}
		return false;
	}

	/** Resolves a remote did:web document's verification keys, cached on success. */
	private List<AccountKey> resolveRemoteKeys(String did, boolean bypassCache) {
		CachedKeys cached = remoteCache.get(did);
		long now = System.currentTimeMillis();
		if (!bypassCache && cached != null && now < cached.expiresAtMillis()) return cached.keys();

		String url = didWebDocumentURL(did);
		if (url == null) return null;
		try {
			HttpResponse<String> resp = HTTP.send(
				HttpRequest.newBuilder(URI.create(url)).timeout(FETCH_TIMEOUT).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) return null;
			ACell doc = JSON.parse(resp.body());
			// Resolution integrity: the document must be about the DID we asked for.
			AString id = RT.ensureString(RT.getIn(doc, "id"));
			if (id == null || !did.equals(id.toString())) return null;
			List<AccountKey> keys = authorisedVerificationKeys(doc);
			if (keys.isEmpty()) return null;
			remoteCache.put(did, new CachedKeys(List.copyOf(keys), now + REMOTE_CACHE_TTL_MILLIS));
			return keys;
		} catch (Exception e) {
			log.debug("did:web resolution failed for {}: {}", did, e.getMessage());
			return null;
		}
	}

	/** Extracts only verification methods authorised for a signing purpose. */
	private static List<AccountKey> authorisedVerificationKeys(ACell doc) {
		var methodsById = new java.util.HashMap<String, AccountKey>();
		ACell methods = RT.getIn(doc, "verificationMethod");
		if (methods instanceof AVector<?> mv) {
			for (long i = 0; i < mv.count(); i++) {
				AString id = RT.ensureString(RT.getIn(mv.get(i), "id"));
				AString mk = RT.ensureString(RT.getIn(mv.get(i), "publicKeyMultibase"));
				AccountKey key = (mk != null) ? Multikey.decodePublicKey(mk.toString()) : null;
				if (id != null && key != null) methodsById.put(id.toString(), key);
			}
		}
		Set<AccountKey> authorised = new LinkedHashSet<>();
		for (String purpose : List.of("authentication", "assertionMethod",
				"capabilityDelegation", "capabilityInvocation")) {
			ACell refs = RT.getIn(doc, purpose);
			if (refs instanceof AVector<?> rv) {
				for (long i = 0; i < rv.count(); i++) {
					addAuthorisedMethod(rv.get(i), methodsById, authorised);
				}
			} else {
				// DID Core permits a single verification relationship value too.
				addAuthorisedMethod(refs, methodsById, authorised);
			}
		}
		return new ArrayList<>(authorised);
	}

	/** A verification relationship may reference a method by id or embed it. */
	private static void addAuthorisedMethod(ACell relationship,
			java.util.Map<String, AccountKey> methodsById, Set<AccountKey> authorised) {
		if (relationship == null) return;
		AString ref = RT.ensureString(relationship);
		if (ref != null) {
			AccountKey key = methodsById.get(ref.toString());
			if (key != null) authorised.add(key);
			return;
		}
		AString mk = RT.ensureString(RT.getIn(relationship, "publicKeyMultibase"));
		AccountKey embedded = (mk != null) ? Multikey.decodePublicKey(mk.toString()) : null;
		if (embedded != null) authorised.add(embedded);
	}

	/**
	 * The did:web resolution URL: colon-separated segments after the host map
	 * to URL path segments; a bare host resolves to {@code /.well-known/did.json}.
	 * HTTPS only, and only for plausible public hostnames (SSRF containment —
	 * loopback, bare names, and IP literals never resolve).
	 */
	public static String didWebDocumentURL(String did) {
		if (did == null || !did.startsWith("did:web:")) return null;
		String rest = did.substring("did:web:".length());
		String[] segments = rest.split(":", -1); // keep trailing empties: "host:" is malformed
		String host = segments[0];
		if (!Config.isPublicHostname(host)) return null;
		if (segments.length == 1) {
			return "https://" + host + "/.well-known/did.json";
		}
		StringBuilder sb = new StringBuilder("https://").append(host);
		for (int i = 1; i < segments.length; i++) {
			if (segments[i].isEmpty()) return null;
			sb.append('/').append(segments[i]);
		}
		return sb.append("/did.json").toString();
	}

	private static boolean verifyWith(AccountKey key, Blob message, Blob signature) {
		if (key == null) return false;
		try {
			ASignature sig = ASignature.fromBlob(signature);
			return sig != null && sig.verify(message, key);
		} catch (Throwable t) {
			return false;
		}
	}
}
