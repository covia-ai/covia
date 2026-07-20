package covia.adapter.hitl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.grid.hitl.Hitl;

/**
 * Pure unit tests for the HITL domain rules — no engine, no lattice, no IO.
 * Every COG-16 validation rule and the echo-consent intersection, driven
 * through the {@link Hitl} builders (which doubles as builder shape coverage).
 */
public class HitlValidationTest {

	// ========== validateAsks ==========

	@Test
	public void testValidAsksPass() {
		AVector<ACell> asks = (AVector<ACell>) RT.getIn(
			Hitl.request("t")
				.ask(Hitl.text("notes", "Anything?"))
				.ask(Hitl.approval("pay", "Pay?").required().grant("w/reports/", "crud/read"))
				.ask(Hitl.choice("tier", "Tier?").option("fast", "Fast").option("best", "Best"))
				.ask(Hitl.checkboxes("scopes", "Scopes?")
					.option("r", "Read", Hitl.grant("w/data/", "crud/read")))
				.build(),
			Hitl.ASKS);
		assertEquals(4, HitlValidation.validateAsks(asks).count());
	}

	@Test
	public void testAsksStructuralViolations() {
		// Empty / non-vector
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.empty()));
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Strings.create("x")));
		// Duplicate ask ids
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			Hitl.text("a", "p").build(), Hitl.text("a", "p2").build())));
		// Unknown type
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			(ACell) Maps.of(Hitl.ID, Strings.create("a"), Hitl.TYPE, Strings.create("essay"),
				Hitl.PROMPT, Strings.create("p")))));
		// Missing prompt
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			(ACell) Maps.of(Hitl.ID, Strings.create("a"), Hitl.TYPE, Hitl.TEXT))));
		// choice without options
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			Hitl.choice("a", "p").build())));
		// Duplicate option ids
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			Hitl.choice("a", "p").option("x", "X").option("x", "X2").build())));
		// Options on a non-option type
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			(ACell) Hitl.text("a", "p").build().assoc(Hitl.OPTIONS,
				Vectors.of((ACell) Maps.of(Hitl.ID, Strings.create("x"), Hitl.LABEL, Strings.create("X")))))));
	}

	@Test
	public void testGrantPlacementRules() {
		// Grants ride explicit choices ONLY: approval asks and options.
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			(ACell) Hitl.text("a", "p").build().assoc(Hitl.GRANTS,
				Vectors.of((ACell) Hitl.grant("w/x", "crud/read"))))));
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			(ACell) Hitl.choice("a", "p").option("x", "X").build().assoc(Hitl.GRANTS,
				Vectors.of((ACell) Hitl.grant("w/x", "crud/read"))))));
		// Malformed grant entries
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAsks(Vectors.of(
			(ACell) Hitl.approval("a", "p").build().assoc(Hitl.GRANTS,
				Vectors.of((ACell) Maps.of(Hitl.WITH, Strings.create("w/x"))))))); // no can
		// Approval-ask and option grants are the permitted placements.
		assertEquals(1, HitlValidation.validateAsks(Vectors.of(
			Hitl.approval("a", "p").grant("w/x", "crud/read").build())).count());
		assertEquals(1, HitlValidation.validateAsks(Vectors.of(
			Hitl.checkboxes("a", "p").option("x", "X", Hitl.grant("w/x", "crud/read")).build())).count());
	}

	// ========== validateAnswers ==========

	private static AVector<ACell> sampleAsks() {
		return HitlValidation.validateAsks(Vectors.of(
			Hitl.approval("pay", "Pay?").required().grant("w/reports/", "crud/read").build(),
			Hitl.choice("tier", "Tier?").option("fast", "Fast")
				.option("best", "Best", Hitl.grant("w/best/", "crud/read")).build(),
			Hitl.checkboxes("scopes", "Scopes?")
				.option("r", "Read", Hitl.grant("w/data/", "crud/read"))
				.option("w", "Write", Hitl.grant("w/data/", "crud/write")).build(),
			Hitl.text("notes", "Notes?").build()));
	}

	private static AMap<AString, ACell> answers(ACell... kv) {
		AMap<AString, ACell> m = Maps.empty();
		for (int i = 0; i < kv.length; i += 2) m = m.assoc((AString) kv[i], kv[i + 1]);
		return m;
	}

	@Test
	public void testAnswerShapes() {
		AVector<ACell> asks = sampleAsks();
		// Required unanswered
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAnswers(asks,
			answers(Strings.create("tier"), Strings.create("fast"))));
		// Approval must be boolean
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAnswers(asks,
			answers(Strings.create("pay"), Strings.create("yes"))));
		// Text must be a string
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAnswers(asks,
			answers(Strings.create("pay"), CVMBool.TRUE, Strings.create("notes"), CVMLong.create(1))));
		// Choice must name a known option
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAnswers(asks,
			answers(Strings.create("pay"), CVMBool.TRUE, Strings.create("tier"), Strings.create("zzz"))));
		// Checkboxes must be a vector of known, non-duplicate option ids
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAnswers(asks,
			answers(Strings.create("pay"), CVMBool.TRUE, Strings.create("scopes"), Strings.create("r"))));
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAnswers(asks,
			answers(Strings.create("pay"), CVMBool.TRUE,
				Strings.create("scopes"), Vectors.of(Strings.create("r"), Strings.create("r")))));
		// Unknown ask id
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.validateAnswers(asks,
			answers(Strings.create("pay"), CVMBool.TRUE, Strings.create("bogus"), Strings.create("x"))));
	}

	@Test
	public void testTriggeredOffers() {
		AVector<ACell> asks = sampleAsks();
		// Approval TRUE + option selections trigger exactly their grants.
		AVector<ACell> triggered = HitlValidation.validateAnswers(asks, answers(
			Strings.create("pay"), CVMBool.TRUE,
			Strings.create("tier"), Strings.create("best"),
			Strings.create("scopes"), Vectors.of(Strings.create("r"))));
		assertEquals(3, triggered.count(), "approval grant + best-option grant + r-option grant");

		// Approval FALSE and unselected options trigger nothing.
		AVector<ACell> none = HitlValidation.validateAnswers(asks, answers(
			Strings.create("pay"), CVMBool.FALSE,
			Strings.create("tier"), Strings.create("fast")));
		assertEquals(0, none.count());
	}

	// ========== intersectEchoedGrants (echo-consent) ==========

	@Test
	public void testEchoConsentIntersection() {
		AVector<ACell> triggered = Vectors.of(
			(ACell) Hitl.grant("w/reports/", "crud/read", 1795000000L));

		// No echo → nothing conferred, always valid.
		assertEquals(0, HitlValidation.intersectEchoedGrants(null, triggered).count());

		// Echo of a triggered offer → approved, carrying the OFFER's map
		// (including its exp) even when the echo omits it.
		AVector<ACell> approved = HitlValidation.intersectEchoedGrants(
			Vectors.of((ACell) Hitl.grant("w/reports/", "crud/read")), triggered);
		assertEquals(1, approved.count());
		assertEquals(CVMLong.create(1795000000L), RT.getIn(approved.get(0), Hitl.EXP),
			"the issued capability is the offer's own map, never the echo's");

		// ADVERSARIAL: echo of a never-offered grant fails the response.
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.intersectEchoedGrants(
			Vectors.of((ACell) Hitl.grant("w/private/", "crud")), triggered));
		// ADVERSARIAL: echo of an offered-but-untriggered grant fails too.
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.intersectEchoedGrants(
			Vectors.of((ACell) Hitl.grant("w/reports/", "crud/read")), Vectors.empty()));
		// ADVERSARIAL: an echo widening the ability is not a match.
		assertThrows(IllegalArgumentException.class, () -> HitlValidation.intersectEchoedGrants(
			Vectors.of((ACell) Hitl.grant("w/reports/", "crud")), triggered));
	}
}
