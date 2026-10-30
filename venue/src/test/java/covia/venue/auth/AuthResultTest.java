package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.adapter.AuthAdapter;
import covia.exception.AuthException;
import covia.grid.auth.VenueAuth;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
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
		Engine.addDemoAssets(engine);
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
		assertNull(result.credential().issuedAt(), "common issuance metadata remains conservative");
		assertNull(result.proof());
		assertNull(result.sessionId());
		assertEquals(EvidencePolicy.MISSING_PROPERTIES,
			new EvidencePolicy(null, Sets.of(Strings.create("mfa")), Sets.empty(), null, null).allows(result, now));
		assertEquals(EvidencePolicy.AUTHENTICATION_NOT_FRESH,
			new EvidencePolicy(null, Sets.empty(), Sets.empty(), 900L, null).allows(result, now));
		assertEquals(EvidencePolicy.PROOF_NOT_FRESH,
			new EvidencePolicy(null, Sets.empty(), Sets.empty(), null, 60L).allows(result, now));
	}

	@Test
	void keyBearerHasProvenIdentityButNoFreshnessOrAssurance() {
		AString token = JWT.signPublic(claims(), key);
		AuthResult result = auth.verify(token);
		assertEquals(did, result.userDID());
		assertEquals(did, result.authenticatedIdentity());
		assertEquals(VenueAuthenticator.KEY_JWT, result.credential().type());
		assertEquals(expiry, result.credential().expiresAt());
		assertEquals(VenueAuthenticator.KEY, result.authentication().method());
		assertEquals(did, result.authentication().details().get(VenueAuthenticator.SIGNING_IDENTITY));
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
	void installationUsersPreserveSigningIdentityWithoutRegistration() {
		for (String name : new String[] {"alice", "Bob_2.test-3", ".", ".."}) {
			// Deliberately the existing username grammar, including dot-only names.
			AString user = Strings.create(did + ":u:" + name);
			AString token = Strings.create(VenueAuth.namedKeyPair(key, user.toString(),
				engine.getDIDString().toString(), 300).mintToken());
			assertNull(engine.getVenueState().users().get(user));
			AuthResult result = auth.verify(token);
			assertEquals(user, result.userDID());
			assertEquals(did, result.authenticatedIdentity());
			assertEquals(did, result.authentication().details().get(VenueAuthenticator.SIGNING_IDENTITY));
			assertEquals(VenueAuthenticator.KEY, result.authentication().method());
			assertUnknownEvidence(result);
			Context ctx = context();
			assertEquals(user, auth.authenticate(ctx, token));
			assertEquals(did, auth.authenticatedIdentity(ctx));
			assertEquals(user, auth.authenticatedUser(ctx));
			assertEquals(user, AuthMiddleware.callerContext(ctx).getCallerDID());
			assertEquals(user, AuthMiddleware.callerContext(ctx).getUserDID());
			assertNull(engine.getVenueState().users().get(user), "authentication does not admit users");
		}
	}

	@Test
	void nestedIdentitiesRequireNonemptySafeSegmentsBelowTheSigningKey() {
		for (String suffix : new String[] {":", "::alice", ":u:", ":u::alice", ":u:alice/w/private",
			":u:alice?x", ":u:alice#x", ":u:alice%2fbob", ":u:alice bob", ":u:é",
			":u:alice\\bob", ":u:alice:g:", ":u:alice:g::worker", "other:u:alice"}) {
			assertKeyRejected(JWT.signPublic(claims().assoc(Fields.SUB, Strings.create(did + suffix)), key),
				VenueAuthenticator.Reason.WRONG_KEY_FOR_SUBJECT);
		}
		AString user = Strings.create(did + ":u:alice");
		AKeyPair stranger = AKeyPair.generate();
		AMap<AString, ACell> childClaims = claims().assoc(Fields.SUB, user);
		assertKeyRejected(JWT.signPublic(childClaims, stranger), VenueAuthenticator.Reason.WRONG_KEY_FOR_SUBJECT);
		// A correct kid with somebody else's signature must still fail cryptographically.
		assertKeyRejected(JWT.signPublic(childClaims, stranger, did), VenueAuthenticator.Reason.SIGNATURE_FAILS);
		assertKeyRejected(JWT.signPublic(childClaims, key,
			Strings.create(did + ":u:bob#" + did.toString().substring("did:key:".length()))),
			VenueAuthenticator.Reason.KID_NOT_OF_SUBJECT);
	}

	@Test
	void arbitraryNestedIdentitiesAuthenticateWithoutRegistration() {
		for (String suffix : new String[] {":alice", ":u:alice:u:bob", ":g:worker",
			":u:alice:g:worker", ":org:acme:service:build:job:42", ":g:parent:g:child",
			":segment".repeat(2000)}) {
			AString subject = Strings.create(did + suffix);
			AString token = Strings.create(VenueAuth.namedKeyPair(key, subject.toString(),
				engine.getDIDString().toString(), 300).mintToken());
			Context ctx = context();
			assertEquals(subject, auth.authenticate(ctx, token));
			assertEquals(subject, auth.authenticatedUser(ctx));
			assertEquals(did, auth.authenticatedIdentity(ctx));
			assertEquals(subject, AuthMiddleware.callerContext(ctx).getCallerDID());
			assertNull(engine.getVenueState().users().get(subject));
			assertUnknownEvidence(auth.authenticationResult(ctx));
			assertKeyRejected(JWT.signPublic(claims().assoc(Fields.SUB, subject), AKeyPair.generate()),
				VenueAuthenticator.Reason.WRONG_KEY_FOR_SUBJECT);
		}
	}

	@Test
	void authenticatedAgentsNeedExplicitGrantsAndCannotInheritOwnerAccess() {
		AString resource = Strings.create(did + "/w/delegated");
		for (String suffix : new String[] {":g:worker", ":u:alice:g:worker", ":g:parent:g:child"}) {
			AString agent = Strings.create(did + suffix);
			Context ctx = context();
			auth.authenticate(ctx, JWT.signPublic(claims().assoc(Fields.SUB, agent), key));
			RequestContext caller = AuthMiddleware.callerContext(ctx);
			assertEquals(agent, caller.getCallerDID());
			assertEquals(agent, caller.getUserDID(), "a bearer cannot claim a local owner's namespace");
			assertEquals(Vectors.empty(), caller.getCaps(), "authentication supplies no agent capabilities");
			AString owner = Strings.create(agent.toString().substring(0, agent.toString().lastIndexOf(":g:")));
			assertFalse(engine.getAccessControl().canAccessJob(caller, Maps.of(Fields.CALLER, owner)),
				"ownership-only checks must not grant access to the named owner's jobs");
			assertThrows(AuthException.class, () -> engine.requireLocalAccess(caller,
				Strings.create("w/private"), Capability.CRUD_READ));
			assertThrows(AuthException.class, () -> engine.requireLocalAccess(caller,
				Strings.create(owner + "/w/private"), Capability.CRUD_READ));
			assertThrows(AuthException.class, () -> engine.requireLocalAccess(caller, resource, Capability.CRUD_READ));
			assertThrows(AuthException.class, () -> engine.admitUser(agent));
			AString proof = JWT.signPublic(UCAN.buildPayload(key.getAccountKey(), agent, expiry, null,
				Vectors.of(Capability.create(resource, Capability.CRUD_READ)), Vectors.empty(), null), key);
			RequestContext granted = AuthMiddleware.withTransportGrants(caller, Vectors.of(proof));
			assertEquals(did, engine.requireLocalAccess(granted, resource, Capability.CRUD_READ));
			assertThrows(AuthException.class, () -> engine.requireLocalAccess(granted, resource, Capability.CRUD_WRITE));
			AString sibling = Strings.create(agent + "-sibling");
			Context siblingCtx = context();
			auth.authenticate(siblingCtx, JWT.signPublic(claims().assoc(Fields.SUB, sibling), key));
			RequestContext other = AuthMiddleware.withTransportGrants(AuthMiddleware.callerContext(siblingCtx), Vectors.of(proof));
			assertThrows(AuthException.class, () -> engine.requireLocalAccess(other, resource, Capability.CRUD_READ));
		}
	}

	@Test
	void installationUsersKeepAudienceAndTemporalChecks() {
		AString user = Strings.create(did + ":u:temporal:g:worker");
		AMap<AString, ACell> childClaims = claims().assoc(Fields.SUB, user).assoc(JWT.ISS, user);
		assertKeyRejected(JWT.signPublic(childClaims.assoc(JWT.AUD, Strings.create("did:web:elsewhere")), key),
			VenueAuthenticator.Reason.AUDIENCE_NOT_THIS_VENUE);
		assertKeyRejected(JWT.signPublic(childClaims.assoc(JWT.EXP, CVMLong.create(now - 120)), key),
			VenueAuthenticator.Reason.EXPIRED);
		assertKeyRejected(JWT.signPublic(childClaims.assoc(JWT.NBF, CVMLong.create(now + 3600)), key),
			VenueAuthenticator.Reason.NOT_YET_VALID);
		assertKeyRejected(JWT.signPublic(childClaims.dissoc(JWT.EXP), key), VenueAuthenticator.Reason.MISSING_EXP);
		assertKeyRejected(JWT.signPublic(childClaims.assoc(JWT.EXP, Strings.create("tomorrow")), key),
			VenueAuthenticator.Reason.MALFORMED_EXP);
		assertKeyRejected(JWT.signPublic(childClaims.assoc(JWT.NBF, Strings.create("tomorrow")), key),
			VenueAuthenticator.Reason.MALFORMED_NBF);
		// As for bare did:key subjects, iss does not confer authority (#323).
		assertEquals(user, auth.authenticate(JWT.signPublic(childClaims.dissoc(JWT.ISS), key)));
		assertEquals(user, auth.authenticate(JWT.signPublic(childClaims.assoc(JWT.ISS, did), key)));
	}

	@Test
	void installationUsersCannotInheritSignerGrantsOrReadSignerAndSiblingData() {
		AKeyPair owner = AKeyPair.generate();
		AString ownerDID = UCAN.toDIDKey(owner.getAccountKey());
		AString resource = Strings.create(ownerDID + "/w/private");
		AString proof = UCAN.create(owner, key.getAccountKey(), expiry,
			Vectors.of(Capability.create(resource, Capability.CRUD_READ)), Vectors.empty()).toJWT(owner);
		Context root = context();
		auth.authenticate(root, JWT.signPublic(claims(), key));
		assertTrue(engine.proofsCover(AuthMiddleware.withTransportGrants(AuthMiddleware.callerContext(root), Vectors.of(proof)),
			resource, Capability.CRUD_READ, now));
		Context child = context();
		AString user = Strings.create(did + ":u:isolated");
		auth.authenticate(child, JWT.signPublic(claims().assoc(Fields.SUB, user), key));
		RequestContext caller = AuthMiddleware.callerContext(child);
		assertFalse(engine.proofsCover(AuthMiddleware.withTransportGrants(caller, Vectors.of(proof)),
			resource, Capability.CRUD_READ, now), "a grant to the signer is not a grant to its user");
		assertFalse(engine.crossUserAllows(caller, Strings.create(did + "/w/private"), Capability.CRUD_READ));
		AString sibling = Strings.create(did + ":u:sibling");
		assertFalse(engine.crossUserAllows(caller, Strings.create(sibling + "/w/private"), Capability.CRUD_READ));
	}

	@Test
	void thePublicPrincipalNeverAuthenticates() {
		// The venue key can sign its own public principal as a nested subject, and
		// could issue a session for it; neither credential may authenticate.
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		assertTrue(engine.isPublicPrincipal(publicDID));
		AString selfIssued = Strings.create(VenueAuth.namedKeyPair(engine.getKeyPair(), publicDID.toString(),
			engine.getDIDString().toString(), 300).mintToken());
		assertKeyRejected(selfIssued, VenueAuthenticator.Reason.PUBLIC_SUBJECT);
		AString venueSigned = JWT.signPublic(Maps.of(JWT.ISS, engine.getDIDString(), JWT.SUB, publicDID,
			JWT.AUD, engine.getDIDString(), JWT.IAT, now, JWT.EXP, expiry), engine.getKeyPair());
		String message = assertThrows(AuthException.class, () -> auth.verify(venueSigned)).getMessage();
		assertTrue(message.startsWith(VenueAuthenticator.VENUE_TOKEN_REJECTED_PREFIX), message);
		assertTrue(message.contains(VenueAuthenticator.Reason.PUBLIC_SUBJECT), message);
		// Any other venue-key-signed nested subject still authenticates.
		AString user = Strings.create(UCAN.toDIDKey(engine.getKeyPair().getAccountKey()) + ":u:alice");
		assertEquals(user, auth.authenticate(Strings.create(VenueAuth.namedKeyPair(engine.getKeyPair(),
			user.toString(), engine.getDIDString().toString(), 300).mintToken())));
	}

	private static void assertKeyRejected(AString token, String reason) {
		String message = assertThrows(AuthException.class, () -> auth.verify(token)).getMessage();
		assertTrue(message.startsWith(VenueAuthenticator.SELF_ISSUED_REJECTED_PREFIX), message);
		assertTrue(message.contains(reason), message);
	}

	@Test
	void ucanBearerProjectsUpstreamIdentityWithoutRetainingToken() {
		AString token = JWT.signPublic(claims().assoc(UCAN.ATT, Vectors.empty()), key);
		AuthResult result = auth.verify(token);
		assertEquals(did, result.userDID());
		assertEquals(VenueAuthenticator.UCAN_BEARER, result.credential().type());
		assertEquals(VenueAuthenticator.UCAN_METHOD, result.authentication().method());
		assertEquals(Maps.of(UCAN.ISS, did, UCAN.AUD, engine.getDIDString()),
			result.authentication().details());
		assertEquals(expiry, result.credential().expiresAt());
		assertUnknownEvidence(result);
		assertFalse(result.toString().contains(token.toString()));
	}

	@Test
	void venueSignatureDoesNotInventLoginOrigin() {
		AString token = JWT.signPublic(claims()
			.assoc(Strings.intern("iss"), engine.getDIDString())
			.assoc(Strings.intern("method"), VenueAuthenticator.OIDC), engine.getKeyPair());
		AuthResult result = auth.verify(token);
		assertEquals(VenueAuthenticator.VENUE_JWT, result.credential().type());
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
	void optionalExpiryDoesNotMakeMalformedTemporalClaimsOptional() {
		Engine relaxed = Engine.createTemp(Maps.of(Config.AUTH, Maps.of("requireExp", false)));
		try {
			VenueAuthenticator verifier = new VenueAuthenticator(relaxed);
			for (boolean venueSigned : new boolean[] {false, true}) {
				AKeyPair signer = venueSigned ? relaxed.getKeyPair() : key;
				AMap<AString, ACell> base = Maps.of("sub", did,
					"iss", venueSigned ? relaxed.getDIDString() : did, "exp", expiry);
				for (String field : new String[] {"exp", "nbf"}) {
					for (ACell invalid : new ACell[] {Strings.create("tomorrow"), Maps.empty(), Vectors.empty()}) {
						AString token = JWT.signPublic(base.assoc(Strings.create(field), invalid), signer);
						String prefix = venueSigned ? VenueAuthenticator.VENUE_TOKEN_REJECTED_PREFIX
							: VenueAuthenticator.SELF_ISSUED_REJECTED_PREFIX;
						String reason = field.equals("exp") ? VenueAuthenticator.Reason.MALFORMED_EXP
							: VenueAuthenticator.Reason.MALFORMED_NBF;
						assertEquals(prefix + reason,
							assertThrows(AuthException.class, () -> verifier.verify(token), field).getMessage());
					}
				}
			}
		} finally { relaxed.close(); }
	}

	@Test
	void temporalBoundsDoNotOverflowWhenApplyingClockSkew() {
		// These checks use a fixed realistic host time and the existing leeway.
		assertNull(auth.temporalProblem(claims().assoc(Strings.create("nbf"), CVMLong.MIN_VALUE), now));
		assertNull(auth.temporalProblem(claims().assoc(Strings.create("exp"), CVMLong.MAX_VALUE), now));
		assertTrue(auth.temporalProblem(claims().assoc(Strings.create("exp"), CVMLong.MIN_VALUE), now)
			.startsWith(VenueAuthenticator.Reason.EXPIRED));
		Engine capped = Engine.createTemp(Maps.of(Config.AUTH, Maps.of("maxTokenLifetime", 3600)));
		try {
			assertTrue(new VenueAuthenticator(capped).temporalProblem(
				claims().assoc(Strings.create("exp"), CVMLong.MAX_VALUE), now)
				.startsWith(VenueAuthenticator.Reason.LIFETIME_EXCEEDS));
		} finally { capped.close(); }
	}

	@Test
	void temporalChecksPreserveTheExistingClockSkewBoundaries() {
		assertNull(auth.temporalProblem(claims().assoc(Strings.create("exp"), CVMLong.create(now - 60)), now));
		assertTrue(auth.temporalProblem(claims().assoc(Strings.create("exp"), CVMLong.create(now - 61)), now)
			.startsWith(VenueAuthenticator.Reason.EXPIRED));
		assertNull(auth.temporalProblem(claims().assoc(Strings.create("nbf"), CVMLong.create(now + 60)), now));
		assertTrue(auth.temporalProblem(claims().assoc(Strings.create("nbf"), CVMLong.create(now + 61)), now)
			.startsWith(VenueAuthenticator.Reason.NOT_YET_VALID));
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

	@Test
	void trustedEmbedderUsesTheContractWithoutOAuthOrASession() {
		ASet<AString> properties = Sets.of("userVerified");
		AMap<AString, ACell> details = Maps.of("account", "host-account",
			"ceremony", Maps.of("device", "terminal", "version", 1));
		AuthResult result = new AuthResult(did, Strings.create("host-account"),
			new AuthResult.Credential(Strings.intern("hostCredential"), null, null),
			new AuthResult.Authentication(Strings.intern("host"), null, now,
				properties, details), null, null);
		assertFalse(properties.include(Strings.intern("mfa")).equals(properties));
		assertEquals(Sets.of("userVerified"), result.authentication().properties());
		assertFalse(details.assoc(Strings.intern("changed"), did).equals(details));
		assertSame(details, result.authentication().details());
		Context ctx = context();
		assertNull(auth.authenticationResult(ctx));
		auth.bindResult(ctx, result);
		assertSame(result, auth.authenticationResult(ctx));
		assertEquals(result.authenticatedIdentity(), auth.authenticatedIdentity(ctx));
		assertEquals(did, auth.authenticatedUser(ctx));
		assertNull(result.sessionId());
	}

	@Test
	void latticeDataSurvivesGridOperationsAndPrivateStorage() {
		AMap<AString, ACell> details = Maps.of("account", "host-account",
			"ceremony", Maps.of("device", "terminal", "version", 1));
		AuthResult result = new AuthResult(did, Strings.create("host-account"),
			new AuthResult.Credential(Strings.intern("hostCredential"), now, expiry),
			new AuthResult.Authentication(Strings.intern("host"), Strings.create("binding"),
				now, Sets.of("userVerified"), details),
			new AuthResult.Proof(now, Sets.of("possession")), Strings.create("session"));
		AMap<AString, ACell> data = result.toData();
		assertEquals(details, RT.getIn(data, "authentication", "details"));
		assertEquals(CVMLong.create(now), RT.getIn(data, "credential", "issuedAt"));
		assertEquals(CVMLong.create(expiry), RT.getIn(data, "credential", "expiresAt"));
		assertEquals(CVMLong.create(now), RT.getIn(data, "proof", "verifiedAt"));
		assertEquals(Strings.create("binding"), RT.getIn(data, "authentication", "binding"));
		assertEquals(result.sessionId(), data.get(AuthResult.SESSION_ID));
		assertEquals(result.proof().properties(), RT.getIn(data, "proof", "properties"));

		// Existing grid operations and upstream CAD3 carry arbitrary method
		// payloads without Java serialisation or a method-specific codec.
		RequestContext caller = engine.venueContext();
		ACell encoded = engine.jobs().invokeOperation("v/ops/convex/encode-cad3",
			Maps.of("value", data), caller).awaitResult(5000);
		ACell restored = engine.jobs().invokeOperation("v/ops/convex/decode-cad3",
			encoded, caller).awaitResult(5000);
		assertEquals(data, restored);
		assertEquals(data.getHash(), restored.getHash());
		var state = engine.adapterState("auth-result-test");
		state.write("event", restored);
		assertEquals(data, state.read("event"));
		assertEquals(details, state.read("event/authentication/details"));
	}

	@Test
	void unknownFieldsAreAbsentFromLatticeProjection() {
		AMap<AString, ACell> data = AuthResult.identity(did, did).toData();
		assertFalse(data.containsKey(AuthResult.CREDENTIAL));
		assertFalse(data.containsKey(AuthResult.PROOF));
		assertFalse(data.containsKey(AuthResult.SESSION_ID));
		assertEquals(Maps.of(AuthResult.PROPERTIES, Sets.empty()), data.get(AuthResult.AUTHENTICATION));
		assertEquals(Maps.of(AuthResult.TYPE, VenueAuthenticator.KEY_JWT),
			new AuthResult.Credential(VenueAuthenticator.KEY_JWT, null, null).toData());
	}

	@Test
	void providerEvidenceUsesVerifiedIssuerAndSubject() throws Exception {
		// Swapping the auth adapter bumps the adapter registry version, which
		// refuses verifications in flight on that engine (AuthMethods.CHANGED).
		// This class's tests run concurrently, so this one swaps on its own engine.
		Engine isolated = Engine.createTemp(Maps.of(Config.HOSTNAME, "auth-result.example"));
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
			isolated.getAuth().putUser(Strings.create("result_provider"),
				Maps.of(Fields.DID, did, Fields.EMAIL, email, LoginProviders.EXTERNAL_IDENTITY,
					Maps.of("iss", issuer, "sub", "provider-subject")));
			AMap<AString, ACell> payload = claims()
				.assoc(Strings.intern("iss"), Strings.create(issuer))
				.assoc(Strings.intern("aud"), Strings.create("client"))
				.assoc(Fields.SUB, Strings.create("provider-subject"))
				.assoc(Fields.EMAIL, email);
			for (String expectedIssuer : new String[] {issuer}) {
				OAuthConfig config = new OAuthConfig("test", "client", "unused",
					null, null, null, jwksUri, expectedIssuer, null, null);
				isolated.registerAdapter(new AuthAdapter() {
					@Override protected VenueAuthenticator createAuthenticator() {
						return new VenueAuthenticator(isolated, Map.of("test", config));
					}
				});
				VenueAuthenticator verifier = new VenueAuthenticator(isolated);
				AString token = signRSA(payload, rsa);
				AuthResult result = verifier.verify(token);
				assertEquals(did, result.userDID());
				assertEquals(Strings.create("provider-subject"), result.authenticatedIdentity());
				assertEquals(VenueAuthenticator.PROVIDER_TOKEN, result.credential().type());
				AMap<AString, ACell> details = result.authentication().details();
				assertEquals(Strings.create("test"), details.get(VenueAuthenticator.PROVIDER));
				assertEquals(Strings.create(issuer), details.get(UCAN.ISS));
				assertEquals(Strings.create("provider-subject"), details.get(Fields.SUB));
				assertEquals(3, details.count());
				assertUnknownEvidence(result);
				assertEquals(did, verifier.authenticate(token));
				assertFalse(result.toString().contains(token.toString()));
				// Upstream accepts fractional NumericDate; do not round it or reject it.
				AuthResult fractional = verifier.verify(signRSA(
					payload.assoc(UCAN.EXP, CVMDouble.create(expiry + 0.5)), rsa));
				assertNull(fractional.credential().expiresAt());
				assertThrows(AuthException.class, () -> verifier.verify(signRSA(payload.dissoc(Fields.SUB), rsa)));
				assertThrows(AuthException.class, () -> verifier.verify(signRSA(payload.assoc(Fields.SUB, Strings.create("unbound")), rsa)));
				assertEquals(did, verifier.verify(signRSA(payload.dissoc(Fields.EMAIL), rsa)).userDID());

			}
		} finally {
			server.stop(0);
			isolated.close();
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
