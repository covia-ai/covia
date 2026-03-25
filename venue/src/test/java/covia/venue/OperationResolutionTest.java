package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Asset;
import covia.grid.Job;

/**
 * Tests for operation resolution: /a/ hash paths, /o/ user-scoped names,
 * and the venue operation registry.
 */
public class OperationResolutionTest {

	private Engine engine;
	private static final AString ALICE_DID = Strings.create("did:key:z6MkAlice");
	private static final RequestContext ALICE = RequestContext.of(ALICE_DID);

	@BeforeEach
	public void setup() {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	// ========== /a/<hash> resolution ==========

	@Test
	public void testResolveByHash() {
		// Resolve an existing operation by its hash
		Hash echoHash = engine.resolveOperation("test:echo");
		assertNotNull(echoHash);

		Asset asset = engine.resolveAsset(Strings.create(echoHash.toHexString()));
		assertNotNull(asset, "Should resolve by bare hex hash");

		Asset asset2 = engine.resolveAsset(Strings.create("/a/" + echoHash.toHexString()));
		assertNotNull(asset2, "Should resolve by /a/ prefixed hash");
	}

	@Test
	public void testResolveNonExistentHash() {
		Asset asset = engine.resolveAsset(Strings.create("/a/0000000000000000000000000000000000000000000000000000000000000000"));
		assertNull(asset);
	}

	// ========== /o/<name> resolution — inline metadata ==========

	@Test
	public void testResolveUserOpInlineMetadata() {
		// Write an inline operation definition to /o/my-echo
		ACell opMeta = Maps.of(
			"name", "My Echo",
			"description", "Custom echo operation",
			"operation", Maps.of(
				"adapter", "test:echo",
				"input", Maps.of("type", "object")
			)
		);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/my-echo", Fields.VALUE, opMeta),
			ALICE).awaitResult(5000);

		// Resolve via /o/ path
		Asset asset = engine.resolveAsset(Strings.create("/o/my-echo"), ALICE);
		assertNotNull(asset, "Should resolve inline operation from /o/ namespace");
		assertEquals(Strings.create("My Echo"), asset.meta().get(Fields.NAME));
	}

	@Test
	public void testInvokeUserOp() {
		// Write an operation definition that routes to test:echo
		ACell opMeta = Maps.of(
			"name", "My Echo",
			"operation", Maps.of("adapter", "test:echo")
		);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/my-echo", Fields.VALUE, opMeta),
			ALICE).awaitResult(5000);

		// Invoke it by /o/ reference
		Job job = engine.jobs().invokeOperation(
			Strings.create("/o/my-echo"),
			Maps.of("echo", "hello from /o/"),
			ALICE);
		ACell result = job.awaitResult(5000);
		assertNotNull(result);
		assertEquals(Strings.create("hello from /o/"), RT.getIn(result, "echo"));
	}

	// ========== /o/<name> resolution — hash reference ==========

	@Test
	public void testResolveUserOpHashReference() {
		// Get the hash of an existing operation
		Hash echoHash = engine.resolveOperation("test:echo");
		assertNotNull(echoHash);

		// Write the hash as a string reference in /o/
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/echo-ref",
				Fields.VALUE, Strings.create("/a/" + echoHash.toHexString())),
			ALICE).awaitResult(5000);

		// Resolve via /o/ — should follow the reference
		Asset asset = engine.resolveAsset(Strings.create("/o/echo-ref"), ALICE);
		assertNotNull(asset, "Should resolve hash reference from /o/ namespace");
	}

	@Test
	public void testInvokeUserOpHashReference() {
		Hash echoHash = engine.resolveOperation("test:echo");

		// Store hash reference in /o/
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/my-ref",
				Fields.VALUE, Strings.create("/a/" + echoHash.toHexString())),
			ALICE).awaitResult(5000);

		// Invoke via /o/ reference
		Job job = engine.jobs().invokeOperation(
			Strings.create("/o/my-ref"),
			Maps.of("echo", "via hash ref"),
			ALICE);
		ACell result = job.awaitResult(5000);
		assertEquals(Strings.create("via hash ref"), RT.getIn(result, "echo"));
	}

	// ========== /o/ resolution — edge cases ==========

	@Test
	public void testResolveNonExistentUserOp() {
		Asset asset = engine.resolveAsset(Strings.create("/o/no-such-op"), ALICE);
		assertNull(asset, "Non-existent /o/ name should return null");
	}

	@Test
	public void testResolveUserOpRequiresAuth() {
		// Anonymous context should not resolve /o/ operations
		Asset asset = engine.resolveAsset(Strings.create("/o/anything"), RequestContext.ANONYMOUS);
		assertNull(asset);
	}

	@Test
	public void testUserOpsIsolatedBetweenUsers() {
		RequestContext BOB = RequestContext.of(Strings.create("did:key:z6MkBob"));

		// Alice defines an operation
		ACell opMeta = Maps.of(
			"name", "Alice's Op",
			"operation", Maps.of("adapter", "test:echo")
		);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "o/private-op", Fields.VALUE, opMeta),
			ALICE).awaitResult(5000);

		// Alice can resolve it
		assertNotNull(engine.resolveAsset(Strings.create("/o/private-op"), ALICE));

		// Bob cannot
		assertNull(engine.resolveAsset(Strings.create("/o/private-op"), BOB),
			"Bob should not see Alice's /o/ operations");
	}

	// ========== Venue registry (backward compat) ==========

	@Test
	public void testVenueRegistryStillWorks() {
		// The legacy operation name registry should still resolve
		Asset asset = engine.resolveAsset(Strings.create("test:echo"));
		assertNotNull(asset, "Venue registry should still resolve operation names");
	}

	// ========== No collisions with distinct hashes ==========

	@Test
	public void testDistinctAssetsHaveDistinctHashes() {
		// langchain:openai and langchain:ollama should be different assets
		Hash openaiHash = engine.resolveOperation("langchain:openai");
		Hash ollamaHash = engine.resolveOperation("langchain:ollama");
		assertNotNull(openaiHash);
		assertNotNull(ollamaHash);
		assertNotEquals(openaiHash, ollamaHash,
			"Different adapter operations should have different hashes");
	}
}
