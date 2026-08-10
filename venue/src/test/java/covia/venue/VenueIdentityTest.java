package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.auth.VenueDIDVerifier;
import covia.venue.server.VenueServer;

/**
 * Operator-declared venue identity (covia#343): a venue with a public hostname
 * may declare {@code did:web:<hostname>} as its identity via the {@code did}
 * config key; declarations are validated fail-closed, and did:web-identified
 * principals verify at every ingress seam exactly like did:key ones.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class VenueIdentityTest {

	private static final String HOST = "identity.example";
	private static final AString WEB_DID = Strings.create("did:web:" + HOST);

	private VenueServer server;
	private AKeyPair venueKey;
	private AKeyPair userKey;

	/** Deterministic 32-byte seed so the test knows the venue's key pair. */
	private static String seedHex(int fill) {
		byte[] seed = new byte[32];
		java.util.Arrays.fill(seed, (byte) fill);
		return convex.core.data.Blob.wrap(seed).toHexString();
	}

	@BeforeAll
	void setup() {
		String seed = seedHex(0x51);
		venueKey = AKeyPair.create(convex.core.data.Blob.fromHex(seed));
		userKey = AKeyPair.generate();
		server = VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.HOSTNAME, HOST,
			Config.DID, WEB_DID,
			Config.SEED, Strings.create(seed),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false)),
			Config.USERS, Maps.of(
				Config.AUTO_CREATE, true,
				Config.BOOTSTRAP, Maps.of("sabine", Maps.of(
					Fields.AUTHENTICATION_KEYS, Vectors.of(Strings.create("did:key:"
						+ Multikey.encodePublicKey(userKey.getAccountKey()))))))));
	}

	@AfterAll
	void close() {
		if (server != null) server.close();
	}

	// ========== Declaration ==========

	@Test
	void declaredWebDidIsTheVenueIdentity() {
		assertEquals(WEB_DID, server.getEngine().getDIDString());
		assertEquals(WEB_DID.toString(), server.getEngine().getDID().toString());
	}

	@Test
	void didDocumentPresentsTheDeclaredIdentity() {
		ACell ddo = server.getEngine().getDIDDocument("https://" + HOST + "/api/v1");
		assertEquals(WEB_DID, RT.getIn(ddo, "id"));
		// Identity == did:web, so there is no alias and no alsoKnownAs entry:
		// the did:key appears only as key material in verificationMethod.
		assertNull(RT.getIn(ddo, "alsoKnownAs"));
	}

	// ========== Config validation (fail closed) ==========

	@Test
	void mismatchedWebHostIsRejected() {
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.HOSTNAME, HOST,
			Config.DID, Strings.create("did:web:other.example"))));
	}

	@Test
	void nonPublicWebHostIsRejected() {
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.HOSTNAME, "localhost",
			Config.DID, Strings.create("did:web:localhost"))));
	}

	@Test
	void pathSegmentsInDeclaredWebDidAreRejected() {
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.HOSTNAME, HOST,
			Config.DID, Strings.create("did:web:" + HOST + ":u:alice"))));
	}

	@Test
	void unsupportedMethodAndMalformedKeyAreRejected() {
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.DID, Strings.create("did:example:12345"))));
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.DID, Strings.create("did:key:not-a-multikey"))));
	}

	@Test
	void declaredKeyDidMustMatchTheVenueKeyPair() {
		AString foreign = Strings.create("did:key:"
			+ Multikey.encodePublicKey(AKeyPair.generate().getAccountKey()));
		assertThrows(RuntimeException.class, () -> VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.SEED, Strings.create(seedHex(0x52)),
			Config.DID, foreign)),
			"a did:key identity pin must refuse a mismatched key pair");
	}

	@Test
	void matchingKeyDidPinIsAccepted() {
		String seed = seedHex(0x53);
		AKeyPair kp = AKeyPair.create(convex.core.data.Blob.fromHex(seed));
		AString pinned = Strings.create("did:key:"
			+ Multikey.encodePublicKey(kp.getAccountKey()));
		VenueServer pinnedServer = VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.SEED, Strings.create(seed),
			Config.DID, pinned));
		try {
			assertEquals(pinned, pinnedServer.getEngine().getDIDString());
		} finally {
			pinnedServer.close();
		}
	}

	// ========== Verification through the identity ==========

	// NOTE: the ucan:issue → ucan:verify roundtrip under a did:web identity is
	// deliberately not tested here — venue minting still hand-rolls its JWTs,
	// and migrating it to the Convex UCAN profile API is covia#322. These tests
	// pin the Phase A surface: the verifier resolves did:web principals.

	@Test
	void venueWebIdentityVerifiesVenueSignatures() {
		VenueDIDVerifier verifier = server.getEngine().didVerifier();
		convex.core.data.Blob message = convex.core.data.Blob.wrap(
			"venue identity proof".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		convex.core.data.Blob sig = venueKey.sign(message).toFlatBlob();
		assertTrue(verifier.verifies(WEB_DID, message, sig),
			"the declared did:web identity must verify the venue's own signatures");
		// The key-derived form verifies identically (stateless did:key path).
		AString keyForm = Strings.create("did:key:"
			+ Multikey.encodePublicKey(venueKey.getAccountKey()));
		assertTrue(verifier.verifies(keyForm, message, sig));
		// A foreign key's signature must not verify as the venue.
		convex.core.data.Blob forged = AKeyPair.generate().sign(message).toFlatBlob();
		assertTrue(!verifier.verifies(WEB_DID, message, forged));
	}

	@Test
	void managedUserWebDidVerifiesRegisteredKeys() {
		VenueDIDVerifier verifier = server.getEngine().didVerifier();
		AString userDID = server.getEngine().managedUserDID(Strings.create("sabine"));
		convex.core.data.Blob message = convex.core.data.Blob.wrap(
			"user proof".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(verifier.verifies(userDID, message, userKey.sign(message).toFlatBlob()),
			"a managed user's did:web must verify against their active registered key");
		assertTrue(!verifier.verifies(userDID, message,
			AKeyPair.generate().sign(message).toFlatBlob()),
			"an unregistered key must not verify as the managed user");
	}

	@Test
	void clientsMayAudienceBindToEitherPublishedForm() throws Exception {
		String keyForm = "did:key:" + Multikey.encodePublicKey(venueKey.getAccountKey());
		assertEchoWorks(VenueAuth.keyPair(AKeyPair.generate(), WEB_DID.toString()));
		assertEchoWorks(VenueAuth.keyPair(AKeyPair.generate(), keyForm));
	}

	private void assertEchoWorks(VenueAuth auth) throws Exception {
		VenueHTTP client = VenueHTTP.create(
			URI.create("http://localhost:" + server.port()), auth);
		client.setTimeout(5000);
		Job job = client.invokeAndWait(Strings.create("v/test/ops/echo"),
			Maps.of(Fields.VALUE, Strings.create("identity")));
		assertEquals(Status.COMPLETE, job.getStatus());
	}

	// ========== did:web resolution URL (SSRF containment) ==========

	@Test
	void didWebDocumentUrlFollowsTheResolutionAlgorithm() {
		assertEquals("https://venue.example/.well-known/did.json",
			VenueDIDVerifier.didWebDocumentURL("did:web:venue.example"));
		assertEquals("https://venue.example/u/alice/did.json",
			VenueDIDVerifier.didWebDocumentURL("did:web:venue.example:u:alice"));
		assertNull(VenueDIDVerifier.didWebDocumentURL("did:web:localhost"));
		assertNull(VenueDIDVerifier.didWebDocumentURL("did:web:127.0.0.1"));
		assertNull(VenueDIDVerifier.didWebDocumentURL("did:web:venue.example:"));
		assertNull(VenueDIDVerifier.didWebDocumentURL("did:key:z6Mk"));
		assertNull(VenueDIDVerifier.didWebDocumentURL(null));
	}

	@Test
	void verifierFailsClosedOnUnknownMethods() {
		VenueDIDVerifier verifier = server.getEngine().didVerifier();
		assertTrue(!verifier.verifies(Strings.create("did:example:zzz"),
			convex.core.data.Blob.wrap(new byte[] {1}),
			convex.core.data.Blob.wrap(new byte[64])));
		assertTrue(!verifier.verifies(null, null, null));
	}
}
