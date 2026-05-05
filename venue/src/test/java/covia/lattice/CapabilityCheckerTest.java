package covia.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Fields;
import covia.grid.Job;
import covia.lattice.CapabilityChecker;
import covia.venue.Engine;
import covia.venue.RequestContext;

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
		assertNull(CapabilityChecker.check(null, "v/ops/covia/write",
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

	// ========== Full check — write access ==========

	@Test
	public void testAllowWriteToGrantedPath() {
		AVector<ACell> caps = caps("w/decisions", "crud/write");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
	}

	@Test
	public void testDenyWriteToUngrantedPath() {
		AVector<ACell> caps = caps("w/decisions", "crud/write");
		assertNotNull(CapabilityChecker.check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));
	}

	// ========== Full check — read access ==========

	@Test
	public void testAllowReadFromGrantedPath() {
		AVector<ACell> caps = caps("w/enrichments", "crud/read");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
	}

	@Test
	public void testDenyReadFromUngrantedPath() {
		AVector<ACell> caps = caps("w/enrichments", "crud/read");
		assertNotNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));
	}

	// ========== Path matching (delegates to Capability.covers) ==========

	@Test
	public void testExactPathMatch() {
		AVector<ACell> caps = caps("w/vendor-records", "crud/read");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
	}

	@Test
	public void testPathPrefixCoversChildren() {
		AVector<ACell> caps = caps("w/vendor-records", "crud/read");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
	}

	@Test
	public void testTrailingSlashCoversBase() {
		// "w/vendor-records/" should still cover "w/vendor-records"
		AVector<ACell> caps = caps("w/vendor-records/", "crud/read");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
	}

	@Test
	public void testTrailingSlashCoversChildren() {
		AVector<ACell> caps = caps("w/vendor-records/", "crud/read");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
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
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));

		// Denied — write to enrichments (only has read)
		assertNotNull(CapabilityChecker.check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
	}

	// ========== Ability hierarchy ==========

	@Test
	public void testWildcardCapsAllowsEverything() {
		AVector<ACell> caps = caps("", "*");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/grid/run",
			Maps.of(Strings.create("operation"), Strings.create("some-hash"))));
	}

	@Test
	public void testCrudPrefixCoversReadWriteDelete() {
		AVector<ACell> caps = caps("w/", "crud");
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/covia/delete",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	@Test
	public void testReadDoesNotCoverWrite() {
		AVector<ACell> caps = caps("w/", "crud/read");
		assertNotNull(CapabilityChecker.check(caps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	// ========== Agent operations ==========

	@Test
	public void testAgentRequestCap() {
		AVector<ACell> caps = caps("g/Alice", "agent/request");
		assertNull(CapabilityChecker.check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Alice"))));
		// Different agent — denied
		assertNotNull(CapabilityChecker.check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	@Test
	public void testAgentPrefixCoversAll() {
		AVector<ACell> caps = caps("g/", "agent");
		assertNull(CapabilityChecker.check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Alice"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/agent/message",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	// ========== Invoke ==========

	@Test
	public void testInvokeCap() {
		AVector<ACell> caps = caps("", "invoke");
		assertNull(CapabilityChecker.check(caps, "v/ops/grid/run",
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
		String msg = CapabilityChecker.check(caps, "v/ops/covia/write",
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
		String msg = CapabilityChecker.check(Vectors.empty(), "v/ops/covia/write",
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
		assertNull(CapabilityChecker.check(carolCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-2024-0891"))));
		// Carol can read anything in workspace
		assertNull(CapabilityChecker.check(carolCaps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-2024-0891"))));
		// Carol CANNOT write to vendor records (only has read on w/)
		assertNotNull(CapabilityChecker.check(carolCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
		// Carol CANNOT write enrichments
		assertNotNull(CapabilityChecker.check(carolCaps, "v/ops/covia/write",
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
		assertNull(CapabilityChecker.check(bobCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-2024-0891"))));
		// Bob can read vendor records
		assertNull(CapabilityChecker.check(bobCaps, "v/ops/covia/read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
		// Bob can list vendor records
		assertNull(CapabilityChecker.check(bobCaps, "v/ops/covia/list",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
		// Bob CANNOT write to decisions
		assertNotNull(CapabilityChecker.check(bobCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-2024-0891"))));
		// Bob CANNOT write vendor records
		assertNotNull(CapabilityChecker.check(bobCaps, "v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
	}

	// ========== RequestContext.caps enforcement at JobManager ==========

	@Test
	public void testJobManagerEnforcesContextCaps() {
		Engine engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);

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
	public void testInvokeInternalBypassesCapCheck() {
		// invokeInternal is the framework dispatch path — no cap check
		// applied. Trust is established by going through this entry point
		// rather than invokeOperation. Caps stay on the ctx (no stripping).
		Engine engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);

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

		// Framework path: same caps, same op, same input, no flag changes —
		// invokeInternal is trusted by call path, succeeds.
		ACell ok = engine.jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/forbidden/doc", Fields.VALUE, Strings.create("ok")), gated)
			.join();
		assertNotNull(ok);

		// Caps remain on the ctx — they didn't get stripped.
		assertEquals(caps, gated.getCaps());
	}

	@Test
	public void testJobManagerNullCapsUnrestricted() {
		Engine engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);

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
		assertNull(CapabilityChecker.check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("foo.txt"))));
		assertNotNull(CapabilityChecker.check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("data"),
				Strings.create("path"), Strings.create("foo.txt"))));
	}

	@Test
	public void testFilePerPathCaps() {
		AVector<ACell> caps = caps("file://scratch/agent-output/", "crud/write");
		assertNull(CapabilityChecker.check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("agent-output/run-123.json"))));
		assertNotNull(CapabilityChecker.check(caps, "v/ops/file/write",
			Maps.of(Strings.create("root"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("other/secret.txt"))));
	}

	@Test
	public void testFileReadOnlyCapsRejectWrite() {
		AVector<ACell> caps = caps("file://", "crud/read");
		assertNull(CapabilityChecker.check(caps, "v/ops/file/read",
			Maps.of(Strings.create("root"), Strings.create("data"),
				Strings.create("path"), Strings.create("anything"))));
		assertNotNull(CapabilityChecker.check(caps, "v/ops/file/write",
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
		assertNull(CapabilityChecker.check(caps, "v/ops/agent/create",
			Maps.of(Strings.create("agentId"), Strings.create("Carol"))));
	}

	@Test
	public void testAgentParentCoversCreate() {
		// "agent" ability covers every agent/* per the UCAN.md §3.2 hierarchy.
		AVector<ACell> caps = caps("g/", "agent");
		assertNull(CapabilityChecker.check(caps, "v/ops/agent/create",
			Maps.of(Strings.create("agentId"), Strings.create("Carol"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/agent/request",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
		assertNull(CapabilityChecker.check(caps, "v/ops/agent/message",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	@Test
	public void testAssetParentCoversStoreAndRead() {
		// "asset" covers asset/store and asset/read.
		AVector<ACell> caps = caps("", "asset");
		assertNull(CapabilityChecker.check(caps, "v/ops/asset/store", Maps.empty()));
		assertNull(CapabilityChecker.check(caps, "v/ops/asset/get",
			Maps.of(Strings.create("hash"), Strings.create("0xabc"))));
	}

	@Test
	public void testAssetReadDoesNotCoverStore() {
		AVector<ACell> caps = caps("", "asset/read");
		// Read allowed
		assertNull(CapabilityChecker.check(caps, "v/ops/asset/get",
			Maps.of(Strings.create("hash"), Strings.create("0xabc"))));
		// Store denied
		assertNotNull(CapabilityChecker.check(caps, "v/ops/asset/store", Maps.empty()));
	}

	@Test
	public void testDLFSPerDriveCaps() {
		AVector<ACell> caps = caps("dlfs://scratch/", "crud");
		// Operations on scratch drive allowed
		assertNull(CapabilityChecker.check(caps, "v/ops/dlfs/write",
			Maps.of(Strings.create("drive"), Strings.create("scratch"),
				Strings.create("path"), Strings.create("/foo.txt"),
				Strings.create("content"), Strings.create("hi"))));
		// Other drive denied
		assertNotNull(CapabilityChecker.check(caps, "v/ops/dlfs/write",
			Maps.of(Strings.create("drive"), Strings.create("private"),
				Strings.create("path"), Strings.create("/foo.txt"),
				Strings.create("content"), Strings.create("hi"))));
	}
}
