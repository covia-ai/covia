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

	// ========== Workspace path resolution (no leading slash) ==========
	//
	// Users should be able to write a hash/reference to a workspace path
	// (e.g. w/config/my-pipeline) and then invoke that path directly via
	// resolveAsset, instead of pasting opaque hashes around.

	@Test
	public void testResolveWorkspacePathToHash() {
		// Store an operation hash at a workspace path
		Hash echoHash = engine.resolveOperation("test:echo");
		assertNotNull(echoHash);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/config/echo-pointer",
			        Fields.VALUE, Strings.create(echoHash.toHexString())),
			ALICE).awaitResult(5000);

		// Resolve via the workspace path — recursive deref
		Asset asset = engine.resolveAsset(Strings.create("w/config/echo-pointer"), ALICE);
		assertNotNull(asset, "workspace path should dereference and resolve to test:echo");
		assertEquals(echoHash, asset.getID());
	}

	@Test
	public void testResolveWorkspacePathToOperationName() {
		// Workspace path → operation name string → registry resolution
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/config/op-pointer",
			        Fields.VALUE, Strings.create("test:echo")),
			ALICE).awaitResult(5000);

		Asset asset = engine.resolveAsset(Strings.create("w/config/op-pointer"), ALICE);
		assertNotNull(asset, "workspace path → op name → registry should resolve");
		assertEquals(engine.resolveOperation("test:echo"), asset.getID());
	}

	@Test
	public void testResolveWorkspacePathToInlineMetadata() {
		// Workspace path → inline operation metadata map
		ACell opMeta = Maps.of(
			"name", "Inline Pipeline",
			"description", "Inline operation stored in workspace",
			"operation", Maps.of(
				"adapter", "test:echo",
				"input", Maps.of("type", "object")
			)
		);
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/config/inline-op", Fields.VALUE, opMeta),
			ALICE).awaitResult(5000);

		Asset asset = engine.resolveAsset(Strings.create("w/config/inline-op"), ALICE);
		assertNotNull(asset, "workspace path with inline metadata should resolve");
		assertEquals(Strings.create("Inline Pipeline"), asset.meta().get(Fields.NAME));
	}

	@Test
	public void testResolveWorkspacePathMissing() {
		assertNull(engine.resolveAsset(Strings.create("w/config/nonexistent"), ALICE));
	}

	@Test
	public void testResolveWorkspacePathRequiresAuth() {
		// Internal context has no caller DID — workspace lookup must fail cleanly
		engine.jobs().invokeOperation("covia:write",
			Maps.of(Fields.PATH, "w/config/internal-test",
			        Fields.VALUE, Strings.create("test:echo")),
			ALICE).awaitResult(5000);

		// Internal context can't resolve workspace paths because they're per-user
		assertNull(engine.resolveAsset(Strings.create("w/config/internal-test")));
	}

	// ========== DID URL self-reference round-trip ==========
	//
	// asset_store returns the venue's own DID URL form (e.g.
	// did:key:VENUE:public/a/<hash>). Feeding that back into resolveAsset
	// must work without crashing — it used to fall through to Grid.connect
	// which only handles did:web.

	@Test
	public void testResolveLocalDidKeyUrlForVenueAsset() {
		// Pick any venue-installed asset hash
		Hash echoHash = engine.resolveOperation("test:echo");
		assertNotNull(echoHash);

		// Construct the DID URL form: <venue-did>:public/a/<hash>
		// (matches what asset_store returns when called by an anonymous user)
		String didUrl = engine.getDIDString().toString() + ":public/a/" + echoHash.toHexString();

		Asset asset = engine.resolveAsset(Strings.create(didUrl), ALICE);
		assertNotNull(asset, "did:key:VENUE:public/a/<hash> should resolve as local");
		assertEquals(echoHash, asset.getID());
	}

	@Test
	public void testResolveBareVenueDidUrl() {
		// Without the :public sub-id — bare venue DID
		Hash echoHash = engine.resolveOperation("test:echo");
		assertNotNull(echoHash);

		String didUrl = engine.getDIDString().toString() + "/a/" + echoHash.toHexString();
		Asset asset = engine.resolveAsset(Strings.create(didUrl), ALICE);
		assertNotNull(asset, "did:key:VENUE/a/<hash> should resolve as local");
		assertEquals(echoHash, asset.getID());
	}

	@Test
	public void testResolveUnknownDidKeyReturnsNullNotCrash() {
		// A different did:key venue we have no record of — must NOT throw
		// "Unrecognised DID method: key" via Grid.connect
		String foreignDid = "did:key:z6MkUnknownVenueKeyThatDoesNotExist123456789ABCDEF/a/"
			+ "0000000000000000000000000000000000000000000000000000000000000000";
		assertNull(engine.resolveAsset(Strings.create(foreignDid), ALICE),
			"unknown did:key should return null, not throw");
	}
}
