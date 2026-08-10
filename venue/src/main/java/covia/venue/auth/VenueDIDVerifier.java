package covia.venue.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@code did:key} — stateless multikey verification
 *       ({@link DIDVerifier#CONVEX}).</li>
 *   <li>The venue's own identity (declared did:web or key-derived did:key,
 *       in either published form) — the venue's current key.</li>
 *   <li>Locally managed users ({@code did:web:<host>:u:<name>}) — the user's
 *       <b>active</b> registered authentication keys, match-any, resolved
 *       from venue state with no outbound fetch.</li>
 *   <li>Remote {@code did:web} — verification methods resolved from the DID
 *       document over HTTPS (the did:web resolution algorithm), cached on
 *       success. Consumers respect the presented identity: resolution never
 *       re-binds through {@code alsoKnownAs}.</li>
 * </ol>
 */
public final class VenueDIDVerifier implements DIDVerifier {

	private static final Logger log = LoggerFactory.getLogger(VenueDIDVerifier.class);

	/** Successful remote resolutions are cached this long — the bound on how
	 *  stale a rotated remote key set can be at this venue. */
	private static final long REMOTE_CACHE_TTL_MILLIS = 300_000;

	private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

	private final Engine engine;
	private final ConcurrentHashMap<String, CachedKeys> remoteCache = new ConcurrentHashMap<>();

	private record CachedKeys(List<AccountKey> keys, long expiresAtMillis) {}

	public VenueDIDVerifier(Engine engine) {
		if (engine == null) throw new IllegalArgumentException("engine is required");
		this.engine = engine;
	}

	@Override
	public boolean verifies(AString did, Blob message, Blob signature) {
		if (did == null || message == null || signature == null) return false;
		try {
			String d = did.toString();
			if (d.startsWith("did:key:")) {
				return DIDVerifier.CONVEX.verifies(did, message, signature);
			}
			if (!d.startsWith("did:web:")) return false;
			if (isOwnVenue(did)) {
				return verifyWith(engine.getAccountKey(), message, signature);
			}
			AString userId = engine.managedUserName(did);
			if (userId != null) {
				return verifyManagedUser(userId, message, signature);
			}
			return verifyRemote(d, message, signature);
		} catch (Throwable t) {
			return false; // fail closed, never throw
		}
	}

	/** The venue's own identity in either published form. */
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

	private boolean verifyRemote(String did, Blob message, Blob signature) {
		List<AccountKey> keys = resolveRemoteKeys(did);
		if (keys == null) return false;
		for (AccountKey key : keys) {
			if (verifyWith(key, message, signature)) return true;
		}
		return false;
	}

	/** Resolves a remote did:web document's verification keys, cached on success. */
	private List<AccountKey> resolveRemoteKeys(String did) {
		CachedKeys cached = remoteCache.get(did);
		long now = System.currentTimeMillis();
		if (cached != null && now < cached.expiresAtMillis()) return cached.keys();

		String url = didWebDocumentURL(did);
		if (url == null) return null;
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build();
			HttpResponse<String> resp = client.send(
				HttpRequest.newBuilder(URI.create(url)).timeout(FETCH_TIMEOUT).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) return null;
			ACell doc = JSON.parse(resp.body());
			// Resolution integrity: the document must be about the DID we asked for.
			AString id = RT.ensureString(RT.getIn(doc, "id"));
			if (id == null || !did.equals(id.toString())) return null;
			List<AccountKey> keys = new ArrayList<>();
			ACell methods = RT.getIn(doc, "verificationMethod");
			if (methods instanceof AVector<?> mv) {
				for (long i = 0; i < mv.count(); i++) {
					AString mk = RT.ensureString(RT.getIn(mv.get(i), "publicKeyMultibase"));
					AccountKey key = (mk != null) ? Multikey.decodePublicKey(mk.toString()) : null;
					if (key != null) keys.add(key);
				}
			}
			if (keys.isEmpty()) return null;
			remoteCache.put(did, new CachedKeys(List.copyOf(keys), now + REMOTE_CACHE_TTL_MILLIS));
			return keys;
		} catch (Exception e) {
			log.debug("did:web resolution failed for {}: {}", did, e.getMessage());
			return null;
		}
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
