package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;

/**
 * Tests for {@link JSONAdapter} — pure data manipulation primitives.
 *
 * <p>Two layers of tests:</p>
 * <ol>
 *   <li>Direct unit tests of the static helpers ({@code mergePatch}, {@code truthy})
 *       — fastest, exhaustively cover the data shape behaviour without any
 *       venue/HTTP overhead.</li>
 *   <li>End-to-end invocation via the shared {@code TestServer.COVIA} client —
 *       verifies the adapter is registered and wired into the operation
 *       dispatch path correctly.</li>
 * </ol>
 */
public class JSONAdapterTest {

	private static final VenueHTTP COVIA = TestServer.COVIA;

	private static AString s(String s) { return Strings.create(s); }
	private static CVMLong l(long n) { return CVMLong.create(n); }

	// ========================================================================
	// json:merge — RFC 7396 deep merge
	// ========================================================================

	@Test public void testMergePatchTwoMapsShallow() {
		AMap<AString, ACell> a = Maps.of(s("x"), l(1), s("y"), l(2));
		AMap<AString, ACell> b = Maps.of(s("y"), l(99), s("z"), l(3));
		ACell merged = JSONAdapter.mergePatch(a, b);
		assertEquals(Maps.of(s("x"), l(1), s("y"), l(99), s("z"), l(3)), merged);
	}

	@Test public void testMergePatchDeepRecursive() {
		// Nested maps must merge recursively (RFC 7396)
		AMap<AString, ACell> a = Maps.of(
			s("user"), Maps.of(s("name"), s("alice"), s("age"), l(30)));
		AMap<AString, ACell> b = Maps.of(
			s("user"), Maps.of(s("age"), l(31), s("email"), s("a@b.c")));
		ACell merged = JSONAdapter.mergePatch(a, b);
		AMap<AString, ACell> expectedUser = Maps.of(
			s("name"), s("alice"), s("age"), l(31), s("email"), s("a@b.c"));
		assertEquals(Maps.of(s("user"), expectedUser), merged);
	}

	@Test public void testMergePatchNullDeletesKey() {
		// RFC 7396: null in patch deletes the key from target
		AMap<AString, ACell> a = Maps.of(s("x"), l(1), s("y"), l(2));
		AMap<AString, ACell> b = Maps.of(s("y"), null);
		ACell merged = JSONAdapter.mergePatch(a, b);
		assertEquals(Maps.of(s("x"), l(1)), merged);
	}

	@Test public void testMergePatchNullDeletesNested() {
		AMap<AString, ACell> a = Maps.of(
			s("user"), Maps.of(s("name"), s("alice"), s("age"), l(30)));
		AMap<AString, ACell> b = Maps.of(
			s("user"), Maps.of(s("age"), null));
		ACell merged = JSONAdapter.mergePatch(a, b);
		assertEquals(Maps.of(s("user"), Maps.of(s("name"), s("alice"))), merged);
	}

	@Test public void testMergePatchNonMapPatchReplacesTarget() {
		// RFC 7396: if patch is not a map, the patch replaces target entirely
		AMap<AString, ACell> a = Maps.of(s("x"), l(1));
		ACell patch = s("hello");
		ACell merged = JSONAdapter.mergePatch(a, patch);
		assertEquals(s("hello"), merged);
	}

	@Test public void testMergePatchMapPatchOverNonMapTarget() {
		// If target is not a map, treat as empty map
		ACell merged = JSONAdapter.mergePatch(s("ignored"), Maps.of(s("x"), l(1)));
		assertEquals(Maps.of(s("x"), l(1)), merged);
	}

	@Test public void testMergePatchOverNullTarget() {
		ACell merged = JSONAdapter.mergePatch(null, Maps.of(s("x"), l(1)));
		assertEquals(Maps.of(s("x"), l(1)), merged);
	}

	@Test public void testMergeViaInvocation() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/merge", Maps.of(
			"values", Vectors.of(
				Maps.of(s("a"), l(1)),
				Maps.of(s("b"), l(2)),
				Maps.of(s("a"), l(99)))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(
			Maps.of(s("a"), l(99), s("b"), l(2)),
			RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testMergeEmptyValuesYieldsEmptyMap() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/merge", Maps.of("values", Vectors.empty()));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(Maps.empty(), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testMergeDeepViaInvocation() throws Exception {
		// Three-way deep merge
		Job result = COVIA.invokeSync("v/ops/json/merge", Maps.of(
			"values", Vectors.of(
				Maps.of(s("decision"), s("APPROVED")),
				Maps.of(s("invoice_summary"), Maps.of(s("amount"), l(800))),
				Maps.of(s("policy_rules"), Vectors.of(s("AP-001"), s("AP-002"))))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		ACell merged = RT.getIn(result.getOutput(), "result");
		assertEquals(s("APPROVED"), RT.getIn(merged, "decision"));
		assertEquals(l(800), RT.getIn(merged, "invoice_summary", "amount"));
	}

	// ========================================================================
	// json:cond — pick first truthy case
	// ========================================================================

	@Test public void testTruthyRules() {
		// Falsy
		assertFalse(JSONAdapter.truthy(null));
		assertFalse(JSONAdapter.truthy(CVMBool.FALSE));
		// Truthy
		assertTrue(JSONAdapter.truthy(CVMBool.TRUE));
		assertTrue(JSONAdapter.truthy(l(0)));               // 0 is truthy (strict rule)
		assertTrue(JSONAdapter.truthy(s("")));              // empty string is truthy
		assertTrue(JSONAdapter.truthy(Maps.empty()));       // empty map is truthy
		assertTrue(JSONAdapter.truthy(Vectors.empty()));    // empty vector is truthy
	}

	@Test public void testCondPicksFirstTruthy() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/cond", Maps.of(
			"cases", Vectors.of(
				Maps.of(s("when"), CVMBool.FALSE, s("then"), s("nope")),
				Maps.of(s("when"), CVMBool.TRUE,  s("then"), s("first-true")),
				Maps.of(s("when"), CVMBool.TRUE,  s("then"), s("second-true")))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(s("first-true"), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testCondNoMatchReturnsDefault() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/cond", Maps.of(
			"cases", Vectors.of(
				Maps.of(s("when"), CVMBool.FALSE, s("then"), s("nope1")),
				Maps.of(s("when"), CVMBool.FALSE, s("then"), s("nope2"))),
			"default", s("fallback")));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(s("fallback"), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testCondNoMatchNoDefaultReturnsNull() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/cond", Maps.of(
			"cases", Vectors.of(
				Maps.of(s("when"), CVMBool.FALSE, s("then"), s("nope")))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertNull(RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testCondNullWhenIsFalsy() throws Exception {
		// when=null should be treated as falsy (not matched)
		Job result = COVIA.invokeSync("v/ops/json/cond", Maps.of(
			"cases", Vectors.of(
				Maps.of(s("when"), null, s("then"), s("skipped")),
				Maps.of(s("when"), CVMBool.TRUE, s("then"), s("matched")))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(s("matched"), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testCondZeroIsTruthy() throws Exception {
		// 0 is truthy under our strict rule — only false/null are falsy
		Job result = COVIA.invokeSync("v/ops/json/cond", Maps.of(
			"cases", Vectors.of(
				Maps.of(s("when"), l(0), s("then"), s("zero-matched")))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(s("zero-matched"), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testCondCanReturnComplexValue() throws Exception {
		// 'then' values can be arbitrary structures (maps, vectors, etc.)
		Job result = COVIA.invokeSync("v/ops/json/cond", Maps.of(
			"cases", Vectors.of(
				Maps.of(s("when"), CVMBool.TRUE,
					s("then"), Maps.of(s("decision"), s("APPROVED"), s("amount"), l(800))))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(s("APPROVED"), RT.getIn(result.getOutput(), "result", "decision"));
		assertEquals(l(800), RT.getIn(result.getOutput(), "result", "amount"));
	}

	// ========================================================================
	// json:assoc — set value at path
	// ========================================================================

	@Test public void testAssocStringPath() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/assoc", Maps.of(
			"target", Maps.of(s("a"), l(1)),
			"path", s("b"),
			"value", l(2)));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(
			Maps.of(s("a"), l(1), s("b"), l(2)),
			RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testAssocVectorPathNested() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/assoc", Maps.of(
			"target", Maps.of(s("user"), Maps.of(s("name"), s("alice"))),
			"path", Vectors.of(s("user"), s("email")),
			"value", s("a@b.c")));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		ACell out = RT.getIn(result.getOutput(), "result");
		assertEquals(s("alice"), RT.getIn(out, "user", "name"));
		assertEquals(s("a@b.c"), RT.getIn(out, "user", "email"));
	}

	@Test public void testAssocOverwritesExisting() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/assoc", Maps.of(
			"target", Maps.of(s("a"), l(1)),
			"path", s("a"),
			"value", l(99)));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(Maps.of(s("a"), l(99)), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testAssocMissingTargetUsesEmptyMap() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/assoc", Maps.of(
			"path", s("k"),
			"value", l(42)));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(Maps.of(s("k"), l(42)), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testAssocCreatesIntermediateMaps() throws Exception {
		// assoc-in semantics: missing intermediate keys get filled with empty maps
		Job result = COVIA.invokeSync("v/ops/json/assoc", Maps.of(
			"target", Maps.empty(),
			"path", Vectors.of(s("a"), s("b"), s("c")),
			"value", l(7)));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(l(7), RT.getIn(result.getOutput(), "result", "a", "b", "c"));
	}

	// ========================================================================
	// json:select — pick by key
	// ========================================================================

	@Test public void testSelectByKey() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/select", Maps.of(
			"key", s("manager"),
			"cases", Maps.of(
				s("auto"),    Maps.of(s("decision"), s("APPROVED")),
				s("manager"), Maps.of(s("decision"), s("ESCALATED"), s("target"), s("J. Martinez")),
				s("vp"),      Maps.of(s("decision"), s("ESCALATED"), s("target"), s("VP")))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(s("ESCALATED"), RT.getIn(result.getOutput(), "result", "decision"));
		assertEquals(s("J. Martinez"), RT.getIn(result.getOutput(), "result", "target"));
	}

	@Test public void testSelectMissingKeyReturnsDefault() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/select", Maps.of(
			"key", s("unknown"),
			"cases", Maps.of(s("a"), l(1), s("b"), l(2)),
			"default", s("fallback")));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertEquals(s("fallback"), RT.getIn(result.getOutput(), "result"));
	}

	@Test public void testSelectMissingKeyNoDefaultReturnsNull() throws Exception {
		Job result = COVIA.invokeSync("v/ops/json/select", Maps.of(
			"key", s("unknown"),
			"cases", Maps.of(s("a"), l(1))));
		assertEquals(Status.COMPLETE, result.getStatus(), msg(result));
		assertNull(RT.getIn(result.getOutput(), "result"));
	}

	// ========================================================================
	// Composition: end-to-end AP-style decision via merge + cond
	// ========================================================================

	@Test public void testApStyleDecisionAssembly() throws Exception {
		// Simulate the AP pipeline tail:
		//   - cond picks the tier-based base decision (manager tier)
		//   - merge combines it with invoice summary and policy rules

		// Step 1: cond picks tier decision
		Job tierJob = COVIA.invokeSync("v/ops/json/cond", Maps.of(
			"cases", Vectors.of(
				Maps.of(s("when"), CVMBool.FALSE,
					s("then"), Maps.of(s("decision"), s("APPROVED"), s("tier"), s("auto"))),
				Maps.of(s("when"), CVMBool.TRUE,
					s("then"), Maps.of(
						s("decision"), s("ESCALATED"),
						s("tier"), s("manager"),
						s("escalation_target"), s("J. Martinez"))))));
		assertEquals(Status.COMPLETE, tierJob.getStatus(), msg(tierJob));
		ACell tierDecision = RT.getIn(tierJob.getOutput(), "result");

		// Step 2: merge tier decision with invoice summary and policy rules
		Job mergeJob = COVIA.invokeSync("v/ops/json/merge", Maps.of(
			"values", Vectors.of(
				tierDecision,
				Maps.of(s("invoice_summary"),
					Maps.of(s("vendor"), s("Acme Corp"), s("amount"), l(15600))),
				Maps.of(s("policy_rules_passed"), CVMBool.TRUE))));
		assertEquals(Status.COMPLETE, mergeJob.getStatus(), msg(mergeJob));
		ACell finalDecision = RT.getIn(mergeJob.getOutput(), "result");

		assertEquals(s("ESCALATED"),    RT.getIn(finalDecision, "decision"));
		assertEquals(s("manager"),      RT.getIn(finalDecision, "tier"));
		assertEquals(s("J. Martinez"),  RT.getIn(finalDecision, "escalation_target"));
		assertEquals(s("Acme Corp"),    RT.getIn(finalDecision, "invoice_summary", "vendor"));
		assertEquals(l(15600),          RT.getIn(finalDecision, "invoice_summary", "amount"));
		assertEquals(CVMBool.TRUE,      RT.getIn(finalDecision, "policy_rules_passed"));
	}

	// ========================================================================
	// Helpers
	// ========================================================================

	private static String msg(Job j) {
		if (j.getStatus() == Status.FAILED) {
			return "Job failed: " + j.getErrorMessage();
		}
		return "Status: " + j.getStatus();
	}
}
