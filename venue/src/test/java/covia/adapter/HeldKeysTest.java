package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.crypto.ECIES;
import convex.core.crypto.util.Multikey;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.json.schema.JsonSchema;
import convex.core.util.JSON;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;
import covia.venue.TestEngine;

/** Keyrings through real Jobs: what each identity holds, and who may use it (HELD_KEYS.md). */
class HeldKeysTest {

	private static final String PHRASE = "abandon ".repeat(11) + "about";
	private static final Engine engine = TestEngine.ENGINE;
	private RequestContext ctx;
	private AKeyPair identity;

	@BeforeEach void caller() {
		identity = AKeyPair.generate();
		ctx = user(identity);
	}

	private static RequestContext user(AKeyPair key) {
		RequestContext user = RequestContext.of(UCAN.toDIDKey(key.getAccountKey()));
		engine.getVenueState().users().ensure(user.getUserDID());
		return user;
	}

	private Job job(String op, ACell input) {
		return engine.jobs().invokeOperation("v/ops/crypto/" + op, input, ctx);
	}
	private ACell run(String op, ACell input) {
		ACell result = job(op, input).awaitResult(5000);
		ACell metadata = engine.getAdapter("crypto").getOperations().get(Strings.create(op));
		assertNull(JsonSchema.validate(RT.ensureMap(RT.getIn(metadata, "operation", "input")),
			JSON.parse(JSON.toString(input))), "JSON input schema: " + op);
		assertNull(JsonSchema.validate(RT.ensureMap(RT.getIn(metadata, "operation", "output")),
			JSON.parse(JSON.toString(result))), "JSON output schema: " + op);
		return result;
	}
	private String failure(String op, ACell input) {
		Job job = job(op, input);
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		return job.getErrorMessage();
	}
	private void put(String name, String value) {
		engine.getVenueState().users().ensure(ctx.getUserDID()).secrets()
			.store(name, value, SecretStore.keys(engine.getKeyPair()));
	}
	private static String text(ACell cell, String field) {
		return assertInstanceOf(AString.class, RT.getIn(cell, field)).toString();
	}
	private static AccountKey accountKey(ACell result) {
		return AccountKey.wrap(assertInstanceOf(ABlob.class, RT.getIn(result, "accountKey")).getBytes());
	}
	private static boolean verifies(ACell signed, String hexData, AccountKey key) {
		Blob signature = assertInstanceOf(ABlob.class, RT.getIn(signed, "signature")).toFlatBlob();
		return ASignature.fromBlob(signature).verify(Blobs.parse(hexData).toFlatBlob(), key);
	}
	private String heldKey() {
		return text(run("generate-key", Maps.of("keyring", true, "label", "hot")), "did");
	}
	private String resource(String did) {
		return ctx.getUserDID() + "/x/crypto/keys/" + Multikey.encodePublicKey(UCAN.fromDIDKey(Strings.create(did)));
	}
	private RequestContext grantee(AKeyPair grantee, AVector<ACell> caps) {
		UCAN grant = UCAN.create(identity, grantee.getAccountKey(), System.currentTimeMillis() / 1000 + 3600,
			caps, Vectors.empty());
		return user(grantee).withProofs(Vectors.of(grant.toMap()));
	}

	@Test void aKeyBornInTheKeyringSignsAndIsNeverReturned() {
		ACell generated = run("generate-key", Maps.of("keyring", true));
		assertNull(RT.getIn(generated, "secret"));
		String did = text(generated, "did");
		for (String key : new String[] {did, did.substring("did:key:".length()), "0x" + accountKey(generated).toHexString()}) {
			ACell signed = run("sign", Maps.of("key", key, "data", "0x616263"));
			assertTrue(verifies(signed, "616263", accountKey(generated)));
			assertEquals(accountKey(generated), accountKey(signed));
		}
		assertEquals(CryptoAdapter.KEY_OR_SEED, failure("sign", Maps.of("key", did, "seed", "s/x", "data", "")));
		assertEquals(CryptoAdapter.KEYRING_OR_NAME, failure("generate-key", Maps.of("keyring", true, "name", "x")));
	}

	@Test void addingTakesOnlyYourOwnSecretsAndRefusesADuplicate() {
		AKeyPair seed = AKeyPair.generate();
		put("seed", seed.getSeed().toHexString());
		ACell added = run("add-key", Maps.of("seed", "s/seed", "label", "wallet"));
		assertEquals(seed.getAccountKey(), accountKey(added));
		assertEquals(CryptoAdapter.KEY_ALREADY_HELD, failure("add-key", Maps.of("seed", "s/seed")));
		run("add-key", Maps.of("seed", "s/seed", "label", "renamed", "overwrite", true));
		ACell listed = run("list-keys", Maps.empty());
		assertEquals(Strings.create("renamed"), RT.getIn(listed, "keys", 0, "label"));
		assertEquals(seed.getAccountKey(), AccountKey.wrap(((ABlob) RT.getIn(listed, "keys", 0, "accountKey")).getBytes()));
		// A literal, or another's secret even when lent, never enters a keyring.
		assertEquals(CryptoAdapter.SECRET_REF_REQUIRED, failure("add-key", Maps.of("seed", seed.getSeed().toHexString())));
		assertEquals(CryptoAdapter.ADD_OWN_SECRETS_ONLY,
			failure("add-key", Maps.of("seed", TestEngine.uniqueDID("HeldKeysTest-other") + "/s/seed")));
		assertEquals(CryptoAdapter.SEED_OR_MNEMONIC, failure("add-key", Maps.empty()));
	}

	@Test void othersUseAKeyOnlyUnderAGrantThatCoversIt() {
		String did = heldKey();
		String key = resource(did);
		AKeyPair bob = AKeyPair.generate();
		RequestContext owner = ctx;
		ctx = user(bob);
		failure("sign", Maps.of("key", key, "data", "0x00"));
		failure("list-keys", Maps.of("did", owner.getUserDID().toString()));
		// The kind of signing bounds the grant.
		ctx = grantee(bob, Vectors.of(Capability.create(Strings.create(key), Abilities.KEY_DECRYPT)));
		failure("sign", Maps.of("key", key, "data", "0x00"));
		ctx = grantee(bob, Vectors.of(Capability.create(Strings.create(key), Abilities.KEY_SIGN)));
		assertTrue(verifies(run("sign", Maps.of("key", key, "data", "0x00")), "00", UCAN.fromDIDKey(Strings.create(did))));
		// A grant of everything over the namespace includes its keys: the standard rules.
		ctx = grantee(bob, Vectors.of(Capability.create(Strings.create(owner.getUserDID() + "/"), Strings.create("*"))));
		run("sign", Maps.of("key", key, "data", "0x00"));
		assertEquals(1, ((AVector<?>) RT.getIn(run("list-keys", Maps.of("did", owner.getUserDID().toString())), "keys")).count());
		// A bare key names the caller's own keyring, which holds nothing.
		assertTrue(failure("sign", Maps.of("key", did, "data", "0x00")).contains(CryptoAdapter.KEY_NOT_HELD));
	}

	@Test void aScopeNarrowsTheHoldersOwnUse() {
		String did = heldKey();
		RequestContext owner = ctx;
		AMap<AString, ACell> invoke = Capability.create(null, Strings.create("invoke"));
		ctx = owner.withCaps(Vectors.of(invoke));
		failure("sign", Maps.of("key", did, "data", "0x00"));
		// A bare path in caps names the namespace the request acts in.
		ctx = owner.withCaps(Vectors.of(invoke, Capability.create(Strings.create("x/crypto/keys/"), Abilities.KEY_SIGN_RAW)));
		run("sign", Maps.of("key", did, "data", "0x00"));
		failure("decrypt", Maps.of("key", did, "ciphertext",
			ECIES.encrypt(UCAN.fromDIDKey(Strings.create(did)), Blob.wrap(new byte[] {1}))));
	}

	@Test void decryptAndJwtSignUseAHeldKey() {
		String did = heldKey();
		AccountKey key = UCAN.fromDIDKey(Strings.create(did));
		ACell plain = run("decrypt", Maps.of("key", did, "ciphertext", ECIES.encrypt(key, Blob.wrap(new byte[] {7, 8}))));
		assertEquals(Blob.wrap(new byte[] {7, 8}), RT.getIn(plain, "data"));
		ACell token = run("jwt-sign", Maps.of("key", did, "claims", Maps.of("sub", "x")));
		assertTrue(convex.auth.jwt.JWT.parse(RT.ensureString(RT.getIn(token, "token"))).verifyEdDSA(key));
	}

	@Test void aHeldPhraseDerivesOnlyIntoItsHoldersKeyring() {
		put("phrase", PHRASE);
		ACell held = run("add-key", Maps.of("mnemonic", "s/phrase"));
		String id = text(held, "did");
		assertEquals(Boolean.TRUE, ((convex.core.data.prim.CVMBool) RT.getIn(run("validate-mnemonic", Maps.of("mnemonic", id)), "valid")).booleanValue());
		assertEquals(CryptoAdapter.DERIVED_TO_KEYRING, failure("derive-key", Maps.of("mnemonic", id, "name", "leak")));
		ACell derived = run("derive-key", Maps.of("mnemonic", id, "keyring", true));
		// The same phrase as a secret derives the same key, readable there.
		ACell fromSecret = run("derive-key", Maps.of("mnemonic", "s/phrase", "name", "copy"));
		assertEquals(accountKey(fromSecret), accountKey(derived));
		run("sign", Maps.of("key", text(derived, "did"), "data", "0x00"));
		ACell generated = run("generate-mnemonic", Maps.of("keyring", true, "words", 24));
		assertNull(RT.getIn(generated, "secret"));
		assertEquals(2, ((AVector<?>) RT.getIn(run("list-keys", Maps.empty()), "mnemonics")).count());
	}

	@Test void removingEndsUse() {
		String did = heldKey();
		assertEquals(Boolean.TRUE, ((convex.core.data.prim.CVMBool) RT.getIn(run("remove-key", Maps.of("key", did)), "removed")).booleanValue());
		assertEquals(Boolean.FALSE, ((convex.core.data.prim.CVMBool) RT.getIn(run("remove-key", Maps.of("key", did)), "removed")).booleanValue());
		assertTrue(failure("sign", Maps.of("key", did, "data", "0x00")).contains(CryptoAdapter.KEY_NOT_HELD));
	}

	@Test void thePublicPrincipalHasNoKeyringAndReachesNoOtherHolders() {
		String key = resource(heldKey());
		AString alice = ctx.getUserDID();
		ctx = RequestContext.of(Strings.create(engine.getDIDString() + ":public"));
		assertNotNull(failure("generate-key", Maps.of("keyring", true)));
		assertNotNull(failure("list-keys", Maps.empty()));
		assertNotNull(failure("sign", Maps.of("key", key, "data", "0x00")));
		assertNotNull(failure("remove-key", Maps.of("key", key)));
		assertNotNull(failure("list-keys", Maps.of("did", alice.toString())));
		ctx = RequestContext.of(alice);
		assertEquals(1, ((AVector<?>) RT.getIn(run("list-keys", Maps.empty()), "keys")).count());
	}

	/** An agent is a principal of its own: its keys are its own, its owner
	 *  grants their use on its behalf, and they go when the agent does. */
	@Test void anAgentHoldsItsOwnKeysWhichGoWithIt() {
		RequestContext owner = ctx;
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "helper", Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo")),
			owner).awaitResult(5000);
		AString agentDID = Principals.agentDID(owner.getUserDID(), Strings.create("helper"));
		ctx = RequestContext.ofAgent(owner.getUserDID(), Strings.create("helper"));
		String did = heldKey();
		run("sign", Maps.of("key", did, "data", "0x00"));
		assertEquals(1, ((AVector<?>) RT.getIn(run("list-keys", Maps.empty()), "keys")).count());
		ctx = owner;
		assertEquals(0, ((AVector<?>) RT.getIn(run("list-keys", Maps.empty()), "keys")).count(),
			"the agent's keyring is not its owner's");

		// Bob signs with it under a grant the owner had the venue make.
		String key = agentDID + "/x/crypto/keys/" + Multikey.encodePublicKey(UCAN.fromDIDKey(Strings.create(did)));
		AKeyPair bob = AKeyPair.generate();
		AString token = RT.ensureString(RT.getIn(engine.jobs().invokeOperation("v/ops/ucan/issue",
			Maps.of(UCAN.AUD, UCAN.toDIDKey(bob.getAccountKey()), UCAN.ATT,
				Vectors.of(Capability.create(Strings.create(key), Abilities.KEY_SIGN))), owner).awaitResult(5000), "token"));
		ctx = user(bob).withProofs(Vectors.of(UCAN.fromJWT(token).toMap()));
		assertTrue(verifies(run("sign", Maps.of("key", key, "data", "0x00")), "00", UCAN.fromDIDKey(Strings.create(did))));

		engine.jobs().invokeOperation("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, "helper"), owner).awaitResult(5000);
		assertTrue(failure("sign", Maps.of("key", key, "data", "0x00")).contains(CryptoAdapter.KEY_NOT_HELD));
	}

	/** A request under user/sudo is bounded by the grants it presents: it
	 *  reaches the target's key by naming it, and a bare key is the delegate's
	 *  own, as for any principal that acts. */
	@Test void underSudoATargetsKeyIsNamedAndGranted() {
		String did = heldKey();
		String key = resource(did);
		AString target = ctx.getUserDID();
		RequestContext sudo = grantee(AKeyPair.generate(),
			Vectors.of(Capability.create(Strings.create(target + "/"), Strings.create("*")))).onBehalfOf(target);
		CryptoAdapter crypto = CryptoAdapter.of(engine);
		assertEquals(UCAN.fromDIDKey(Strings.create(did)),
			crypto.heldKeyPair(sudo, Strings.create(key), Abilities.KEY_SIGN_RAW).getAccountKey());
		assertThrows(AuthException.class, () -> crypto.heldKeyPair(sudo, Strings.create(did), Abilities.KEY_SIGN_RAW));
	}

	@Test void pagingCountsKeysThenPhrases() {
		heldKey();
		heldKey();
		run("generate-mnemonic", Maps.of("keyring", true));
		ACell first = run("list-keys", Maps.of("limit", 2));
		assertEquals(3L, ((convex.core.data.prim.CVMLong) RT.getIn(first, "total")).longValue());
		assertEquals(2L, ((convex.core.data.prim.CVMLong) RT.getIn(first, "nextOffset")).longValue());
		ACell rest = run("list-keys", Maps.of("offset", 2));
		assertEquals(0, ((AVector<?>) RT.getIn(rest, "keys")).count());
		assertEquals(1, ((AVector<?>) RT.getIn(rest, "mnemonics")).count());
		assertNull(RT.getIn(rest, "nextOffset"));
	}
}
