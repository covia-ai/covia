package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
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
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
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
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/my-echo", Fields.VALUE, opMeta),
			ALICE).awaitResult(5000);

		// Resolve via /o/ path
		Asset asset = engine.resolveAsset(Strings.create("/o/my-echo"), ALICE);
		assertNotNull(asset, "Should resolve inline operation from /o/ namespace");
		assertEquals(Strings.create("My Echo"), asset.meta().get(Fields.NAME));
	}

	@Test
	public void testInvokeUserOp() {
		// Write an operation definition that routes to test:echo. The
		// operation.adapter field is the internal dispatch string used by
		// the test adapter to identify the sub-op — kept as-is even though
		// the catalog path for echo is v/test/ops/echo.
		ACell opMeta = Maps.of(
			"name", "My Echo",
			"operation", Maps.of("adapter", "test:echo")
		);
		engine.jobs().invokeOperation("v/ops/covia/write",
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

	// ========== /o/<name> resolution — string values are NOT references ==========
	//
	// Per OPERATIONS.md §4 (no ref following), the resolver does not chase
	// any kind of stored reference. A string written to /o/<name> is
	// opaque data, not an alias. The only callable shape is a map with an
	// "operation" field. To "alias" a venue op, the user copies it instead.

	@Test
	public void testStringAtUserOpIsNotResolvableAsOperation() {
		// Storing a string at /o/<name> is allowed (it's just data) but it
		// is NOT treated as a reference. resolveAsset returns null because
		// the value isn't a map with an operation field.
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
		assertNotNull(echoHash);

		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/echo-ref",
				Fields.VALUE, Strings.create("/a/" + echoHash.toHexString())),
			ALICE).awaitResult(5000);

		// resolvePath returns the literal string (the value IS there)
		ACell raw = engine.resolvePath(Strings.create("/o/echo-ref"), ALICE);
		assertEquals(Strings.create("/a/" + echoHash.toHexString()), raw,
			"resolvePath should return the literal stored value");

		// resolveAsset returns null — strings are not operations
		Asset asset = engine.resolveAsset(Strings.create("/o/echo-ref"), ALICE);
		assertNull(asset,
			"strings stored at /o/<name> are not resolvable as operations (no ref following)");
	}

	@Test
	public void testInvokingUserOpWithStringValueFails() {
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/my-ref",
				Fields.VALUE, Strings.create("/a/" + echoHash.toHexString())),
			ALICE).awaitResult(5000);

		// Invoking should fail because /o/my-ref is a string, not an operation
		assertThrows(Exception.class, () -> {
			engine.jobs().invokeOperation(
				Strings.create("/o/my-ref"),
				Maps.of("echo", "via hash ref"),
				ALICE);
		}, "invoking a /o/ entry that holds a string should fail explicitly");
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
		engine.jobs().invokeOperation("v/ops/covia/write",
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
		Asset asset = engine.resolveAsset(Strings.create("v/test/ops/echo"));
		assertNotNull(asset, "Venue registry should still resolve operation names");
	}

	// ========== No collisions with distinct hashes ==========

	@Test
	public void testDistinctAssetsHaveDistinctHashes() {
		// langchain:openai and langchain:ollama should be different assets
		Hash openaiHash = engine.resolveAsset(Strings.create("v/ops/langchain/openai")).getID();
		Hash ollamaHash = engine.resolveAsset(Strings.create("v/ops/langchain/ollama")).getID();
		assertNotNull(openaiHash);
		assertNotNull(ollamaHash);
		assertNotEquals(openaiHash, ollamaHash,
			"Different adapter operations should have different hashes");
	}

	// ========== Workspace path resolution (no leading slash) ==========
	//
	// Per OPERATIONS.md §4 (no ref following), workspace paths resolve to
	// the literal value at the path. A string stored at a workspace path
	// is opaque data, not a reference. To make a workspace path callable
	// as an operation, the user must store inline operation metadata
	// (a map with an "operation" field) at that path.

	@Test
	public void testStringAtWorkspacePathIsNotResolvableAsOperation() {
		// A hash string stored at a workspace path is data, not a reference.
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
		assertNotNull(echoHash);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/config/echo-pointer",
			        Fields.VALUE, Strings.create(echoHash.toHexString())),
			ALICE).awaitResult(5000);

		// resolvePath returns the literal string
		ACell raw = engine.resolvePath(Strings.create("w/config/echo-pointer"), ALICE);
		assertEquals(Strings.create(echoHash.toHexString()), raw);

		// resolveAsset returns null — strings are not operations
		Asset asset = engine.resolveAsset(Strings.create("w/config/echo-pointer"), ALICE);
		assertNull(asset, "string at workspace path is not resolvable as an operation");
	}

	@Test
	public void testOperationNameStringAtWorkspacePathIsNotResolvableAsOperation() {
		// Same: an op name string stored at a workspace path is data, not
		// a reference. The legacy registry fallback is only triggered for
		// the bare-string ref form, not for stored values that happen to
		// be strings.
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/config/op-pointer",
			        Fields.VALUE, Strings.create("v/test/ops/echo")),
			ALICE).awaitResult(5000);

		Asset asset = engine.resolveAsset(Strings.create("w/config/op-pointer"), ALICE);
		assertNull(asset, "op name string at workspace path is not resolvable as an operation");
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
		engine.jobs().invokeOperation("v/ops/covia/write",
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
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/config/internal-test",
			        Fields.VALUE, Strings.create("v/test/ops/echo")),
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
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
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
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
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

	// ========== Engine.resolvePath — pure path navigation ==========
	//
	// Per OPERATIONS.md §4, resolvePath returns the literal value at the
	// resolved local lattice cell. No reference following, no asset
	// interpretation, no recursion. Tests cover all input forms accepted
	// by the universal resolution chain.

	@Test
	public void testResolvePathReturnsAssetMetadataForBareHash() {
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
		assertNotNull(echoHash);

		ACell value = engine.resolvePath(Strings.create(echoHash.toHexString()), ALICE);
		assertNotNull(value, "bare hex hash should resolve to asset metadata");
		assertTrue(value instanceof convex.core.data.AMap,
			"resolved value should be a metadata map");
	}

	@Test
	public void testResolvePathReturnsAssetMetadataForSlashAHash() {
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
		ACell value = engine.resolvePath(
			Strings.create("/a/" + echoHash.toHexString()), ALICE);
		assertNotNull(value);
		assertTrue(value instanceof convex.core.data.AMap);
	}

	@Test
	public void testResolvePathReturnsLiteralUserOpValue() {
		// Inline metadata: resolvePath returns the literal map
		ACell opMeta = Maps.of(
			"name", "Custom",
			"operation", Maps.of("adapter", "test:echo")
		);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/custom", Fields.VALUE, opMeta),
			ALICE).awaitResult(5000);

		ACell value = engine.resolvePath(Strings.create("/o/custom"), ALICE);
		assertEquals(opMeta, value, "resolvePath should return the literal stored map");
	}

	@Test
	public void testResolvePathReturnsLiteralStringAtUserOp() {
		// A string at /o/<name> is opaque data; resolvePath returns it literally
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/note",
				Fields.VALUE, Strings.create("just a string")),
			ALICE).awaitResult(5000);

		ACell value = engine.resolvePath(Strings.create("/o/note"), ALICE);
		assertEquals(Strings.create("just a string"), value);
	}

	@Test
	public void testResolvePathReturnsLiteralValueAtWorkspacePath() {
		ACell raw = Maps.of("kind", "config", "version", CVMLong.create(42));
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/cfg/x", Fields.VALUE, raw),
			ALICE).awaitResult(5000);

		ACell value = engine.resolvePath(Strings.create("w/cfg/x"), ALICE);
		assertEquals(raw, value);
	}

	@Test
	public void testResolvePathReturnsNullForMissingPath() {
		assertNull(engine.resolvePath(Strings.create("/o/no-such"), ALICE));
		assertNull(engine.resolvePath(Strings.create("w/no/such/path"), ALICE));
		assertNull(engine.resolvePath(
			Strings.create("/a/0000000000000000000000000000000000000000000000000000000000000000"), ALICE));
	}

	@Test
	public void testResolvePathDoesNotChaseStringRefs() {
		// Even if the string at /o/x looks like a path, resolvePath returns
		// the string literally — no automatic chasing.
		Hash echoHash = engine.resolveAsset(Strings.create("v/test/ops/echo")).getID();
		AString hashStr = Strings.create("/a/" + echoHash.toHexString());

		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/looks-like-ref", Fields.VALUE, hashStr),
			ALICE).awaitResult(5000);

		ACell value = engine.resolvePath(Strings.create("/o/looks-like-ref"), ALICE);
		assertEquals(hashStr, value,
			"resolvePath returns the literal string, NOT the chased target");
	}

	@Test
	public void testResolvePathDoesNotChaseRefMaps() {
		// Even if the value is a {ref: ...} map, resolvePath returns it
		// literally — there is no special "ref" interpretation.
		ACell refMap = Maps.of(
			"ref", Strings.create("v/ops/json/merge")
		);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/has-ref-field", Fields.VALUE, refMap),
			ALICE).awaitResult(5000);

		ACell value = engine.resolvePath(Strings.create("/o/has-ref-field"), ALICE);
		assertEquals(refMap, value,
			"resolvePath returns the literal map, NOT the chased target");

		// And resolveAsset returns null because it's not a map with operation field
		Asset asset = engine.resolveAsset(Strings.create("/o/has-ref-field"), ALICE);
		assertNull(asset, "{ref:...} maps are not callable as operations");
	}

	@Test
	public void testResolvePathRequiresAuth() {
		// Anonymous context can't read /o/ paths
		assertNull(engine.resolvePath(Strings.create("/o/anything"), RequestContext.ANONYMOUS));
	}

	@Test
	public void testResolveAssetUsesResolvePathThenInterprets() {
		// resolveAsset = resolvePath + Asset.fromMeta
		// For an inline op, both should return the same thing semantically.
		ACell opMeta = Maps.of(
			"name", "Inline",
			"operation", Maps.of("adapter", "test:echo")
		);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "o/inline", Fields.VALUE, opMeta),
			ALICE).awaitResult(5000);

		ACell rawValue = engine.resolvePath(Strings.create("/o/inline"), ALICE);
		Asset asset = engine.resolveAsset(Strings.create("/o/inline"), ALICE);

		assertNotNull(rawValue);
		assertNotNull(asset);
		assertEquals(rawValue, asset.meta(),
			"resolveAsset should give the same metadata as resolvePath for inline ops");
	}

	// ========== /v/ namespace — venue globals via VenueGlobalsResolver ==========
	//
	// Per OPERATIONS.md §3, /v/ is a virtual prefix that resolves to the
	// venue user's /w/global/ sub-tree. Reads are universally allowed; writes
	// require the venue identity (RequestContext.INTERNAL or the venue's own
	// DID). The venue user record exists at engine startup so /v/ paths
	// always have a backing namespace to navigate.

	@Test
	public void testWriteAndReadVenueGlobalAsInternal() {
		// Internal context can write to /v/
		ACell value = Maps.of("name", "test entry", "version", CVMLong.create(1));
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "v/test/entry", Fields.VALUE, value),
			RequestContext.INTERNAL).awaitResult(5000);

		// Any caller can read it
		ACell readBack = engine.resolvePath(Strings.create("v/test/entry"), ALICE);
		assertEquals(value, readBack);

		// Anonymous can also read
		ACell anonRead = engine.resolvePath(Strings.create("v/test/entry"), RequestContext.ANONYMOUS);
		assertEquals(value, anonRead);
	}

	@Test
	public void testNonVenueCallerCannotWriteToV() {
		// Alice (a regular user) cannot write to /v/
		assertThrows(Exception.class, () -> {
			engine.jobs().invokeOperation("v/ops/covia/write",
				Maps.of(Fields.PATH, "v/test/forbidden", Fields.VALUE, Strings.create("nope")),
				ALICE).awaitResult(5000);
		}, "non-venue caller should be rejected when writing to /v/");
	}

	@Test
	public void testVenueGlobalsRouteThroughVenueUserWGlobal() {
		// Verify the underlying storage location: writing to v/foo should be
		// readable from <venue-DID>/w/global/foo by the venue itself.
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "v/foo", Fields.VALUE, Strings.create("hello")),
			RequestContext.INTERNAL).awaitResult(5000);

		// Read directly from the venue user's workspace via the venue's own
		// internal context (which can read its own /w/).
		AString venueDID = engine.getDIDString();
		RequestContext venueCtx = RequestContext.of(venueDID);
		ACell value = engine.resolvePath(Strings.create("w/global/foo"), venueCtx);
		assertEquals(Strings.create("hello"), value,
			"v/foo should physically live at <venue-DID>/w/global/foo");
	}

	@Test
	public void testVenueGlobalsReadableViaCoviaRead() {
		// Universal resolution: covia:read should accept v/ paths
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "v/welcome", Fields.VALUE, Strings.create("hi from venue")),
			RequestContext.INTERNAL).awaitResult(5000);

		Job job = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "v/welcome"), ALICE);
		ACell result = job.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(Strings.create("hi from venue"), RT.getIn(result, "value"));
	}

	@Test
	public void testVenueGlobalsCanContainStructuredData() {
		// Maps and vectors should round-trip cleanly through /v/
		ACell complex = Maps.of(
			"name", "JSON Merge",
			"operation", Maps.of("adapter", "json:merge"),
			"tags", convex.core.data.Vectors.of(Strings.create("data"), Strings.create("util"))
		);
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "v/ops/json/merge", Fields.VALUE, complex),
			RequestContext.INTERNAL).awaitResult(5000);

		// Read via /v/ and verify it round-trips
		ACell readBack = engine.resolvePath(Strings.create("v/ops/json/merge"), ALICE);
		assertEquals(complex, readBack);

		// And it's resolvable as an Asset because it has an operation field
		Asset asset = engine.resolveAsset(Strings.create("v/ops/json/merge"), ALICE);
		assertNotNull(asset, "v/ops entry with operation field should resolve as Asset");
	}

	@Test
	public void testVenueGlobalsMissingPathReturnsNull() {
		ACell value = engine.resolvePath(Strings.create("v/no/such/thing"), ALICE);
		assertNull(value);
	}

	// ========== /v/info/ — venue introspection materialised at startup ==========
	//
	// Per OPERATIONS.md §3 and §7, addDemoAssets calls materialiseVenueInfo
	// which writes name, did, version, started, protocols, and per-adapter
	// summaries to /v/info/. Tests verify each path is populated and readable.

	@Test
	public void testVenueInfoDidIsPopulated() {
		ACell did = engine.resolvePath(Strings.create("v/info/did"), ALICE);
		assertNotNull(did, "/v/info/did should be populated at startup");
		assertEquals(engine.getDIDString(), did,
			"/v/info/did should match engine.getDIDString()");
	}

	@Test
	public void testVenueInfoVersionIsPopulated() {
		ACell version = engine.resolvePath(Strings.create("v/info/version"), ALICE);
		assertNotNull(version, "/v/info/version should be populated at startup");
		assertTrue(version instanceof AString, "version should be a string");
	}

	@Test
	public void testVenueInfoStartedIsPopulated() {
		ACell started = engine.resolvePath(Strings.create("v/info/started"), ALICE);
		assertNotNull(started, "/v/info/started should be populated at startup");
		assertTrue(started instanceof CVMLong, "started should be a long (epoch ms)");
		long startedMs = ((CVMLong) started).longValue();
		assertTrue(startedMs > 0, "startedMs should be a positive epoch time");
		// Sanity check: startedMs should be in the recent past
		long now = System.currentTimeMillis();
		assertTrue(startedMs <= now, "startedMs should not be in the future");
	}

	@Test
	public void testVenueInfoProtocolsIsPopulated() {
		ACell protocols = engine.resolvePath(Strings.create("v/info/protocols"), ALICE);
		assertNotNull(protocols, "/v/info/protocols should be populated at startup");
		assertTrue(protocols instanceof convex.core.data.AVector,
			"protocols should be a vector");
	}

	@Test
	public void testVenueInfoAdaptersHasJsonAdapter() {
		// JSONAdapter is one of the registered adapters; its summary should
		// be findable at /v/info/adapters/json
		ACell summary = engine.resolvePath(Strings.create("v/info/adapters/json"), ALICE);
		assertNotNull(summary, "/v/info/adapters/json should exist");
		assertTrue(summary instanceof AMap, "adapter summary should be a map");
		assertEquals(Strings.create("json"), RT.getIn(summary, "name"));
		assertNotNull(RT.getIn(summary, "description"));
		assertNotNull(RT.getIn(summary, "operations"));
	}

	@Test
	public void testVenueInfoAccessibleViaCoviaRead() {
		// Universal resolution: covia:read should work for /v/info/ paths
		Job job = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "v/info/did"), ALICE);
		ACell result = job.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		assertEquals(engine.getDIDString(), RT.getIn(result, "value"));
	}

	// ========== /v/ops/ — adapter primitives via new installAsset overload ==========
	//
	// JSONAdapter has been migrated to use installAsset(catalogPath, resourcePath).
	// Verify the four json ops appear at /v/ops/json/<op> as inline metadata.

	@Test
	public void testJsonAdapterPrimitivesAtVOps() {
		// Each JSONAdapter primitive should be discoverable at v/ops/json/<op>
		String[] ops = { "merge", "cond", "assoc", "select" };
		for (String op : ops) {
			ACell value = engine.resolvePath(
				Strings.create("v/ops/json/" + op), ALICE);
			assertNotNull(value, "/v/ops/json/" + op + " should exist");
			assertTrue(value instanceof AMap, "should be inline metadata map");
			AMap<AString, ACell> meta = (AMap<AString, ACell>) value;
			assertNotNull(meta.get(Strings.create("operation")),
				"metadata should have an 'operation' field");
		}
	}

	@Test
	public void testJsonMergeAtVOpsHasFullMetadata() {
		ACell value = engine.resolvePath(Strings.create("v/ops/json/merge"), ALICE);
		assertNotNull(value);
		AMap<AString, ACell> meta = (AMap<AString, ACell>) value;
		// The metadata should have name, description, and operation
		assertEquals(Strings.create("JSON Merge"), meta.get(Strings.create("name")));
		assertNotNull(meta.get(Strings.create("description")));
		AMap<AString, ACell> opBlock = (AMap<AString, ACell>) meta.get(Strings.create("operation"));
		assertNotNull(opBlock);
		assertEquals(Strings.create("json:merge"), opBlock.get(Strings.create("adapter")));
	}

	@Test
	public void testVOpsEntryIsCallableAsAsset() {
		// /v/ops entries should resolve as Assets (they have an operation field)
		Asset asset = engine.resolveAsset(Strings.create("v/ops/json/merge"), ALICE);
		assertNotNull(asset, "v/ops/json/merge should resolve as Asset");
	}

	@Test
	public void testVOpsAccessibleViaCoviaList() {
		// covia:list on v/ops/json should show the four primitives
		Job job = engine.jobs().invokeOperation("v/ops/covia/list",
			Maps.of(Fields.PATH, "v/ops/json"), ALICE);
		ACell result = job.awaitResult(5000);
		assertEquals(CVMBool.TRUE, RT.getIn(result, "exists"));
		// The result should contain a "keys" entry with the 4 op names
		ACell keys = RT.getIn(result, "keys");
		assertNotNull(keys, "should list child keys under v/ops/json");
	}
}
