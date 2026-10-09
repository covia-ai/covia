package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.venue.Engine;

/**
 * Remote DID resolution is the one step of authentication that reaches out of
 * the venue, so it must not be repeatable at will by whoever sends tokens
 * (covia#539).
 */
public class VenueDIDVerifierTest {

	private static Engine engine;

	@BeforeAll
	static void setUp() {
		engine = Engine.createTemp(Maps.empty());
	}

	@AfterAll
	static void tearDown() {
		if (engine != null) engine.close();
	}

	@Test
	public void failedRemoteResolutionIsRememberedSoRepeatsCostNoFetch() {
		VenueDIDVerifier verifier = engine.didVerifier();
		// A reserved TLD: never resolves, fails without leaving the machine.
		AString did = Strings.create("did:web:nonexistent.invalid");
		Blob message = Blob.fromHex("00");
		Blob signature = Blob.wrap(new byte[64]);

		long before = verifier.remoteFetches.get();
		assertFalse(verifier.verifies(did, message, signature));
		assertEquals(before + 1, verifier.remoteFetches.get(), "the first verification resolves the document");
		assertFalse(verifier.verifies(did, message, signature));
		assertFalse(verifier.verifies(did, message, signature));
		assertEquals(before + 1, verifier.remoteFetches.get(),
			"a failed resolution is remembered: a stream of bad tokens for one DID costs one fetch");
	}

	@Test
	public void unresolvableShapesCostNoFetchAtAll() {
		VenueDIDVerifier verifier = engine.didVerifier();
		long before = verifier.remoteFetches.get();
		// Loopback, bare names and IP literals never resolve (SSRF containment).
		for (String did : new String[] {"did:web:localhost", "did:web:127.0.0.1", "did:web:venue"}) {
			assertFalse(verifier.verifies(Strings.create(did), Blob.fromHex("00"), Blob.wrap(new byte[64])));
		}
		assertEquals(before, verifier.remoteFetches.get());
	}
}
