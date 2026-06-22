package covia.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.lattice.CapabilityChecker;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Tests for CapabilityChecker — agent capability enforcement.
 *
 * <p>Matching delegates to {@code Capability.covers()} from convex-core.
 * These tests verify the integration: operation-to-ability mapping,
 * resource extraction from tool inputs, and end-to-end check behaviour.</p>
 */
public class CapabilityCheckerTest {

	// ========== No caps = full access ==========

	@Test
	public void testNullCapsAllowsEverything() {
		assertNull(check(null, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	// ========== Ability mapping ==========

	@Test
	public void testOperationAbilityMapping() {
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/covia/read"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/covia/list"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/covia/slice"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("v/ops/covia/write"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("v/ops/covia/append"));
		assertEquals("crud/delete", CapabilityChecker.operationAbility("v/ops/covia/delete"));
		assertEquals("agent/request", CapabilityChecker.operationAbility("v/ops/agent/request"));
		assertEquals("agent/message", CapabilityChecker.operationAbility("v/ops/agent/message"));
		assertEquals("asset/store", CapabilityChecker.operationAbility("v/ops/asset/store"));
		assertEquals("asset/read", CapabilityChecker.operationAbility("v/ops/asset/get"));
		assertEquals("invoke", CapabilityChecker.operationAbility("v/ops/grid/run"));
		assertEquals("invoke", CapabilityChecker.operationAbility("some:unknown:op"));
	}

	// ========== Resource extraction ==========

	@Test
	public void testExtractResourceFromCoviaOp() {
		assertEquals("w/decisions/INV-123",
			CapabilityChecker.extractResource("v/ops/covia/write",
				Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
	}

	@Test
	public void testExtractResourceFromAgentOp() {
		assertEquals("g/Carol",
			CapabilityChecker.extractResource("v/ops/agent/request",
				Maps.of(Strings.create("agentId"), Strings.create("Carol"))));
	}

	@Test
	public void testExtractResourceNullForGridRun() {
		assertNull(CapabilityChecker.extractResource("v/ops/grid/run",
			Maps.of(Strings.create("operation"), Strings.create("some-hash"))));
	}

	// ========== Helpers ==========

	@SuppressWarnings("unchecked")
	private static AVector<ACell> caps(Object... capPairs) {
		AVector<ACell> result = Vectors.empty();
		for (int i = 0; i < capPairs.length; i += 2) {
			result = result.conj(Maps.of(
				Strings.create("with"), Strings.create((String) capPairs[i]),
				Strings.create("can"), Strings.create((String) capPairs[i + 1])));
		}
		return result;
	}

	// Owner under whom resources/caps are canonicalised for these unit checks.
	// Bare caps and bare resources are both prefixed with this DID, so the
	// existing bare↔bare match/no-match outcomes are preserved; DID-URL and
	// scheme-qualified (file://, dlfs://) resources are absolute and unchanged.
	private static final AString TEST_OWNER = Strings.create("did:key:zTestOwner");

	private static String check(AVector<ACell> caps, String operation, ACell input) {
		return CapabilityChecker.check(caps, operation, input, TEST_OWNER);
	}

	// ========== Full check — write access ==========

	@Test
	public void testAllowWriteToGrantedPath() {
		AVector<ACell> caps = caps("w/decisions", "crud/write");
		assertNull(check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
	}

	@Test
	public void testDenyWriteToUngrantedPath() {
		AVector<ACell> caps = caps("w/decisions", "crud/write");
		assertNotNull(check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));
	}

	// ========== Full check — read access ==========

	@Test
	public void testAllowReadFromGrantedPath() {
		AVector<ACell> caps = caps("w/enrichments", "crud/read");
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
	}

	@Test
	public void testDenyReadFromUngrantedPath() {
		AVector<ACell> caps = caps("w/enrichments", "crud/read");
		assertNotNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));
	}

	// ========== Path matching (delegates to Capability.covers) ==========

	@Test
	public void testExactPathMatch() {
		AVector<ACell> caps = caps("w/vendor-records", "crud/read");
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
	}

	@Test
	public void testPathPrefixCoversChildren() {
		AVector<ACell> caps = caps("w/vendor-records", "crud/read");
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
	}

	@Test
	public void testTrailingSlashCoversBase() {
		// "w/vendor-records/" should still cover "w/vendor-records"
		AVector<ACell> caps = caps("w/vendor-records/", "crud/read");
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
	}

	@Test
	public void testTrailingSlashCoversChildren() {
		AVector<ACell> caps = caps("w/vendor-records/", "crud/read");
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
	}

	// ========== Multiple caps ==========

	@Test
	public void testMultipleCaps() {
		AVector<ACell> caps = caps(
			"w/decisions", "crud/write",
			"w/enrichments", "crud/read",
			"w/vendor-records", "crud/read"
		);
		// Allowed
		assertNull(check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));

		// Denied — write to enrichments (only has read)
		assertNotNull(check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
	}

	// ========== Ability hierarchy ==========

	@Test
	public void testWildcardCapsAllowsEverything() {
		AVector<ACell> caps = caps("", "*");
		assertNull(check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(check(caps, "v/ops/grid/run",
			Maps.of(Strings.create("operation"), Strings.create("some-hash"))));
	}

	@Test
	public void testCrudPrefixCoversReadWriteDelete() {
		AVector<ACell> caps = caps("w/", "crud");
		assertNull(check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(check(caps, "v/ops/covia/delete",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	@Test
	public void testReadDoesNotCoverWrite() {
		AVector<ACell> caps = caps("w/", "crud/read");
		assertNotNull(check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	// ========== Agent operations ==========

	@Test
	public void testAgentRequestCap() {
		AVector<ACell> caps = caps("g/Alice", "agent/request");
		assertNull(check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Alice"))));
		// Different agent — denied
		assertNotNull(check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	@Test
	public void testAgentPrefixCoversAll() {
		AVector<ACell> caps = caps("g/", "agent");
		assertNull(check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Alice"))));
		assertNull(check(caps, "v/ops/agent/message",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	// ========== Invoke ==========

	@Test
	public void testInvokeCap() {
		AVector<ACell> caps = caps("", "invoke");
		assertNull(check(caps, "v/ops/grid/run",
			Maps.of(Strings.create("operation"), Strings.create("some-hash"))));
	}

	// ========== Denial message format ==========

	@Test
	public void testDenialMessageIncludesAvailableCaps() {
		// LLMs that hit a denial historically retried the same call because
		// the error didn't tell them what they CAN do. The denial message
		// must include the agent's capability set so the LLM has actionable
		// guidance, not just "denied".
		AVector<ACell> caps = caps(
			"w/decisions/", "crud",
			"w/", "crud/read");
		String msg = check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/audits/INV-123")));
		assertNotNull(msg);
		assertTrue(msg.contains("Capability denied"), "Should be flagged as denial: " + msg);
		assertTrue(msg.contains("w/audits/INV-123"), "Should name the resource attempted: " + msg);
		assertTrue(msg.contains("crud/write"), "Should name the ability required: " + msg);
		assertTrue(msg.contains("Your capabilities are"),
			"Denial must include the agent's actual capabilities: " + msg);
		assertTrue(msg.contains("crud on w/decisions/"),
			"Should list each cap with ability and resource: " + msg);
		assertTrue(msg.contains("crud/read on w/"),
			"Should list each cap with ability and resource: " + msg);
		assertTrue(msg.contains("Retrying"),
			"Should tell the LLM not to loop on the same call: " + msg);
	}

	@Test
	public void testDenialMessageWithEmptyCaps() {
		// Empty caps array = deny-all. Message should still be sensible
		// rather than rendering as nonsense.
		String msg = check(Vectors.empty(), "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything")));
		assertNotNull(msg);
		assertTrue(msg.contains("Your capabilities are: (none)"),
			"Empty caps should be rendered as (none): " + msg);
	}

	// ========== AP Demo scenario ==========

	@Test
	public void testCarolAPCaps() {
		AVector<ACell> carolCaps = caps(
			"w/decisions", "crud/write",
			"w/", "crud/read"
		);
		// Carol can write decisions
		assertNull(check(carolCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-2024-0891"))));
		// Carol can read anything in workspace
		assertNull(check(carolCaps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-2024-0891"))));
		// Carol CANNOT write to vendor records (only has read on w/)
		assertNotNull(check(carolCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
		// Carol CANNOT write enrichments
		assertNotNull(check(carolCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-2024-0891"))));
	}

	@Test
	public void testBobAPCaps() {
		AVector<ACell> bobCaps = caps(
			"w/enrichments", "crud/write",
			"w/vendor-records", "crud/read",
			"w/purchase-orders", "crud/read",
			"w/invoices", "crud/read"
		);
		// Bob can write enrichments
		assertNull(check(bobCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-2024-0891"))));
		// Bob can read vendor records
		assertNull(check(bobCaps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
		// Bob can list vendor records
		assertNull(check(bobCaps, "v/ops/covia/list",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
		// Bob CANNOT write to decisions
		assertNotNull(check(bobCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-2024-0891"))));
		// Bob CANNOT write vendor records
		assertNotNull(check(bobCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
	}

	// ========== RequestContext.caps enforcement at JobManager ==========

	@Test
	public void testJobManagerEnforcesContextCaps() {
		Engine engine = TestEngine.ENGINE;

		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create("w/allowed/"), Capability.CRUD_WRITE),
			Capability.create(Strings.create("w/"), Capability.CRUD_READ)
		);

		RequestContext ctx = RequestContext.of(
			convex.auth.ucan.UCAN.toDIDKey(convex.core.crypto.AKeyPair.generate().getAccountKey())
		).withCaps(caps);

		// Write to allowed path — should succeed
		Job writeOk = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/allowed/doc", Fields.VALUE, Strings.create("ok")), ctx);
		assertNotNull(writeOk.awaitResult(5000), "Write to allowed path should succeed");

		// Read from anywhere — should succeed (crud/read on w/)
		Job readOk = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "w/allowed/doc"), ctx);
		assertNotNull(readOk.awaitResult(5000), "Read should succeed");

		// Write to disallowed path — should fail
		assertThrows(Exception.class, () -> {
			engine.jobs().invokeOperation("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/forbidden/doc", Fields.VALUE, Strings.create("bad")), ctx);
		}, "Write to disallowed path should throw");
	}

	@Test
	public void testInvokeInternalEnforcesContextCeiling() {
		// Trust is a property of the context's authority, not the call path.
		// invokeInternal differs from invokeOperation only in Job creation — it
		// enforces whatever ceiling the context carries. A write outside the
		// ceiling is denied on BOTH paths; "framework-trusted" is expressed by
		// an unrestricted (null-caps) context, not by choosing invokeInternal.
		Engine engine = TestEngine.ENGINE;

		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create("w/allowed/"), Capability.CRUD_WRITE)
		);
		RequestContext gated = RequestContext.of(
			convex.auth.ucan.UCAN.toDIDKey(convex.core.crypto.AKeyPair.generate().getAccountKey())
		).withCaps(caps);

		// User-facing path: capped ctx writing outside its scope — denied.
		assertThrows(Exception.class, () ->
			engine.jobs().invokeOperation("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/forbidden/doc", Fields.VALUE, Strings.create("nope")), gated));

		// Internal path: same ceiling, same op — also denied. No call-path bypass.
		assertThrows(Exception.class, () ->
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/forbidden/doc", Fields.VALUE, Strings.create("nope")), gated)
				.join(),
			"invokeInternal must enforce the context ceiling");

		// Within the ceiling, the internal write succeeds.
		ACell ok = engine.jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/allowed/doc", Fields.VALUE, Strings.create("ok")), gated)
			.join();
		assertNotNull(ok);

		// The ceiling stays on the ctx — enforcement reads it, never strips it.
		assertEquals(caps, gated.getCaps());
	}

	@Test
	public void testJobManagerNullCapsUnrestricted() {
		Engine engine = TestEngine.ENGINE;

		RequestContext ctx = RequestContext.of(
			convex.auth.ucan.UCAN.toDIDKey(convex.core.crypto.AKeyPair.generate().getAccountKey())
		);
		// No caps = unrestricted — write anywhere
		Job writeOk = engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/anything", Fields.VALUE, Strings.create("fine")), ctx);
		assertNotNull(writeOk.awaitResult(5000), "No caps should mean unrestricted access");
	}

	// ========== File adapter caps ==========

	@Test
	public void testFileOperationAbilityMapping() {
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/file/read"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/file/list"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/file/stat"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/file/roots"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("v/ops/file/write"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("v/ops/file/append"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("v/ops/file/mkdir"));
		assertEquals("crud/delete", CapabilityChecker.operationAbility("v/ops/file/delete"));
	}

	@Test
	public void testFileResourceFormat() {
		// Resource is the URI "file://<root>/<path>". Leading slashes on path
		// are stripped before composing.
		assertEquals("file://scratch/notes.txt",
			CapabilityChecker.extractResource("v/ops/file/read",
				Maps.of(Strings.create("root"), Strings.create("scratch"),
					Strings.create("path"), Strings.create("notes.txt"))));
		assertEquals("file://scratch/notes.txt",
			CapabilityChecker.extractResource("v/ops/file/read",
				Maps.of(Strings.create("root"), Strings.create("scratch"),
					Strings.create("path"), Strings.create("/notes.txt"))));
		// No path → root authority + empty path
		assertEquals("file://scratch/",
			CapabilityChecker.extractResource("v/ops/file/list",
				Maps.of(Strings.create("root"), Strings.create("scratch"))));
		// No root (file:roots etc.) → namespace root
		assertEquals("file://",
			CapabilityChecker.extractResource("v/ops/file/roots", Maps.empty()));
	}

	@Test
	public void testFilePerRootCaps() {
		// Cap scoped to file://scratch/ — agent can write within scratch but
		// not other roots.
		AVector<ACell> caps = caps("file://scratch/", "crud/write");
		assertNull(check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("foo.txt"))));
		assertNotNull(check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("data"),
				Strings.create("path"), Strings.create("foo.txt"))));
	}

	@Test
	public void testFilePerPathCaps() {
		AVector<ACell> caps = caps("file://scratch/agent-output/", "crud/write");
		assertNull(check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("agent-output/run-123.json"))));
		assertNotNull(check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("other/secret.txt"))));
	}

	@Test
	public void testFileReadOnlyCapsRejectWrite() {
		AVector<ACell> caps = caps("file://", "crud/read");
		assertNull(check(caps, "v/ops/file/read",
			Maps.of(Strings.create("root"), Strings.create("data"),
				Strings.create("path"), Strings.create("anything"))));
		assertNotNull(check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("data"),
				Strings.create("path"), Strings.create("anything"))));
	}

	// ========== DLFS adapter caps ==========

	@Test
	public void testDLFSOperationAbilityMapping() {
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/dlfs/read"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("v/ops/dlfs/list"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("v/ops/dlfs/write"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("v/ops/dlfs/create-drive"));
		assertEquals("crud/delete", CapabilityChecker.operationAbility("v/ops/dlfs/delete-drive"));
	}

	@Test
	public void testDLFSResourceFormat() {
		assertEquals("dlfs://health-vault/medications",
			CapabilityChecker.extractResource("v/ops/dlfs/list",
				Maps.of(Strings.create("drive"), Strings.create("health-vault"),
					Strings.create("path"), Strings.create("/medications"))));
		assertEquals("dlfs://health-vault/",
			CapabilityChecker.extractResource("v/ops/dlfs/create-drive",
				Maps.of(Strings.create("name"), Strings.create("health-vault"))));
		assertEquals("dlfs://",
			CapabilityChecker.extractResource("v/ops/dlfs/list-drives", Maps.empty()));
	}

	// ========== Agent / asset abilities (documented in UCAN.md §3.2) ==========

	@Test
	public void testAgentCreateAbility() {
		AVector<ACell> caps = caps("g/Carol", "agent/create");
		assertNull(check(caps, "v/ops/agent/create",
			Maps.of(Strings.create("agentId"), Strings.create("Carol"))));
	}

	@Test
	public void testAgentParentCoversCreate() {
		// "agent" ability covers every agent/* per the UCAN.md §3.2 hierarchy.
		AVector<ACell> caps = caps("g/", "agent");
		assertNull(check(caps, "v/ops/agent/create",
			Maps.of(Strings.create("agentId"), Strings.create("Carol"))));
		assertNull(check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
		assertNull(check(caps, "v/ops/agent/message",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	@Test
	public void testAssetParentCoversStoreAndRead() {
		// "asset" covers asset/store and asset/read.
		AVector<ACell> caps = caps("", "asset");
		assertNull(check(caps, "v/ops/asset/store", Maps.empty()));
		assertNull(check(caps, "v/ops/asset/get",
			Maps.of(Strings.create("hash"), Strings.create("0xabc"))));
	}

	@Test
	public void testAssetReadDoesNotCoverStore() {
		AVector<ACell> caps = caps("", "asset/read");
		// Read allowed
		assertNull(check(caps, "v/ops/asset/get",
			Maps.of(Strings.create("hash"), Strings.create("0xabc"))));
		// Store denied
		assertNotNull(check(caps, "v/ops/asset/store", Maps.empty()));
	}

	@Test
	public void testDLFSPerDriveCaps() {
		AVector<ACell> caps = caps("dlfs://scratch/", "crud");
		// Operations on scratch drive allowed
		assertNull(check(caps, "v/ops/dlfs/write",
			Maps.of(Strings.create("drive"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("/foo.txt"),
				Strings.create("content"), Strings.create("hi"))));
		// Other drive denied
		assertNotNull(check(caps, "v/ops/dlfs/write",
			Maps.of(Strings.create("drive"), Strings.create("private"),
				Strings.create("path"), Strings.create("/foo.txt"),
				Strings.create("content"), Strings.create("hi"))));
	}

	// ==================================================================
	// allows(caps, resource, ability, owner) — the pinned-resource primitive
	// the executing adapter calls. The adapter supplies the EXACT resource and
	// ability, so the enforced cap can't drift from the implementation. These
	// are deterministic + adversarial unit checks of the covia-side logic
	// (null/empty ceiling, malformed grants, cross-user isolation, the
	// read-only profile); the underlying prefix/hierarchy matching is
	// convex-core's Capability.covers, exercised through here.
	// ==================================================================

	private static String allows(AVector<ACell> caps, String resource, String ability) {
		return CapabilityChecker.allows(caps, resource, ability, TEST_OWNER);
	}

	@Test
	public void testAllowsNullCeilingIsUnrestricted() {
		// null ceiling = full authority (internal/unrestricted callers).
		assertNull(allows(null, "w/anything", "crud/write"));
		assertNull(allows(null, "did:key:zOther/w/x", "secret/write"));
		assertNull(allows(null, null, null));
	}

	@Test
	public void testAllowsEmptyCeilingDeniesEverything() {
		// Empty ceiling grants NOTHING — the crucial distinction from null.
		// (A bug that treated empty like null would silently grant full access.)
		assertNotNull(allows(Vectors.empty(), "w/x", "crud/read"));
		assertNotNull(allows(Vectors.empty(), "w/x", "crud/write"));
		assertNotNull(allows(Vectors.empty(), "v/test/ops/echo", "invoke"));
	}

	@Test
	public void testAllowsExactAndPrefix() {
		AVector<ACell> ceiling = caps("w/notes", "crud/write");
		assertNull(allows(ceiling, "w/notes", "crud/write"));          // exact
		assertNull(allows(ceiling, "w/notes/2026/x", "crud/write"));   // child (prefix)
		assertNotNull(allows(ceiling, "w/other", "crud/write"));       // sibling — denied
	}

	@Test
	public void testAllowsAbilityMismatchAndHierarchy() {
		// read grant does not cover write...
		assertNotNull(allows(caps("w/", "crud/read"), "w/x", "crud/write"));
		// ...but the parent ability covers its children, and * covers all.
		assertNull(allows(caps("w/", "crud"), "w/x", "crud/write"));
		assertNull(allows(caps("w/", "crud"), "w/x", "crud/delete"));
		assertNull(allows(caps("", "*"), "anything", "secret/write"));
	}

	@Test
	public void testAllowsAbilityBoundaryNotNaivePrefix() {
		// Ability matching must respect the "/" boundary: "cru" must NOT cover
		// "crud/read" (it is a raw string prefix but not a UCAN ability prefix).
		assertNotNull(allows(caps("w/", "cru"), "w/x", "crud/read"));
		// And a sibling ability under the same parent is not covered.
		assertNotNull(allows(caps("w/", "crud/read"), "w/x", "crud/delete"));
	}

	@Test
	public void testAllowsCrossUserResourceDenied() {
		// An owner-scoped read grant must not reach another identity's resource.
		// This is the isolation property: a restricted (e.g. public) caller
		// cannot read across users even with a read grant.
		AVector<ACell> ceiling = Vectors.of(Capability.create(TEST_OWNER, Capability.CRUD_READ));
		assertNull(allows(ceiling, "w/notes", "crud/read"));                       // own
		assertNotNull(allows(ceiling, "did:key:zOther/w/notes", "crud/read"));     // cross-user → denied
	}

	@Test
	public void testAllowsMultipleCapsAnyMatchWins() {
		AVector<ACell> ceiling = caps(
			"w/a", "crud/read",
			"w/b", "crud/write");
		assertNull(allows(ceiling, "w/b/x", "crud/write"));   // second grant matches
		assertNull(allows(ceiling, "w/a/x", "crud/read"));    // first grant matches
		assertNotNull(allows(ceiling, "w/a/x", "crud/write")); // neither grants write on a
	}

	@Test
	public void testAllowsNonMapAndNonMatchingGrantsAreRobust() {
		// A non-map entry must be skipped without crashing, and well-formed but
		// non-matching grants must not accidentally allow the request.
		AVector<ACell> noMatch = Vectors.of(
			Strings.create("not-a-map"),                                          // skipped, no crash
			Capability.create(Strings.create("w/different"), Capability.CRUD_READ), // wrong resource
			Capability.create(Strings.create("g/X"), Strings.create("agent/create"))); // wrong ability+resource
		assertNotNull(allows(noMatch, "w/x", "crud/read"), "no grant matches → denied");

		// Adding a matching grant flips it to allowed (the non-map is still ignored).
		AVector<ACell> plusMatch = noMatch.conj(
			Capability.create(Strings.create("w/x"), Capability.CRUD_READ));
		assertNull(allows(plusMatch, "w/x", "crud/read"));
	}

	@Test
	public void testAllowsGrantWithoutResourceIsBroadNotInert() {
		// SECURITY FOOT-GUN, asserted explicitly: a grant with an ability but no
		// `with` is NOT a no-op — an empty/absent `with` covers ANY resource for
		// that ability, including across users. Profiles must always scope `with`.
		AVector<ACell> noWith = Vectors.of(
			Maps.of(Capability.CAN, Strings.create("crud/read")));   // ability, no `with`
		assertNull(allows(noWith, "w/anything", "crud/read"));        // own — granted
		assertNull(allows(noWith, "did:key:zOther/w/x", "crud/read")); // cross-user — also granted!
		assertNotNull(allows(noWith, "w/x", "crud/write"));           // but only that ability
	}

	@Test
	public void testAllowsEmptyWithCoversAnyResource() {
		// An empty `with` grant covers any resource for that ability (used for
		// content-addressed assets, which are not owner-scoped paths).
		AVector<ACell> ceiling = Vectors.of(
			Capability.create(Strings.create(""), Strings.create("asset/read")));
		assertNull(allows(ceiling, "0xdeadbeef", "asset/read"));
		assertNull(allows(ceiling, "did:key:zOther/a/0xabc", "asset/read"));
		assertNotNull(allows(ceiling, "0xdeadbeef", "asset/store")); // wrong ability
	}

	@Test
	public void testReadOnlyProfileIsTheSecurityProperty() {
		// The default public read-only profile: read own/venue paths + read
		// assets. This is the concrete #148 fix at the helper level.
		AVector<ACell> profile = Vectors.of(
			Capability.create(TEST_OWNER, Capability.CRUD_READ),                 // own + venue reads
			Capability.create(Strings.create(""), Strings.create("asset/read"))); // asset reads

		// Reads allowed
		assertNull(allows(profile, "w/reviews/x", "crud/read"));
		assertNull(allows(profile, "v/ops/covia/read", "crud/read"));  // venue path (owner-prefixed) covered
		assertNull(allows(profile, "0xhash", "asset/read"));

		// Writes denied
		assertNotNull(allows(profile, "w/reviews/x", "crud/write"));
		assertNotNull(allows(profile, "w/reviews/x", "crud/delete"));
		// secret:set denied (the #148 case)
		assertNotNull(allows(profile, "s/OPENAI_API_KEY", "secret/write"));
		// arbitrary op invocation denied (read-only)
		assertNotNull(allows(profile, "v/test/ops/echo", "invoke"));
		assertNotNull(allows(profile, "g/Bob", "agent/create"));
	}

	@Test
	public void testAllowsDenialMessageIsActionable() {
		String msg = allows(caps("w/notes", "crud/read"), "w/secrets", "crud/write");
		assertNotNull(msg);
		assertTrue(msg.contains("Capability denied"), msg);
		assertTrue(msg.contains("crud/write"), "names the required ability: " + msg);
		assertTrue(msg.contains("Your capabilities are"), "lists what is granted: " + msg);
	}

	@Test
	public void testAllowsMatchesCheckForSameOp() {
		// Parity: the pinned allows(path, crud/write) and the name-keyed
		// check(covia:write, {path}) share the match loop and agree.
		AVector<ACell> ceiling = caps("w/decisions", "crud/write");
		ACell input = Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-1"));
		boolean allowDirect = allows(ceiling, "w/decisions/INV-1", "crud/write") == null;
		boolean allowViaCheck = check(ceiling, "v/ops/covia/write", input) == null;
		assertEquals(allowViaCheck, allowDirect);
		assertTrue(allowDirect);
	}

	// ==================================================================
	// readOnlyCeiling + RequestContext.requireCapability — the public
	// read-only default and the adapter-facing enforcement primitive.
	// ==================================================================

	@Test
	public void testReadOnlyCeilingGrantsOnlyReads() {
		AString did = Strings.create("did:key:zPublic");
		AVector<ACell> ceiling = CapabilityChecker.readOnlyCeiling(did);
		// Reads: own/venue lattice + content-addressed assets
		assertNull(CapabilityChecker.allows(ceiling, "w/x", "crud/read", did));
		assertNull(CapabilityChecker.allows(ceiling, "v/ops/covia/read", "crud/read", did));
		assertNull(CapabilityChecker.allows(ceiling, "0xhash", "asset/read", did));
		// Every mutating ability denied
		assertNotNull(CapabilityChecker.allows(ceiling, "w/x", "crud/write", did));
		assertNotNull(CapabilityChecker.allows(ceiling, "w/x", "crud/delete", did));
		assertNotNull(CapabilityChecker.allows(ceiling, "s/KEY", "secret/write", did));
		assertNotNull(CapabilityChecker.allows(ceiling, "g/Bob", "agent/create", did));
		assertNotNull(CapabilityChecker.allows(ceiling, "0xh", "asset/store", did));
		assertNotNull(CapabilityChecker.allows(ceiling, "v/test/ops/echo", "invoke", did));
		// And no cross-user read
		assertNotNull(CapabilityChecker.allows(ceiling, "did:key:zOther/w/x", "crud/read", did));
	}

	@Test
	public void testRequireCapabilityEnforcesReadOnlyCeiling() {
		AString did = Strings.create("did:key:zPublic");
		RequestContext ctx = RequestContext.of(did).withCaps(CapabilityChecker.readOnlyCeiling(did));
		assertDoesNotThrow(() -> ctx.requireCapability("w/notes", "crud/read"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("w/notes", "crud/write"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("w/notes", "crud/delete"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("s/KEY", "secret/write"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("v/test/ops/echo", "invoke"));
	}

	@Test
	public void testRequireCapabilityNullCeilingIsNoOp() {
		// Authenticated / internal callers carry no ceiling → unrestricted.
		RequestContext ctx = RequestContext.of(Strings.create("did:key:zAlice"));
		assertDoesNotThrow(() -> ctx.requireCapability("w/anything", "crud/write"));
		assertDoesNotThrow(() -> ctx.requireCapability("s/KEY", "secret/write"));
	}

	@Test
	public void testAllowsAStringAndStringOverloadsAgree() {
		AString did = Strings.create("did:key:zPublic");
		AVector<ACell> ceiling = CapabilityChecker.readOnlyCeiling(did);
		// The AString-native primary and the String convenience overload must
		// produce identical verdicts and identical denial messages.
		assertEquals(
			CapabilityChecker.allows(ceiling, "w/x", "crud/write", did),
			CapabilityChecker.allows(ceiling, Strings.create("w/x"), Capability.CRUD_WRITE, did));
		assertNull(CapabilityChecker.allows(ceiling, Strings.create("w/x"), Capability.CRUD_READ, did));
		assertNotNull(CapabilityChecker.allows(ceiling, Strings.create("w/x"), Capability.CRUD_WRITE, did));
	}

	@Test
	public void testRequireCapabilityAStringOverloadEnforces() {
		AString did = Strings.create("did:key:zPublic");
		RequestContext ctx = RequestContext.of(did).withCaps(CapabilityChecker.readOnlyCeiling(did));
		assertDoesNotThrow(() -> ctx.requireCapability(Strings.create("w/x"), Capability.CRUD_READ));
		assertThrows(AuthException.class,
			() -> ctx.requireCapability(Strings.create("w/x"), Capability.CRUD_WRITE));
	}

	@Test
	public void testReadOnlyCeilingDeniesAllVenueMutations() {
		AString did = Strings.create("did:key:zPublic");
		AVector<ACell> ceiling = CapabilityChecker.readOnlyCeiling(did);
		// Drift guard: every operation that mutates a venue resource must map to a
		// mutating ability the read-only ceiling withholds. A new mutating op that
		// forgets to map falls through operationAbility() to the generic "invoke"
		// and would pass IF invoke were ever granted — this test fails first.
		String[] mutations = {
			"covia:write", "covia:append", "covia:delete",
			"asset:store",
			"agent:create", "agent:fork", "agent:update", "agent:delete",
			"agent:suspend", "agent:resume", "agent:cancelTask",
			"file:write", "file:append", "file:mkdir", "file:delete",
			"dlfs:write", "dlfs:append", "dlfs:mkdir", "dlfs:delete",
			"dlfs:createDrive", "dlfs:deleteDrive",
			"vault:write", "vault:mkdir", "vault:delete",
			"secret:set",
		};
		for (String op : mutations) {
			assertNotNull(CapabilityChecker.check(ceiling, op, Maps.empty(), did),
				"read-only ceiling must deny mutating op: " + op);
		}
		// A read in the owner's own namespace remains allowed.
		assertNull(CapabilityChecker.check(ceiling, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/x")), did),
			"read-only ceiling must allow owner-namespace reads");
	}

	// ==================================================================
	// Hardenings A1–A3 (Convex #585 mitigation + self-ceiling hygiene +
	// explicit cross-user write rejection).
	// ==================================================================

	@Test
	public void testResourceMatchingRespectsPathBoundary() {
		// #585 regression: a path grant covers itself + descendants at a '/'
		// boundary, NOT siblings that merely share a string prefix.
		AString did = Strings.create("did:key:zBoundary");
		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create(did + "/w/notes"), Capability.CRUD_READ));
		assertNull(CapabilityChecker.allows(caps, "w/notes", "crud/read", did));            // exact
		assertNull(CapabilityChecker.allows(caps, "w/notes/child", "crud/read", did));      // descendant
		assertNotNull(CapabilityChecker.allows(caps, "w/notesSECRET", "crud/read", did));   // sibling — DENY
		assertNotNull(CapabilityChecker.allows(caps, "w/notes-private", "crud/read", did));  // sibling — DENY

		AVector<ACell> capsSlash = Vectors.of(
			Capability.create(Strings.create(did + "/w/notes/"), Capability.CRUD_READ));
		assertNull(CapabilityChecker.allows(capsSlash, "w/notes/child", "crud/read", did)); // children
		assertNotNull(CapabilityChecker.allows(capsSlash, "w/notesX", "crud/read", did));    // sibling — DENY
	}

	@Test
	public void testResourceMatchesUnit() {
		assertTrue(CapabilityChecker.resourceMatches(Strings.create("w/notes"), Strings.create("w/notes")));
		assertTrue(CapabilityChecker.resourceMatches(Strings.create("w/notes"), Strings.create("w/notes/child")));
		assertFalse(CapabilityChecker.resourceMatches(Strings.create("w/notes"), Strings.create("w/notesSECRET")));
		assertTrue(CapabilityChecker.resourceMatches(Strings.create("w/notes/"), Strings.create("w/notes"))); // trailing-slash parent
		assertTrue(CapabilityChecker.resourceMatches(Strings.create(""), Strings.create("anything")));         // empty = wildcard
		assertTrue(CapabilityChecker.resourceMatches(null, Strings.create("anything")));                       // null = wildcard
	}

	@Test
	public void testSelfCapabilitiesStripsEmptyWithCaps() {
		convex.core.crypto.AKeyPair kp = convex.core.crypto.AKeyPair.generate();
		AString did = convex.auth.ucan.UCAN.toDIDKey(kp.getAccountKey());
		long now = System.currentTimeMillis() / 1000;
		// Self-token (iss == aud == caller) granting a scoped read AND an
		// empty-`with` wildcard write. The wildcard must not survive into the
		// derived self-ceiling — it would broaden, not narrow.
		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create(did + "/w/notes"), Capability.CRUD_READ),
			Capability.create(Strings.create(""), Capability.CRUD_WRITE));
		convex.auth.ucan.UCAN token = convex.auth.ucan.UCAN.create(
			kp, kp.getAccountKey(), now + 3600, caps, Vectors.empty());
		AVector<ACell> ceiling = CapabilityChecker.selfCapabilities(
			Vectors.of(token.toMap()), did, did, now);

		assertNotNull(ceiling);
		assertNull(CapabilityChecker.allows(ceiling, "w/notes", "crud/read", did));        // scoped read survives
		assertNotNull(CapabilityChecker.allows(ceiling, "w/anything", "crud/write", did)); // empty-with wildcard dropped
	}

	@Test
	public void testCrossUserDIDWritePathRejected() {
		Engine engine = TestEngine.ENGINE;
		AString did = convex.auth.ucan.UCAN.toDIDKey(convex.core.crypto.AKeyPair.generate().getAccountKey());
		RequestContext ctx = RequestContext.of(did); // authenticated, null ceiling
		Throwable ex = assertThrows(Throwable.class, () ->
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, "did:key:zOtherUser/w/x", Fields.VALUE, Strings.create("v")), ctx).join());
		StringBuilder msg = new StringBuilder();
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t.getMessage() != null) msg.append(t.getMessage()).append(" | ");
		}
		assertTrue(msg.toString().contains("not supported"),
			"cross-user DID-URL write must be explicitly rejected, got: " + msg);
	}

	@Test
	public void testReadOnlyCeilingStopsMutationsEndToEnd() {
		Engine engine = TestEngine.ENGINE;
		AString did = convex.auth.ucan.UCAN.toDIDKey(
			convex.core.crypto.AKeyPair.generate().getAccountKey());
		RequestContext ctx = RequestContext.of(did).withCaps(CapabilityChecker.readOnlyCeiling(did));

		// Read under a read-only ceiling is allowed (absent path → exists:false).
		Job read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "w/x"), ctx);
		assertNotNull(read.awaitResult(5000), "read is allowed under a read-only ceiling");

		// Mutations are denied.
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/x", Fields.VALUE, Strings.create("v")), ctx),
			"write must be denied under a read-only ceiling");
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, "w/x"), ctx),
			"delete must be denied under a read-only ceiling");
	}
}
