package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.jwt.JWT;
import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestEngine;

/** Other adapters signing with held keys: identity tokens, UCAN grants and Convex transactions. */
class HeldKeySigningTest {

	private static final Engine engine = TestEngine.ENGINE;

	private static RequestContext user(AKeyPair key) {
		RequestContext user = RequestContext.of(UCAN.toDIDKey(key.getAccountKey()));
		engine.getVenueState().users().ensure(user.getUserDID());
		return user;
	}
	private static ACell run(String op, ACell input, RequestContext ctx) {
		return engine.jobs().invokeOperation(op, input, ctx).awaitResult(5000);
	}
	private static String failure(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		return job.getErrorMessage();
	}
	/** A user that has put its own identity key into its keyring. */
	private static RequestContext holdingItsOwnKey(AKeyPair identity) {
		RequestContext ctx = user(identity);
		engine.getVenueState().users().ensure(ctx.getUserDID()).secrets()
			.store("own", identity.getSeed().toHexString(), SecretStore.keys(engine.getKeyPair()));
		run("v/ops/crypto/add-key", Maps.of("seed", "s/own"), ctx);
		return ctx;
	}
	private static AMap<AString, ACell> identityToken(RequestContext ctx) {
		run("v/ops/auth/identity-token", Maps.of("aud", engine.getDIDString(), "name", "id", "overwrite", true), ctx);
		return JWT.parse(Strings.create(engine.resolveSecret("s/id", ctx))).getClaims();
	}

	@Test void anIdentityTokenIsSignedByTheRootsHeldKey() {
		AKeyPair identity = AKeyPair.generate();
		RequestContext stranger = user(AKeyPair.generate());
		assertEquals(AuthAdapter.IDENTITY_NOT_OWNED,
			failure("v/ops/auth/identity-token", Maps.of("aud", engine.getDIDString(), "name", "id"), stranger));
		RequestContext root = holdingItsOwnKey(identity);
		AMap<AString, ACell> claims = identityToken(root);
		AString token = Strings.create(engine.resolveSecret("s/id", root));
		assertNotNull(JWT.verifyPublic(token, identity.getAccountKey()));
		assertEquals(root.getCallerDID(), claims.get(JWT.SUB));
		// An identity nested below the root needs no key ability for its own token.
		RequestContext nested = RequestContext.of(Strings.create(root.getUserDID() + ":u:alice"));
		engine.getVenueState().users().ensure(nested.getUserDID());
		claims = identityToken(nested);
		assertNotNull(JWT.verifyPublic(Strings.create(engine.resolveSecret("s/id", nested)), identity.getAccountKey()));
		assertEquals(nested.getCallerDID(), claims.get(JWT.SUB));
	}

	@Test void aGrantSignedWithAHeldKeyIsRootedInThatKey() {
		AKeyPair holder = AKeyPair.generate();
		RequestContext ctx = user(holder);
		AString did = RT.ensureString(RT.getIn(run("v/ops/crypto/generate-key", Maps.of("keyring", true), ctx), "did"));
		AString bob = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ACell issued = run("v/ops/ucan/issue", Maps.of("aud", bob, "key", did,
			"att", Vectors.of(Capability.create(Strings.create("w/shared"), Capability.CRUD_READ))), ctx);
		assertEquals(did, RT.getIn(issued, "iss"));
		AString token = RT.ensureString(RT.getIn(issued, "token"));
		assertNotNull(JWT.verifyPublic(token, UCAN.fromDIDKey(did)));
		assertEquals(Strings.create(did + "/w/shared"), RT.getIn(JWT.parse(token).getClaims(), "att", 0, "with"));
		// It names only the key's own resources.
		assertTrue(failure("v/ops/ucan/issue", Maps.of("aud", bob, "key", did,
			"att", Vectors.of(Capability.create(Strings.create(ctx.getUserDID() + "/w/x"), Capability.CRUD_READ))), ctx)
			.contains(UCANAdapter.KEY_GRANTS_OWN_ONLY));
		// Another identity cannot sign with it.
		RequestContext other = user(AKeyPair.generate());
		failure("v/ops/ucan/issue", Maps.of("aud", bob, "key", ctx.getUserDID() + "/x/crypto/keys/" + did.toString().substring(8),
			"att", Vectors.of(Capability.create(Strings.create("w/x"), Capability.CRUD_READ))), other);
	}

	@Test void aTransactionResolvesItsHeldKeyBeforeAnyNetworkUse() {
		RequestContext ctx = user(AKeyPair.generate());
		AString did = RT.ensureString(RT.getIn(run("v/ops/crypto/generate-key", Maps.of("keyring", true), ctx), "did"));
		AMap<AString, ACell> tx = Maps.of(Fields.PEER, "localhost:1", Fields.ADDRESS, "#13", Fields.SOURCE, "(def foo 1)");
		assertEquals(ConvexAdapter.KEY_OR_SEED, failure("v/ops/convex/transact", tx.assoc(Fields.KEY, did).assoc(Fields.SEED, Strings.create("s/x")), ctx));
		RequestContext other = user(AKeyPair.generate());
		assertTrue(failure("v/ops/convex/transact", tx.assoc(Fields.KEY, did), other).contains(CryptoAdapter.KEY_NOT_HELD));
		// Held, the key resolves and the transaction gets as far as the peer.
		assertFalse(failure("v/ops/convex/transact", tx.assoc(Fields.KEY, did), ctx).contains(CryptoAdapter.KEY_NOT_HELD));
	}
}
