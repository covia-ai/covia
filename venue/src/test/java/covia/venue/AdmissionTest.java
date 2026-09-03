package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/** The pure admission algebra (covia#447): exact allowlists, the operator class, fail-closed. */
public class AdmissionTest {

	private static final AString ALICE = Strings.create("did:key:z6MkAlice");
	private static final AString BOB = Strings.create("did:key:z6MkBob");
	private static final AString BOB_HELPER = Strings.create("did:key:z6MkBob:g:helper");

	@Test
	public void testOwnerAndAbsentAdmitNobody() {
		assertFalse(Admission.admits(null, BOB, false));
		assertFalse(Admission.admits(null, BOB, true));
		assertFalse(Admission.admits(Admission.OWNER, BOB, true));
		assertNull(Admission.problem(null));
		assertNull(Admission.problem(Admission.OWNER));
	}

	@Test
	public void testVenueAdmitsTheOperatorOnly() {
		assertTrue(Admission.admits(Admission.VENUE, BOB, true));
		assertFalse(Admission.admits(Admission.VENUE, BOB, false));
		assertFalse(Admission.admits(Admission.VENUE, null, true));
		assertNull(Admission.problem(Admission.VENUE));
	}

	@Test
	public void testAllowlistIsExact() {
		assertTrue(Admission.admits(Vectors.of(BOB), BOB, false));
		assertFalse(Admission.admits(Vectors.of(BOB), BOB_HELPER, false), "a user DID does not admit that user's agents");
		assertFalse(Admission.admits(Vectors.of(BOB_HELPER), BOB, false), "an agent DID does not admit its owner");
		assertTrue(Admission.admits(Vectors.of(BOB_HELPER), BOB_HELPER, false));
		assertFalse(Admission.admits(Vectors.of(BOB), ALICE, false));
		assertFalse(Admission.admits(Vectors.of(Strings.create("did:key:z6MkBo")), BOB, false), "no prefixes");
		assertFalse(Admission.admits(Vectors.empty(), BOB, true));
		assertNull(Admission.problem(Vectors.of(BOB, BOB_HELPER)));
	}

	@Test
	public void testVenueKeywordComposesInsideAnAllowlist() {
		assertTrue(Admission.admits(Vectors.of(Admission.VENUE, BOB), ALICE, true));
		assertTrue(Admission.admits(Vectors.of(Admission.VENUE, BOB), BOB, false));
		assertFalse(Admission.admits(Vectors.of(Admission.VENUE, BOB), ALICE, false));
	}

	@Test
	public void testMalformedPoliciesFailClosedAndAreNamed() {
		AString everyone = Strings.create("everyone");
		assertNotNull(Admission.problem(everyone));
		assertFalse(Admission.admits(everyone, BOB, true));

		assertNotNull(Admission.problem(CVMLong.create(42)));
		assertFalse(Admission.admits(CVMLong.create(42), BOB, true));

		// One bad entry poisons the whole list: nobody is admitted on it.
		assertNotNull(Admission.problem(Vectors.of(BOB, Strings.create("bob"))));
		assertFalse(Admission.admits(Vectors.of(BOB, Strings.create("bob")), BOB, false));
		assertNotNull(Admission.problem(Vectors.of(CVMLong.create(1))));
	}
}
