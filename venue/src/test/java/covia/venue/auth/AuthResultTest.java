package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.server.AuthMiddleware;
import io.javalin.http.Context;

/** Credential/evidence boundaries, without contacting an external identity provider. */
class AuthResultTest {
	private static Engine engine;
	private static VenueAuthenticator auth;
	private static AKeyPair key;
	private static AString did;
	private static long now;
	private static long expiry;

	@BeforeAll
	static void setup() {
		engine = Engine.createTemp(Maps.of(Config.HOSTNAME, "auth-result.example"));
		auth = new VenueAuthenticator(engine);
		key = AKeyPair.generate();
		did = UCAN.toDIDKey(key.getAccountKey());
		now = System.currentTimeMillis() / 1000;
		expiry = now + 3600;
	}

	@AfterAll
	static void close() {
		if (engine != null) engine.close();
	}

	private static AMap<AString, ACell> claims() {
		// These extra signed claims are deliberately not trusted as evidence.
		return Maps.of("sub", did, "iss", did, "aud", engine.getDIDString(),
			"exp", expiry, "iat", now, "auth_time", now,
			"amr", Vectors.of("mfa", "hwk"), "acr", "hard",
			"sessionId", "invented", "proofTime", now);
	}

	private static void assertUnknownEvidence(AuthResult result) {
		assertNull(result.authentication().authenticatedAt());
		assertTrue(result.authentication().properties().isEmpty());
		assertNull(result.credential().issuedAt(), "iat has no existing validation policy");
		assertNull(result.proof());
		assertNull(result.sessionId());
	}

	@Test
	void keyBearerHasProvenIdentityButNoFreshnessOrAssurance() {
		AString token = JWT.signPublic(claims(), key);
		AuthResult result = auth.verify(token);
		assertEquals(did, result.userDID());
		assertEquals(did, result.authenticatedIdentity());
		assertEquals(AuthResult.KEY_JWT, result.credential().type());
		assertEquals(expiry, result.credential().expiresAt());
		assertEquals(AuthResult.KEY, result.authentication().method());
		assertEquals(did, assertInstanceOf(AuthResult.KeyDetails.class,
			result.authentication().details()).signingIdentity());
		assertUnknownEvidence(result);
		assertEquals(result, auth.verify(token), "replay must not manufacture a new event");
		assertEquals(result.userDID(), auth.authenticate(token));
		assertFalse(result.toString().contains(token.toString()));
	}

	@Test
	void namedKeyPreservesBothIdentities() {
		AString id = Strings.create("result_named");
		AString userDID = engine.managedUserDID(id);
		engine.getAuth().putUser(id, Maps.of(Fields.DID, userDID));
		engine.getAuth().addAuthenticationKey(id, did, engine.getDIDString(), null);
		AString token = JWT.signPublic(claims().assoc(Fields.SUB, userDID)
			.assoc(Strings.intern("iss"), userDID), key);
		AuthResult result = auth.verify(token);
		assertEquals(userDID, result.userDID());
		assertEquals(did, result.authenticatedIdentity());
		assertUnknownEvidence(result);
	}

	@Test
	void ucanBearerProjectsUpstreamIdentityWithoutRetainingToken() {
		AString token = JWT.signPublic(claims().assoc(UCAN.ATT, Vectors.empty()), key);
		AuthResult result = auth.verify(token);
		assertEquals(did, result.userDID());
		assertEquals(AuthResult.UCAN_BEARER, result.credential().type());
		assertEquals(AuthResult.UCAN, result.authentication().method());
		assertEquals(new AuthResult.UcanDetails(did, engine.getDIDString()),
			result.authentication().details());
		assertEquals(expiry, result.credential().expiresAt());
		assertUnknownEvidence(result);
		assertFalse(result.toString().contains(token.toString()));
	}

	@Test
	void venueSignatureDoesNotInventLoginOrigin() {
		AString token = JWT.signPublic(claims()
			.assoc(Strings.intern("iss"), engine.getDIDString())
			.assoc(Strings.intern("method"), AuthResult.OIDC), engine.getKeyPair());
		AuthResult result = auth.verify(token);
		assertEquals(AuthResult.VENUE_JWT, result.credential().type());
		assertNull(result.authentication().method());
		assertNull(result.authentication().details());
		assertUnknownEvidence(result);
		assertEquals(did, auth.authenticate(token));
	}

	@Test
	void failuresKeepTheExistingReasons() {
		AString token = JWT.signPublic(claims().assoc(Strings.intern("aud"),
			Strings.create("did:web:wrong.example")), key);
		AuthException oldApi = assertThrows(AuthException.class, () -> auth.authenticate(token));
		AuthException resultApi = assertThrows(AuthException.class, () -> auth.verify(token));
		assertEquals(oldApi.getMessage(), resultApi.getMessage());
		assertTrue(resultApi.getMessage().contains(VenueAuthenticator.Reason.AUDIENCE_NOT_THIS_VENUE));
	}

	@Test
	void uncheckedOrMissingMetadataDoesNotChangeAcceptance() {
		Engine relaxed = Engine.createTemp(Maps.of(Config.AUTH, Maps.of("requireExp", false)));
		try {
			VenueAuthenticator verifier = new VenueAuthenticator(relaxed);
			AString token = JWT.signPublic(Maps.of("sub", did, "iat", "not-a-time",
				"auth_time", now, "amr", Vectors.of("mfa")), key);
			AuthResult result = verifier.verify(token);
			assertNull(result.credential().expiresAt());
			assertUnknownEvidence(result);
			assertEquals(did, verifier.authenticate(token));
		} finally {
			relaxed.close();
		}
	}

	@Test
	void identityOnlyRebindingDiscardsEarlierEvidence() {
		Context ctx = context();
		AString token = JWT.signPublic(claims(), key);
		assertEquals(did, auth.authenticate(ctx, token));
		assertEquals(auth.verify(token), auth.authenticationResult(ctx));
		auth.bindIdentity(ctx, did, did);
		assertEquals(AuthResult.identity(did, did), auth.authenticationResult(ctx));
		auth.authenticate(ctx, token);
		AuthMiddleware.setRequestIdentity(ctx, did, did);
		assertEquals(AuthResult.identity(did, did), auth.authenticationResult(ctx));
		assertEquals(did, AuthMiddleware.getAuthenticatedIdentity(ctx));
		assertEquals(did, AuthMiddleware.getVenueUserDID(ctx));
	}

	private record HostDetails(String account) implements AuthResult.Details {}

	@Test
	void trustedEmbedderUsesTheContractWithoutOAuthOrASession() {
		Set<String> properties = new HashSet<>(Set.of("userVerified"));
		AuthResult result = new AuthResult(did, Strings.create("host-account"),
			new AuthResult.Credential(Strings.intern("hostCredential"), null, null),
			new AuthResult.Authentication(Strings.intern("host"), null, now,
				properties, new HostDetails("host-account")), null, null);
		properties.clear();
		assertEquals(Set.of("userVerified"), result.authentication().properties());
		assertThrows(UnsupportedOperationException.class,
			() -> result.authentication().properties().add("mfa"));
		Context ctx = context();
		assertNull(auth.authenticationResult(ctx));
		auth.bindResult(ctx, result);
		assertSame(result, auth.authenticationResult(ctx));
		assertEquals(result.authenticatedIdentity(), auth.authenticatedIdentity(ctx));
		assertEquals(did, auth.authenticatedUser(ctx));
		assertNull(result.sessionId());
	}

	@Test
	void providerEvidenceIsBoundedAndIssuerIsUnknownWhenUnchecked() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair rsa = generator.generateKeyPair();
		RSAPublicKey publicKey = (RSAPublicKey) rsa.getPublic();
		String jwks = "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test\",\"n\":\""
			+ unsigned(publicKey.getModulus().toByteArray()) + "\",\"e\":\""
			+ unsigned(publicKey.getPublicExponent().toByteArray()) + "\"}]}";
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/jwks", exchange -> {
			byte[] bytes = jwks.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			try (var out = exchange.getResponseBody()) { out.write(bytes); }
		});
		server.start();
		try {
			String jwksUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
			String issuer = "https://provider.example";
			AString email = Strings.create("auth-result@example.test");
			engine.getAuth().putUser(Strings.create("result_provider"),
				Maps.of(Fields.DID, did, Fields.EMAIL, email));
			AMap<AString, ACell> payload = claims()
				.assoc(Strings.intern("iss"), Strings.create(issuer))
				.assoc(Strings.intern("aud"), Strings.create("client"))
				.assoc(Fields.SUB, Strings.create("provider-subject"))
				.assoc(Fields.EMAIL, email);
			for (String expectedIssuer : new String[] {issuer, null}) {
				OAuthConfig config = new OAuthConfig("test", "client", "unused",
					null, null, null, jwksUri, expectedIssuer, null, null);
				VenueAuthenticator verifier = new VenueAuthenticator(engine, Map.of("test", config));
				AString token = signRSA(payload, rsa);
				AuthResult result = verifier.verify(token);
				assertEquals(did, result.userDID());
				assertEquals(Strings.create("provider-subject"), result.authenticatedIdentity());
				assertEquals(AuthResult.PROVIDER_TOKEN, result.credential().type());
				AuthResult.ProviderDetails details = assertInstanceOf(
					AuthResult.ProviderDetails.class, result.authentication().details());
				assertEquals("test", details.provider());
				assertEquals(expectedIssuer == null ? null : Strings.create(issuer), details.iss());
				assertEquals(Strings.create("provider-subject"), details.sub());
				assertUnknownEvidence(result);
				assertEquals(did, verifier.authenticate(token));
				assertFalse(result.toString().contains(token.toString()));
				// Upstream accepts fractional NumericDate; do not round it or reject it.
				AuthResult fractional = verifier.verify(signRSA(
					payload.assoc(UCAN.EXP, CVMDouble.create(expiry + 0.5)), rsa));
				assertNull(fractional.credential().expiresAt());
				AuthResult fallback = verifier.verify(signRSA(payload.dissoc(Fields.SUB), rsa));
				assertEquals(email, fallback.authenticatedIdentity());
				assertNull(((AuthResult.ProviderDetails) fallback.authentication().details()).sub());
			}
		} finally {
			server.stop(0);
		}
	}

	private static AString signRSA(AMap<AString, ACell> claims, KeyPair key) throws Exception {
		String input = base64("{\"alg\":\"RS256\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8))
			+ "." + base64(JSON.print(claims).toString().getBytes(StandardCharsets.UTF_8));
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(key.getPrivate());
		signer.update(input.getBytes(StandardCharsets.UTF_8));
		return Strings.create(input + "." + base64(signer.sign()));
	}

	private static String unsigned(byte[] bytes) {
		return base64(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
	}

	private static String base64(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Only request attributes are involved in binding, not servlet behaviour. */
	private static Context context() {
		Map<String, Object> attributes = new HashMap<>();
		return (Context) Proxy.newProxyInstance(Context.class.getClassLoader(),
			new Class<?>[] {Context.class}, (proxy, method, args) -> {
				if (!method.getName().equals("attribute")) throw new UnsupportedOperationException(method.getName());
				if (args.length == 1) return attributes.get(args[0]);
				attributes.put((String) args[0], args[1]);
				return proxy;
			});
	}
}
