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
 * Tests for CapabilityChecker — the covia-side capability primitives.
 *
 * <p>There is no central name-keyed boundary: each adapter asserts its own cap
 * at its enforcement point (see {@code AdapterCapEnforcementTest}). These tests
 * cover the shared primitives those adapters call — {@link CapabilityChecker#allows},
 * the boundary-aware {@code resourceMatches}, {@code readOnlyCeiling},
 * {@code selfCapabilities}, and {@link RequestContext#requireCapability} — plus
 * end-to-end enforcement through {@code JobManager}. Resource/ability prefix
 * matching ultimately delegates to convex-core's {@code Capability.covers},
 * exercised through here.</p>
 */
public class CapabilityCheckerTest {

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
	private static final AString TEST_OWNER = Strings.create("did:key:zTestOwner");

	private static String allows(AVector<ACell> caps, String resource, String ability) {
		return CapabilityChecker.allows(caps, resource, ability, TEST_OWNER);
	}

	// ========== End-to-end enforcement at JobManager ==========
	// Enforcement now happens at the adapter's point, so a denial surfaces as a
	// FAILED Job — observed at awaitResult, not as a synchronous throw from
	// invokeOperation. invokeInternal denials surface via the returned future.

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

		// Write to disallowed path — denied (the adapter fails the Job).
		assertThrows(Exception.class, () ->
			engine.jobs().invokeOperation("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/forbidden/doc", Fields.VALUE, Strings.create("bad")), ctx)
				.awaitResult(5000),
			"Write to a disallowed path must be denied");
	}

	@Test
	public void testInvokeInternalEnforcesContextCeiling() {
		// Trust is a property of the context's authority, not the call path.
		// invokeInternal differs from invokeOperation only in Job creation — both
		// enforce whatever ceiling the context carries via the adapter's pin.
		Engine engine = TestEngine.ENGINE;

		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create("w/allowed/"), Capability.CRUD_WRITE)
		);
		RequestContext gated = RequestContext.of(
			convex.auth.ucan.UCAN.toDIDKey(convex.core.crypto.AKeyPair.generate().getAccountKey())
		).withCaps(caps);

		// User-facing path: capped ctx writing outside its scope — denied (Job fails).
		assertThrows(Exception.class, () ->
			engine.jobs().invokeOperation("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/forbidden/doc", Fields.VALUE, Strings.create("nope")), gated)
				.awaitResult(5000));

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

	// ==================================================================
	// allows(caps, resource, ability, owner) — the pinned-resource primitive
	// the executing adapter calls. The adapter supplies the EXACT resource and
	// ability, so the enforced cap can't drift from the implementation.
	// ==================================================================

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
		AVector<ACell> noMatch = Vectors.of(
			Strings.create("not-a-map"),                                          // skipped, no crash
			Capability.create(Strings.create("w/different"), Capability.CRUD_READ), // wrong resource
			Capability.create(Strings.create("g/X"), Strings.create("agent/create"))); // wrong ability+resource
		assertNotNull(allows(noMatch, "w/x", "crud/read"), "no grant matches → denied");

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

	// ==================================================================
	// Hardenings (Convex #585 mitigation + self-ceiling hygiene + explicit
	// cross-user write rejection).
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

		// Mutations are denied — the adapter fails the Job (observed at awaitResult).
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/x", Fields.VALUE, Strings.create("v")), ctx).awaitResult(5000),
			"write must be denied under a read-only ceiling");
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, "w/x"), ctx).awaitResult(5000),
			"delete must be denied under a read-only ceiling");
	}
}
