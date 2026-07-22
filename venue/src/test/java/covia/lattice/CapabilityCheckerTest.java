package covia.lattice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.RootAuthorityPolicy;
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
 * cover the shared primitives those adapters call — {@link CapabilityChecker#allows}
 * (boundary matching via core's {@code Capability.resourceCovers}), {@code readOnlyScope},
 * and {@link RequestContext#requireCapability} — plus
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

	@Test
	public void testPublicCallerDenialCarriesAuthHint() {
		// The public/anonymous identity's denial points to the auth remedy
		// (covia#206) — scoped to the ":public" caller.
		AVector<ACell> readOnly = CapabilityChecker.readOnlyScope(
			Strings.create("did:key:zVenue:public"));
		String pub = CapabilityChecker.allows(readOnly, "w/x", "crud/write",
			Strings.create("did:key:zVenue:public"));
		assertNotNull(pub);
		assertTrue(pub.contains("Capability denied"), pub);
		assertTrue(pub.contains("Authenticate") && pub.contains("UCAN.md"),
			"public denial must carry the auth hint: " + pub);

		// A capped agent (real DID owner) hitting its own scope gets a clean
		// message — no misleading "authenticate" advice (it already is
		// authenticated; it should just handle the denial, #211).
		String agent = CapabilityChecker.allows(readOnly, "w/x", "crud/write",
			Strings.create("did:key:zRealAgentOwner"));
		assertNotNull(agent);
		assertTrue(agent.contains("Capability denied"), agent);
		assertFalse(agent.contains("Authenticate"),
			"a real identity's own-scope denial must stay clean: " + agent);
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
	public void testInvokeInternalEnforcesContextScope() {
		// Trust is a property of the context's authority, not the call path.
		// invokeInternal differs from invokeOperation only in Job creation — both
		// enforce whatever scope the context carries via the adapter's pin.
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

		// Internal path: same scope, same op — also denied. No call-path bypass.
		assertThrows(Exception.class, () ->
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/forbidden/doc", Fields.VALUE, Strings.create("nope")), gated)
				.join(),
			"invokeInternal must enforce the context scope");

		// Within the scope, the internal write succeeds.
		ACell ok = engine.jobs().invokeInternal("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/allowed/doc", Fields.VALUE, Strings.create("ok")), gated)
			.join();
		assertNotNull(ok);

		// The scope stays on the ctx — enforcement reads it, never strips it.
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

	// ========== #211 — resource-precise invoke ==========

	@Test
	public void testAllowsScopedInvokeGrant() {
		// An invoke grant scoped to an op-path prefix covers ops under it and
		// nothing else — the "which ops may this agent invoke" attenuation.
		AVector<ACell> scoped = caps("v/test/ops", "invoke");
		assertNull(allows(scoped, "v/test/ops/echo", "invoke"));      // child
		assertNull(allows(scoped, "v/test/ops", "invoke"));           // exact
		assertNotNull(allows(scoped, "v/ops/http/get", "invoke"));    // outside prefix
		assertNotNull(allows(scoped, "v/test/opsX", "invoke"));       // boundary — not a child
		assertNotNull(allows(scoped, null, "invoke"));                // resource-less (meta-direct)
		assertNotNull(allows(scoped, "0xdeadbeef", "invoke"));        // hash form — path caps don't cover
	}

	@Test
	public void testAllowsWildcardInvokeGrantCoversEverything() {
		// A full invoke grant stays a one-liner: {"can":"invoke"} (with omitted)
		// and {"with":"", "can":"invoke"} both cover path-form, hash-form and
		// resource-less invokes — the documented "restricted paths + full tool
		// access" recipe keeps working after resource-precision (#211).
		AVector<ACell> noWith = Vectors.of(
			Maps.of(Capability.CAN, Strings.create("invoke")));
		AVector<ACell> emptyWith = caps("", "invoke");
		for (AVector<ACell> scope : java.util.List.of(noWith, emptyWith)) {
			assertNull(allows(scope, "v/test/ops/echo", "invoke"));
			assertNull(allows(scope, "0xdeadbeef", "invoke"));
			assertNull(allows(scope, null, "invoke"));
		}
	}

	@Test
	public void testScopedInvokeEndToEnd() {
		// The op reference the caller supplies reaches requireInvoke via
		// RequestContext.withOp, so a scoped invoke grant admits exactly
		// the named ops and the denial names the op that was blocked.
		Engine engine = TestEngine.ENGINE;
		AVector<ACell> invokeCaps = Vectors.of(
			Capability.create(Strings.create("v/test/ops/echo"), Strings.create("invoke")));
		RequestContext ctx = RequestContext.of(
			convex.auth.ucan.UCAN.toDIDKey(convex.core.crypto.AKeyPair.generate().getAccountKey())
		).withCaps(invokeCaps);

		Job ok = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of("data", Strings.create("hi")), ctx);
		assertNotNull(ok.awaitResult(5000), "in-scope op must be invocable");

		Job denied = engine.jobs().invokeOperation("v/test/ops/random",
			Maps.of(), ctx);
		assertThrows(Exception.class, () -> denied.awaitResult(5000),
			"out-of-scope op must be denied");
		String err = denied.getErrorMessage();
		assertTrue(err.contains("Capability denied"), err);
		assertTrue(err.contains("v/test/ops/random"),
			"denial must name the blocked op: " + err);
	}

	// ==================================================================
	// allows(caps, resource, ability, owner) — the pinned-resource primitive
	// the executing adapter calls. The adapter supplies the EXACT resource and
	// ability, so the enforced cap can't drift from the implementation.
	// ==================================================================

	@Test
	public void testAllowsNullScopeIsUnrestricted() {
		// null scope = full authority (internal/unrestricted callers).
		assertNull(allows(null, "w/anything", "crud/write"));
		assertNull(allows(null, "did:key:zOther/w/x", "secret/write"));
		assertNull(allows(null, null, null));
	}

	@Test
	public void testAllowsEmptyScopeDeniesEverything() {
		// Empty scope grants NOTHING — the crucial distinction from null.
		assertNotNull(allows(Vectors.empty(), "w/x", "crud/read"));
		assertNotNull(allows(Vectors.empty(), "w/x", "crud/write"));
		assertNotNull(allows(Vectors.empty(), "v/test/ops/echo", "invoke"));
	}

	@Test
	public void testAllowsExactAndPrefix() {
		AVector<ACell> scope = caps("w/notes", "crud/write");
		assertNull(allows(scope, "w/notes", "crud/write"));          // exact
		assertNull(allows(scope, "w/notes/2026/x", "crud/write"));   // child (prefix)
		assertNotNull(allows(scope, "w/other", "crud/write"));       // sibling — denied
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
		AVector<ACell> scope = Vectors.of(Capability.create(TEST_OWNER, Capability.CRUD_READ));
		assertNull(allows(scope, "w/notes", "crud/read"));                       // own
		assertNotNull(allows(scope, "did:key:zOther/w/notes", "crud/read"));     // cross-user → denied
	}

	@Test
	public void testAllowsMultipleCapsAnyMatchWins() {
		AVector<ACell> scope = caps(
			"w/a", "crud/read",
			"w/b", "crud/write");
		assertNull(allows(scope, "w/b/x", "crud/write"));   // second grant matches
		assertNull(allows(scope, "w/a/x", "crud/read"));    // first grant matches
		assertNotNull(allows(scope, "w/a/x", "crud/write")); // neither grants write on a
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
		AVector<ACell> scope = Vectors.of(
			Capability.create(Strings.create(""), Strings.create("asset/read")));
		assertNull(allows(scope, "0xdeadbeef", "asset/read"));
		assertNull(allows(scope, "did:key:zOther/a/0xabc", "asset/read"));
		assertNotNull(allows(scope, "0xdeadbeef", "asset/store")); // wrong ability
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
	// readOnlyScope + RequestContext.requireCapability — the public
	// read-only default and the adapter-facing enforcement primitive.
	// ==================================================================

	@Test
	public void testReadOnlyScopeGrantsOnlyReads() {
		AString did = Strings.create("did:key:zPublic");
		AVector<ACell> scope = CapabilityChecker.readOnlyScope(did);
		// Reads: own/venue lattice + content-addressed assets
		assertNull(CapabilityChecker.allows(scope, "w/x", "crud/read", did));
		assertNull(CapabilityChecker.allows(scope, "v/ops/covia/read", "crud/read", did));
		assertNull(CapabilityChecker.allows(scope, "0xhash", "asset/read", did));
		// Every mutating ability denied
		assertNotNull(CapabilityChecker.allows(scope, "w/x", "crud/write", did));
		assertNotNull(CapabilityChecker.allows(scope, "w/x", "crud/delete", did));
		assertNotNull(CapabilityChecker.allows(scope, "s/KEY", "secret/write", did));
		assertNotNull(CapabilityChecker.allows(scope, "g/Bob", "agent/create", did));
		assertNotNull(CapabilityChecker.allows(scope, "0xh", "asset/store", did));
		assertNotNull(CapabilityChecker.allows(scope, "v/test/ops/echo", "invoke", did));
		// And no cross-user read
		assertNotNull(CapabilityChecker.allows(scope, "did:key:zOther/w/x", "crud/read", did));
	}

	@Test
	public void testRequireCapabilityEnforcesReadOnlyScope() {
		AString did = Strings.create("did:key:zPublic");
		RequestContext ctx = RequestContext.of(did).withCaps(CapabilityChecker.readOnlyScope(did));
		assertDoesNotThrow(() -> ctx.requireCapability("w/notes", "crud/read"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("w/notes", "crud/write"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("w/notes", "crud/delete"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("s/KEY", "secret/write"));
		assertThrows(AuthException.class, () -> ctx.requireCapability("v/test/ops/echo", "invoke"));
	}

	@Test
	public void testRequireCapabilityNullScopeIsNoOp() {
		// Authenticated / internal callers carry no scope → unrestricted.
		RequestContext ctx = RequestContext.of(Strings.create("did:key:zAlice"));
		assertDoesNotThrow(() -> ctx.requireCapability("w/anything", "crud/write"));
		assertDoesNotThrow(() -> ctx.requireCapability("s/KEY", "secret/write"));
	}

	@Test
	public void testAllowsAStringAndStringOverloadsAgree() {
		AString did = Strings.create("did:key:zPublic");
		AVector<ACell> scope = CapabilityChecker.readOnlyScope(did);
		// The AString-native primary and the String convenience overload must
		// produce identical verdicts and identical denial messages.
		assertEquals(
			CapabilityChecker.allows(scope, "w/x", "crud/write", did),
			CapabilityChecker.allows(scope, Strings.create("w/x"), Capability.CRUD_WRITE, did));
		assertNull(CapabilityChecker.allows(scope, Strings.create("w/x"), Capability.CRUD_READ, did));
		assertNotNull(CapabilityChecker.allows(scope, Strings.create("w/x"), Capability.CRUD_WRITE, did));
	}

	@Test
	public void testRequireCapabilityAStringOverloadEnforces() {
		AString did = Strings.create("did:key:zPublic");
		RequestContext ctx = RequestContext.of(did).withCaps(CapabilityChecker.readOnlyScope(did));
		assertDoesNotThrow(() -> ctx.requireCapability(Strings.create("w/x"), Capability.CRUD_READ));
		assertThrows(AuthException.class,
			() -> ctx.requireCapability(Strings.create("w/x"), Capability.CRUD_WRITE));
	}

	// ==================================================================
	// Hardenings (Convex #585 mitigation + explicit cross-user write rejection).
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
	public void testProofsCoverFailsClosed() {
		// covia#196 boundary pin: malformed or hostile inputs to the cross-user
		// grant gate must yield false — never true, never a throw.
		AString caller = Strings.create("did:key:zCaller");
		AString venue = Strings.create("did:key:zVenue");
		AString res = Strings.create("did:key:zOwner/w/notes");
		AString ability = Strings.create("crud/read");
		long now = System.currentTimeMillis() / 1000;
		RootAuthorityPolicy policy = RootAuthorityPolicy.SELF_SOVEREIGN.or(
			(root, with) -> venue.equals(root));

		// Null inputs → false.
		assertFalse(CapabilityChecker.proofsCover(null, caller, policy, res, ability, now));
		assertFalse(CapabilityChecker.proofsCover(Vectors.empty(), null, policy, res, ability, now));
		assertFalse(CapabilityChecker.proofsCover(Vectors.empty(), caller, policy, null, ability, now));
		assertFalse(CapabilityChecker.proofsCover(Vectors.empty(), caller, policy, res, null, now));
		assertFalse(CapabilityChecker.proofsCover(Vectors.empty(), caller, null, res, ability, now));

		// Garbage proof entries (non-map, map with no UCAN fields, nonsense fields)
		// grant nothing and must not throw.
		AVector<ACell> garbage = Vectors.of(
			Strings.create("not-a-token"),
			convex.core.data.Maps.empty(),
			convex.core.data.Maps.of(Strings.create("bogus"), Strings.create("junk")));
		assertFalse(CapabilityChecker.proofsCover(garbage, caller, policy, res, ability, now));

		// A resource with no derivable DID owner (unanchorable scheme): grants
		// nothing under the self-sovereign arm — fail-closed at the capability.
		assertFalse(CapabilityChecker.proofsCover(garbage, caller, policy,
			Strings.create("https://example.com/x"), ability, now));
	}

	@Test
	public void testLegacyDlfsSchemeShorthandCoversOwnDrive() {
		// A scope cap written in the legacy scheme form (dlfs://docs/) must cover
		// the DID-scoped path form the adapter now enforces (dlfs/docs/…), and
		// vice versa — both canonicalise to <owner>/dlfs/docs/….
		AString did = Strings.create("did:key:zDlfsCompat");
		AVector<ACell> legacy = Vectors.of(
			Capability.create(Strings.create("dlfs://docs/"), Capability.CRUD_READ));
		assertNull(CapabilityChecker.allows(legacy, "dlfs/docs/notes.txt", "crud/read", did));
		assertNotNull(CapabilityChecker.allows(legacy, "dlfs/other/notes.txt", "crud/read", did)); // other drive — DENY
		assertNotNull(CapabilityChecker.allows(legacy, "dlfs/docs/notes.txt", "crud/write", did)); // wrong ability — DENY

		// Path-form cap covers a legacy-form op resource identically.
		AVector<ACell> pathForm = Vectors.of(
			Capability.create(Strings.create("dlfs/docs/"), Capability.CRUD_READ));
		assertNull(CapabilityChecker.allows(pathForm, "dlfs://docs/notes.txt", "crud/read", did));

		// Bare "dlfs://" covers every drive of the owner (legacy wildcard-of-drives).
		AVector<ACell> allDrives = Vectors.of(
			Capability.create(Strings.create("dlfs://"), Capability.CRUD_READ));
		assertNull(CapabilityChecker.allows(allDrives, "dlfs/anything/x", "crud/read", did));
	}

	@Test
	public void testEmptyWithGrantIsScopeWildcard() {
		// An empty-`with` grant matches any resource — the venue's asset/read
		// scope relies on it. This wildcard lives only in the scope (allows)
		// path, never in the fail-closed UCAN proof path. Boundary matching for
		// concrete grants is core's Capability.resourceCovers (Convex #585),
		// exercised via allows() in testResourceBoundaryViaAllows above.
		AVector<ACell> caps = Vectors.of(
			Capability.create(Strings.create(""), Strings.create("asset/read")));
		assertNull(CapabilityChecker.allows(caps, "a/deadbeef", "asset/read", null));   // any resource
		assertNull(CapabilityChecker.allows(caps, "anything/else", "asset/read", null));
		assertNotNull(CapabilityChecker.allows(caps, "a/deadbeef", "crud/write", null)); // wrong ability — DENY
	}

	@Test
	public void testCrossUserDIDWritePathRejected() {
		Engine engine = TestEngine.ENGINE;
		AString did = convex.auth.ucan.UCAN.toDIDKey(convex.core.crypto.AKeyPair.generate().getAccountKey());
		RequestContext ctx = RequestContext.of(did); // authenticated, null scope
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
	public void testReadOnlyScopeStopsMutationsEndToEnd() {
		Engine engine = TestEngine.ENGINE;
		AString did = convex.auth.ucan.UCAN.toDIDKey(
			convex.core.crypto.AKeyPair.generate().getAccountKey());
		RequestContext ctx = RequestContext.of(did).withCaps(CapabilityChecker.readOnlyScope(did));

		// Read under a read-only scope is allowed (absent path → exists:false).
		Job read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "w/x"), ctx);
		assertNotNull(read.awaitResult(5000), "read is allowed under a read-only scope");

		// Mutations are denied — the adapter fails the Job (observed at awaitResult).
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "w/x", Fields.VALUE, Strings.create("v")), ctx).awaitResult(5000),
			"write must be denied under a read-only scope");
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, "w/x"), ctx).awaitResult(5000),
			"delete must be denied under a read-only scope");
		// s/ deletes take the delete-only namespace branch (#166) — the capability
		// gate must still apply there (requireCap runs before the namespace rule).
		assertThrows(Exception.class, () -> engine.jobs().invokeOperation("v/ops/covia/delete",
			Maps.of(Fields.PATH, "s/x"), ctx).awaitResult(5000),
			"secret delete must be denied under a read-only scope");
	}
}
