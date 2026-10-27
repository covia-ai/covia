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
import convex.core.data.Vectors;
import covia.venue.Engine;

/**
 * Remote DID resolution is the one step of authentication that reaches out of
 * the venue, so it must not be repeatable at will by whoever sends tokens
 * (covia#539).
 */
public class VenueDIDVerifierTest {

	private static Engine engine;

	/** Allow-listed, so its resolution reaches the fetch; a reserved TLD, so the fetch fails locally. */
	private static final String UNRESOLVABLE = "nonexistent.invalid";
	private static final String BLOCKED = "blocked.invalid";

	@BeforeAll
	static void setUp() {
		engine = Engine.createTemp(Maps.of("adapters", Maps.of("http", Maps.of(
			"allowedHosts", Vectors.of(UNRESOLVABLE),
			"blockedHosts", Vectors.of(BLOCKED)))));
		// The http adapter holds the outbound guard every document fetch passes
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void tearDown() {
		if (engine != null) engine.close();
	}

	// Each test counts on a verifier of its own: tests run concurrently

	@Test
	public void failedRemoteResolutionIsRememberedSoRepeatsCostNoFetch() {
		VenueDIDVerifier verifier = new VenueDIDVerifier(engine);
		AString did = Strings.create("did:web:" + UNRESOLVABLE);
		Blob message = Blob.fromHex("00");
		Blob signature = Blob.wrap(new byte[64]);

		assertFalse(verifier.verifies(did, message, signature));
		assertEquals(1, verifier.remoteFetches.get(), "the first verification resolves the document");
		assertFalse(verifier.verifies(did, message, signature));
		assertFalse(verifier.verifies(did, message, signature));
		assertEquals(1, verifier.remoteFetches.get(),
			"a failed resolution is remembered: a stream of bad tokens for one DID costs one fetch");
	}

	@Test
	public void unresolvableShapesCostNoFetchAtAll() {
		VenueDIDVerifier verifier = new VenueDIDVerifier(engine);
		// Loopback, bare names and IP literals never resolve (SSRF containment).
		for (String did : new String[] {"did:web:localhost", "did:web:127.0.0.1", "did:web:venue"}) {
			assertFalse(verifier.verifies(Strings.create(did), Blob.fromHex("00"), Blob.wrap(new byte[64])));
		}
		assertEquals(0, verifier.remoteFetches.get());
	}

	@Test
	public void documentHostTheOutboundGuardRefusesIsNeverFetched() {
		// The host is the token's choice, so it passes the same guard as any outbound target
		VenueDIDVerifier verifier = new VenueDIDVerifier(engine);
		AString did = Strings.create("did:web:" + BLOCKED + ":u:alice");
		assertFalse(verifier.verifies(did, Blob.fromHex("00"), Blob.wrap(new byte[64])));
		assertFalse(verifier.verifies(did, Blob.fromHex("00"), Blob.wrap(new byte[64])));
		assertEquals(0, verifier.remoteFetches.get());
	}
}
