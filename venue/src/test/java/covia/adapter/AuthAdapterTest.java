package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import convex.core.json.schema.JsonSchema;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.api.Abilities;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.Engine;
import covia.venue.SecretStore;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.TestServer;
import covia.venue.User;
import covia.venue.auth.VenueAuthenticator;

class AuthAdapterTest {
	private static final AString IDENTITY_OP = Strings.create("v/ops/auth/identity-token");

	private RequestContext localCaller() {
		Engine engine = TestEngine.ENGINE;
		AString did = Strings.create(UCAN.toDIDKey(engine.getKeyPair().getAccountKey()) + ":u:" + UUID.randomUUID());
		engine.getVenueState().users().ensure(did);
		return RequestContext.of(did);
	}

	private AMap<AString, ACell> identityInput() {
		return Maps.of("aud", TestEngine.ENGINE.getDIDString(), "name", "IDENTITY");
	}

	private Job identityJob(RequestContext ctx, ACell input) {
		return TestEngine.ENGINE.jobs().invokeOperation(IDENTITY_OP, input, ctx);
	}

	private Job identityFailure(RequestContext ctx, ACell input) {
		Job job = identityJob(ctx, input);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		return job;
	}

	@Test void identityTokenPreservesExactCallerAndNeverReturnsBearerOrAuthority() {
		Engine engine = TestEngine.ENGINE;
		RequestContext ctx = localCaller();
		Job job = identityJob(ctx, identityInput());
		ACell result = job.awaitResult(5000);
		AString token = Strings.create(engine.resolveSecret("s/IDENTITY", ctx));
		assertNotNull(JWT.verifyPublic(token, engine.getKeyPair().getAccountKey()));
		AMap<AString, ACell> claims = JWT.parse(token).getClaims();
		assertEquals(ctx.getCallerDID(), claims.get(JWT.SUB));
		assertEquals(ctx.getCallerDID(), claims.get(JWT.ISS));
		assertEquals(engine.getDIDString(), claims.get(JWT.AUD));
		assertEquals(AuthAdapter.DEFAULT_IDENTITY_LIFETIME,
			RT.ensureLong(claims.get(JWT.EXP)).longValue() - RT.ensureLong(claims.get(JWT.IAT)).longValue());
		assertNull(claims.get(Strings.create("att")));
		assertNull(claims.get(Strings.create("prf")));
		assertEquals(ctx.getCallerDID(), new VenueAuthenticator(engine).verify(token).userDID());
		assertEquals(Strings.create("s/IDENTITY"), RT.getIn(result, "secret"));
		assertEquals(claims.get(JWT.EXP), RT.getIn(result, "exp"));
		assertFalse(job.getData().toString().contains(token.toString()));
		assertNull(RT.getIn(result, "token"));
		ACell metadata = engine.getAdapter("auth").getOperations().get(Strings.create("identity-token"));
		assertNull(JsonSchema.validate(RT.ensureMap(RT.getIn(metadata, "operation", "input")), identityInput()));
		assertNull(JsonSchema.validate(RT.ensureMap(RT.getIn(metadata, "operation", "output")), result));
	}

	@Test void agentMintsItsIdentityWithExplicitInvokeAndSecretWriteGrants() {
		Engine engine = TestEngine.ENGINE;
		RequestContext owner = localCaller();
		RequestContext agent = RequestContext.ofAgent(owner.getUserDID(), Strings.create("worker"));
		var invoke = Capability.create(IDENTITY_OP, AAdapter.INVOKE);
		var write = Capability.create(Strings.create("s/IDENTITY"), Abilities.SECRET_WRITE);
		identityFailure(agent.withCaps(Vectors.empty()), identityInput());
		identityFailure(agent.withCaps(Vectors.of(invoke)), identityInput());
		identityFailure(agent.withCaps(Vectors.of(write)), identityInput());
		assertNull(engine.resolveSecret("s/IDENTITY", owner));
		RequestContext authorised = agent.withCaps(Vectors.of(invoke, write));
		ACell result = identityJob(authorised, identityInput()).awaitResult(5000);
		AString token = Strings.create(engine.resolveSecret("s/IDENTITY", owner));
		assertEquals(agent.getCallerDID(), RT.getIn(result, "sub"));
		assertEquals(agent.getCallerDID(), new VenueAuthenticator(engine).verify(token).userDID());
		assertFalse(owner.getCallerDID().equals(JWT.parse(token).getClaims().get(JWT.SUB)));
		identityFailure(authorised, identityInput().assoc(Strings.create("name"), Strings.create("OTHER")));
		assertNull(engine.resolveSecret("s/OTHER", owner));
	}

	@Test void identityCannotBeSelectedOrMintedForForeignOrPublicCallers() {
		Engine engine = TestEngine.ENGINE;
		RequestContext ctx = localCaller();
		for (String field : new String[] {"sub", "iss", "att", "prf", "seed", "key", "claims"}) {
			assertEquals(AuthAdapter.IDENTITY_INPUT,
				identityFailure(ctx, identityInput().assoc(Strings.create(field), engine.getDIDString())).getErrorMessage());
		}
		assertNull(engine.resolveSecret("s/IDENTITY", ctx));
		RequestContext foreign = RequestContext.of(UCAN.toDIDKey(AKeyPair.generate().getAccountKey()));
		assertEquals(AuthAdapter.IDENTITY_NOT_OWNED, identityFailure(foreign, identityInput()).getErrorMessage());
		assertEquals(AuthAdapter.IDENTITY_NOT_OWNED, identityFailure(
			RequestContext.of(Strings.create("did:web:external.example:u:alice")), identityInput()).getErrorMessage());
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		assertEquals(AuthAdapter.IDENTITY_AUTH_REQUIRED,
			identityFailure(RequestContext.of(publicDID), identityInput()).getErrorMessage());
		assertEquals(AuthAdapter.IDENTITY_AUTH_REQUIRED,
			identityFailure(RequestContext.ofAgent(publicDID, Strings.create("worker")), identityInput()).getErrorMessage());
	}

	@Test void identityTokenValidatesAudienceLifetimeAndStorageInput() {
		RequestContext ctx = localCaller();
		assertEquals(AuthAdapter.IDENTITY_INPUT, identityFailure(ctx, Vectors.empty()).getErrorMessage());
		for (Object aud : new Object[] {"", "did:web:", "did:WEB:example.com", "https://example.com", "did:web:example.com/path", "did:web:example.com#key", "did:web:example.com?x=1", 42}) {
			assertEquals(AuthAdapter.IDENTITY_AUDIENCE,
				identityFailure(ctx, Maps.of("aud", aud, "name", "IDENTITY")).getErrorMessage());
		}
		assertEquals(AuthAdapter.IDENTITY_AUDIENCE, identityFailure(ctx, Maps.of("name", "IDENTITY")).getErrorMessage());
		for (Object seconds : new Object[] {0, -1, 3601, Long.MAX_VALUE, 1.5, "300"}) {
			assertEquals(AuthAdapter.IDENTITY_LIFETIME, identityFailure(ctx,
				Maps.of("aud", TestEngine.ENGINE.getDIDString(), "name", "IDENTITY", "lifetime", seconds)).getErrorMessage());
		}
		assertEquals(AuthAdapter.IDENTITY_OVERWRITE, identityFailure(ctx,
			identityInput().assoc(Strings.create("overwrite"), Strings.create("true"))).getErrorMessage());
		assertEquals(SecretAdapter.NAME_REQUIRED, identityFailure(ctx, Maps.of("aud", TestEngine.ENGINE.getDIDString())).getErrorMessage());
		assertEquals(SecretAdapter.INVALID_NAME, identityFailure(ctx,
			identityInput().assoc(Strings.create("name"), Strings.create("../escape"))).getErrorMessage());
		assertNull(TestEngine.ENGINE.resolveSecret("s/IDENTITY", ctx));
	}

	@Test void identityTokenRequiresExplicitRefreshAndBindsNewAudience() {
		Engine engine = TestEngine.ENGINE;
		RequestContext ctx = localCaller();
		identityJob(ctx, identityInput()).awaitResult(5000);
		String original = engine.resolveSecret("s/IDENTITY", ctx);
		assertEquals(SecretAdapter.SECRET_EXISTS, identityFailure(ctx, identityInput()).getErrorMessage());
		assertEquals(original, engine.resolveSecret("s/IDENTITY", ctx));
		identityJob(ctx, Maps.of("aud", "did:web:other.example", "name", "IDENTITY",
			"lifetime", 3600, "overwrite", true)).awaitResult(5000);
		AString refreshed = Strings.create(engine.resolveSecret("s/IDENTITY", ctx));
		AMap<AString, ACell> claims = JWT.parse(refreshed).getClaims();
		assertEquals(Strings.create("did:web:other.example"), claims.get(JWT.AUD));
		assertEquals(3600, RT.ensureLong(claims.get(JWT.EXP)).longValue() - RT.ensureLong(claims.get(JWT.IAT)).longValue());
		assertThrows(covia.exception.AuthException.class, () -> new VenueAuthenticator(engine).verify(refreshed));
	}

	@Test void customMetadataCannotBypassIdentityInvokeGrant() {
		// An agent holding invoke on another op and secret/write: a sub-principal,
		// so it passes the lent-scope refusal and reaches the pinned invoke check.
		RequestContext owner = localCaller();
		RequestContext ctx = RequestContext.ofAgent(owner.getUserDID(), Strings.create("worker")).withCaps(Vectors.of(
			Capability.create(Strings.create("v/ops/auth/whoami"), AAdapter.INVOKE),
			Capability.create(Strings.create("s/IDENTITY"), Abilities.SECRET_WRITE)))
			.withOp(Strings.create("v/ops/auth/whoami"));
		AuthAdapter adapter = (AuthAdapter) TestEngine.ENGINE.getAdapter("auth");
		String message = assertThrows(Exception.class, () -> adapter.invokeFuture(ctx,
			Maps.of("operation", Maps.of("adapter", "auth:identity-token")), identityInput()).join()).getMessage();
		assertFalse(message.contains(AuthAdapter.IDENTITY_DIRECT_ONLY), message);
		assertNull(TestEngine.ENGINE.resolveSecret("s/IDENTITY", owner));
	}

	@Test void lentScopeCannotMintTheIdentityItWasLentBy() {
		// The shape a public or anonymous dispatch gives a stranger: the owner's own
		// DID as caller, narrowed by a scope. Even a scope covering both abilities
		// must not mint the owner's bearer; the op cannot tell the owner from the
		// stranger, so it refuses the shape.
		RequestContext owner = localCaller();
		RequestContext lent = owner.withCaps(Vectors.of(
			Capability.create(IDENTITY_OP, AAdapter.INVOKE),
			Capability.create(Strings.create("s/IDENTITY"), Abilities.SECRET_WRITE)));
		assertFalse(lent.isSubPrincipal());
		assertEquals(AuthAdapter.IDENTITY_DIRECT_ONLY, identityFailure(lent, identityInput()).getErrorMessage());
		assertNull(TestEngine.ENGINE.resolveSecret("s/IDENTITY", owner));
		// The owner's own unrestricted authority, and a scoped agent of the owner, still mint.
		assertEquals(owner.getCallerDID(), RT.getIn(identityJob(owner, identityInput()).awaitResult(5000), "sub"));
		RequestContext agent = RequestContext.ofAgent(owner.getUserDID(), Strings.create("worker")).withCaps(Vectors.of(
			Capability.create(IDENTITY_OP, AAdapter.INVOKE),
			Capability.create(Strings.create("s/AGENT"), Abilities.SECRET_WRITE)));
		assertEquals(agent.getCallerDID(), RT.getIn(identityJob(agent,
			identityInput().assoc(Strings.create("name"), Strings.create("AGENT"))).awaitResult(5000), "sub"));
	}

	@Test
	void whoami_returnsCallerOnAnonymousPublicAccess() throws Exception {
		VenueHTTP covia = TestServer.COVIA;
		Job job = covia.invokeSync("v/ops/auth/whoami", Maps.of(), 10_000);

		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());

		// TestServer enables public access, so anonymous requests are attributed
		// to the venue's public DID (e.g. did:web:host:public).
		Object caller = RT.getIn(job.getOutput(), "caller");
		assertNotNull(caller, "caller should be set when public access is enabled");
		assertTrue(caller.toString().endsWith(":public"),
			"public anonymous caller should end with ':public', got: " + caller);

		assertEquals(CVMBool.TRUE, RT.getIn(job.getOutput(), "authenticated"));
	}

	/**
	 * End-to-end round-trip for the {@code bearerSecret} field on http:post.
	 * Outer caller (DID O) stores a self-signed JWT issued by a different
	 * keypair (DID I), then invokes http:post against the venue's own
	 * /api/v1/invoke endpoint to call auth:whoami, passing bearerSecret to
	 * inject the JWT as Authorization. The inner request authenticates as I,
	 * not O — proving that the bearer header is being constructed from the
	 * resolved secret and that the venue's auth middleware verifies it.
	 */
	@Test
	void bearerSecret_injectsSelfSignedJWT_innerCallerIsJWTSubject() throws Exception {
		Engine engine = TestServer.ENGINE;

		// Outer identity: stores the secret and submits the http:post job.
		AKeyPair outerKp = AKeyPair.generate();
		String outerDid = "did:key:" + Multikey.encodePublicKey(outerKp.getAccountKey());

		// Inner identity: signs the JWT injected via bearerSecret. Must differ
		// from the outer identity to make the assertion meaningful.
		AKeyPair innerKp = AKeyPair.generate();
		AString innerDidStr = Strings.create(
			"did:key:" + Multikey.encodePublicKey(innerKp.getAccountKey()));

		// Stash a fresh self-signed JWT in the outer user's secret store.
		User outerUser = engine.getVenueState().users().ensure(Strings.create(outerDid));
		long now = System.currentTimeMillis() / 1000;
		AString jwt = JWT.signPublic(Maps.of(
			Strings.intern("sub"), innerDidStr,
			Strings.intern("iss"), innerDidStr,
			Strings.intern("iat"), now,
			Strings.intern("exp"), now + 300
		), innerKp);
		SecretStore.Keys encKey = SecretStore.keys(engine.getKeyPair());
		outerUser.secrets().store("MY_JWT", jwt.toString(), encKey);

		// Authed client so the outer invocation runs as outerDid (the secret owner).
		VenueHTTP authed = VenueHTTP.create(URI.create(TestServer.BASE_URL), VenueAuth.keyPair(outerKp));

		// Body for the inner /api/v1/invoke call: ?wait=true so the response
		// includes the completed job's output.
		ACell invokeBody = Maps.of(
			Strings.create("operation"), Strings.create("v/ops/auth/whoami"),
			Strings.create("input"), Maps.empty(),
			Strings.create("wait"), CVMBool.TRUE
		);

		Job outer = authed.invokeSync("v/ops/http/post", Maps.of(
			"url", TestServer.BASE_URL + "/api/v1/invoke",
			"headers", Maps.of("Content-Type", "application/json"),
			"body", invokeBody,
			"bearerSecret", "MY_JWT"
		), 30_000);

		assertEquals(Status.COMPLETE, outer.getStatus(), outer.getErrorMessage());
		long httpStatus = RT.ensureLong(RT.getIn(outer.getOutput(), "status")).longValue();
		assertEquals(200, httpStatus, "inner /invoke should return 200 for completed job");

		// Inner response body is JSON-encoded job data. Parse it and assert
		// that the inner caller (resolved by AuthMiddleware from the bearer)
		// matches the JWT subject — NOT the outer caller's DID.
		String innerBodyJson = RT.getIn(outer.getOutput(), "body").toString();
		ACell innerJob = JSON.parse(innerBodyJson);
		assertNotNull(innerJob, "inner response body should be parseable JSON");

		Object innerCaller = RT.getIn(innerJob, "output", "caller");
		assertNotNull(innerCaller, "inner job output should include caller");
		assertEquals(innerDidStr.toString(), innerCaller.toString(),
			"inner caller should be the JWT subject (inner DID), not the outer DID");
		assertTrue(!innerCaller.toString().equals(outerDid),
			"inner caller must differ from outer DID — otherwise the bearer wasn't verified");
	}
}
