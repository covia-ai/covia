package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;

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
		assertNull(CapabilityChecker.check(null, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	// ========== Ability mapping ==========

	@Test
	public void testOperationAbilityMapping() {
		assertEquals("crud/read", CapabilityChecker.operationAbility("covia:read"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("covia:list"));
		assertEquals("crud/read", CapabilityChecker.operationAbility("covia:slice"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("covia:write"));
		assertEquals("crud/write", CapabilityChecker.operationAbility("covia:append"));
		assertEquals("crud/delete", CapabilityChecker.operationAbility("covia:delete"));
		assertEquals("agent/request", CapabilityChecker.operationAbility("agent:request"));
		assertEquals("agent/message", CapabilityChecker.operationAbility("agent:message"));
		assertEquals("asset/store", CapabilityChecker.operationAbility("asset:store"));
		assertEquals("asset/read", CapabilityChecker.operationAbility("asset:get"));
		assertEquals("invoke", CapabilityChecker.operationAbility("grid:run"));
		assertEquals("invoke", CapabilityChecker.operationAbility("some:unknown:op"));
	}

	// ========== Resource extraction ==========

	@Test
	public void testExtractResourceFromCoviaOp() {
		assertEquals("w/decisions/INV-123",
			CapabilityChecker.extractResource("covia:write",
				Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
	}

	@Test
	public void testExtractResourceFromAgentOp() {
		assertEquals("g/Carol",
			CapabilityChecker.extractResource("agent:request",
				Maps.of(Strings.create("agentId"), Strings.create("Carol"))));
	}

	@Test
	public void testExtractResourceNullForGridRun() {
		assertNull(CapabilityChecker.extractResource("grid:run",
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
		assertNull(CapabilityChecker.check(caps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
	}

	@Test
	public void testDenyWriteToUngrantedPath() {
		AVector<ACell> caps = caps("w/decisions", "crud/write");
		assertNotNull(CapabilityChecker.check(caps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));
	}

	// ========== Full check — read access ==========

	@Test
	public void testAllowReadFromGrantedPath() {
		AVector<ACell> caps = caps("w/enrichments", "crud/read");
		assertNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
	}

	@Test
	public void testDenyReadFromUngrantedPath() {
		AVector<ACell> caps = caps("w/enrichments", "crud/read");
		assertNotNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));
	}

	// ========== Path matching (delegates to Capability.covers) ==========

	@Test
	public void testExactPathMatch() {
		AVector<ACell> caps = caps("w/vendor-records", "crud/read");
		assertNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
	}

	@Test
	public void testPathPrefixCoversChildren() {
		AVector<ACell> caps = caps("w/vendor-records", "crud/read");
		assertNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
	}

	@Test
	public void testTrailingSlashCoversBase() {
		// "w/vendor-records/" should still cover "w/vendor-records"
		AVector<ACell> caps = caps("w/vendor-records/", "crud/read");
		assertNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
	}

	@Test
	public void testTrailingSlashCoversChildren() {
		AVector<ACell> caps = caps("w/vendor-records/", "crud/read");
		assertNull(CapabilityChecker.check(caps, "covia:read",
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
		assertNull(CapabilityChecker.check(caps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-123"))));
		assertNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
		assertNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme"))));

		// Denied — write to enrichments (only has read)
		assertNotNull(CapabilityChecker.check(caps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-123"))));
	}

	// ========== Ability hierarchy ==========

	@Test
	public void testWildcardCapsAllowsEverything() {
		AVector<ACell> caps = caps("", "*");
		assertNull(CapabilityChecker.check(caps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(CapabilityChecker.check(caps, "grid:run",
			Maps.of(Strings.create("operation"), Strings.create("some-hash"))));
	}

	@Test
	public void testCrudPrefixCoversReadWriteDelete() {
		AVector<ACell> caps = caps("w/", "crud");
		assertNull(CapabilityChecker.check(caps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(CapabilityChecker.check(caps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
		assertNull(CapabilityChecker.check(caps, "covia:delete",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	@Test
	public void testReadDoesNotCoverWrite() {
		AVector<ACell> caps = caps("w/", "crud/read");
		assertNotNull(CapabilityChecker.check(caps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/anything"))));
	}

	// ========== Agent operations ==========

	@Test
	public void testAgentRequestCap() {
		AVector<ACell> caps = caps("g/Alice", "agent/request");
		assertNull(CapabilityChecker.check(caps, "agent:request",
			Maps.of(Strings.create("agentId"), Strings.create("Alice"))));
		// Different agent — denied
		assertNotNull(CapabilityChecker.check(caps, "agent:request",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	@Test
	public void testAgentPrefixCoversAll() {
		AVector<ACell> caps = caps("g/", "agent");
		assertNull(CapabilityChecker.check(caps, "agent:request",
			Maps.of(Strings.create("agentId"), Strings.create("Alice"))));
		assertNull(CapabilityChecker.check(caps, "agent:message",
			Maps.of(Strings.create("agentId"), Strings.create("Bob"))));
	}

	// ========== Invoke ==========

	@Test
	public void testInvokeCap() {
		AVector<ACell> caps = caps("", "invoke");
		assertNull(CapabilityChecker.check(caps, "grid:run",
			Maps.of(Strings.create("operation"), Strings.create("some-hash"))));
	}

	// ========== AP Demo scenario ==========

	@Test
	public void testCarolAPCaps() {
		AVector<ACell> carolCaps = caps(
			"w/decisions", "crud/write",
			"w/", "crud/read"
		);
		// Carol can write decisions
		assertNull(CapabilityChecker.check(carolCaps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-2024-0891"))));
		// Carol can read anything in workspace
		assertNull(CapabilityChecker.check(carolCaps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-2024-0891"))));
		// Carol CANNOT write to vendor records (only has read on w/)
		assertNotNull(CapabilityChecker.check(carolCaps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
		// Carol CANNOT write enrichments
		assertNotNull(CapabilityChecker.check(carolCaps, "covia:write",
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
		assertNull(CapabilityChecker.check(bobCaps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/enrichments/INV-2024-0891"))));
		// Bob can read vendor records
		assertNull(CapabilityChecker.check(bobCaps, "covia:read",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
		// Bob can list vendor records
		assertNull(CapabilityChecker.check(bobCaps, "covia:list",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records"))));
		// Bob CANNOT write to decisions
		assertNotNull(CapabilityChecker.check(bobCaps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/decisions/INV-2024-0891"))));
		// Bob CANNOT write vendor records
		assertNotNull(CapabilityChecker.check(bobCaps, "covia:write",
			Maps.of(Strings.create("path"), Strings.create("w/vendor-records/Acme Corp"))));
	}
}
