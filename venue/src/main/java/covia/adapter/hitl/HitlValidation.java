package covia.adapter.hitl;

import java.util.HashSet;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.grid.hitl.Hitl;

/**
 * Pure HITL domain rules (COG-16) — request/response validation and the
 * echo-consent grant intersection. No engine, no lattice, no side effects:
 * every function maps immutable inputs to a result or throws
 * {@link IllegalArgumentException} with an actionable message.
 *
 * <p>Kept separate from {@code HITLAdapter} (which owns authorisation,
 * persistence and job control) so the semantics are unit-testable in
 * isolation and reusable by any other HITL surface.</p>
 */
public class HitlValidation {

	private static final Set<String> ASK_TYPES = Set.of("text", "approval", "choice", "checkboxes");

	private HitlValidation() {}

	/**
	 * Validates the asks of a request at submission: ids unique, types known,
	 * prompts present, options present/unique where required, and grants
	 * attached ONLY where an explicit choice confers them — approval asks and
	 * options (COG-16: a grant must be the consequence of a choice).
	 *
	 * @param asksCell the raw {@code asks} input
	 * @return the validated asks vector
	 * @throws IllegalArgumentException on any violation — no record is written,
	 *         no job parks
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> validateAsks(ACell asksCell) {
		if (!(asksCell instanceof AVector) || ((AVector<ACell>) asksCell).count() == 0) {
			throw new IllegalArgumentException("asks must be a non-empty array");
		}
		AVector<ACell> asks = (AVector<ACell>) asksCell;
		Set<String> ids = new HashSet<>();
		for (long i = 0; i < asks.count(); i++) {
			AMap<AString, ACell> ask = RT.castMap(asks.get(i));
			if (ask == null) throw new IllegalArgumentException("asks[" + i + "] must be an object");
			AString id = RT.ensureString(ask.get(Hitl.ID));
			AString type = RT.ensureString(ask.get(Hitl.TYPE));
			AString prompt = RT.ensureString(ask.get(Hitl.PROMPT));
			if (id == null || !ids.add(id.toString())) {
				throw new IllegalArgumentException("asks[" + i + "].id is required and must be unique");
			}
			if (type == null || !ASK_TYPES.contains(type.toString())) {
				throw new IllegalArgumentException("asks[" + i + "].type must be one of " + ASK_TYPES);
			}
			if (prompt == null) throw new IllegalArgumentException("asks[" + i + "].prompt is required");
			boolean optionType = Hitl.CHOICE.equals(type) || Hitl.CHECKBOXES.equals(type);
			ACell optionsCell = ask.get(Hitl.OPTIONS);
			if (optionType) {
				if (!(optionsCell instanceof AVector) || ((AVector<ACell>) optionsCell).count() == 0) {
					throw new IllegalArgumentException("asks[" + i + "].options must be a non-empty array");
				}
				Set<String> optionIds = new HashSet<>();
				AVector<ACell> options = (AVector<ACell>) optionsCell;
				for (long j = 0; j < options.count(); j++) {
					AMap<AString, ACell> opt = RT.castMap(options.get(j));
					AString optId = (opt != null) ? RT.ensureString(opt.get(Hitl.ID)) : null;
					if (opt == null || optId == null || !optionIds.add(optId.toString())) {
						throw new IllegalArgumentException("asks[" + i + "].options[" + j
							+ "] must be an object with a unique id");
					}
					validateGrantList(opt.get(Hitl.GRANTS), "asks[" + i + "].options[" + j + "]");
				}
			} else if (optionsCell != null) {
				throw new IllegalArgumentException("asks[" + i + "]: options only apply to choice/checkboxes");
			}
			ACell grants = ask.get(Hitl.GRANTS);
			if (grants != null && !Hitl.APPROVAL.equals(type)) {
				throw new IllegalArgumentException("asks[" + i + "]: grants only attach to approval "
					+ "asks and options — a grant must be the consequence of an explicit choice");
			}
			validateGrantList(grants, "asks[" + i + "]");
		}
		return asks;
	}

	private static void validateGrantList(ACell grantsCell, String where) {
		if (grantsCell == null) return;
		if (!(grantsCell instanceof AVector)) {
			throw new IllegalArgumentException(where + ".grants must be an array");
		}
		AVector<ACell> grants = (AVector<ACell>) grantsCell;
		for (long i = 0; i < grants.count(); i++) {
			AMap<AString, ACell> g = RT.castMap(grants.get(i));
			if (g == null || RT.ensureString(g.get(Hitl.WITH)) == null
					|| RT.ensureString(g.get(Hitl.CAN)) == null) {
				throw new IllegalArgumentException(where + ".grants[" + i + "] must be {with, can, exp?}");
			}
		}
	}

	/**
	 * Validates a response's answers against the request's asks and computes
	 * the OFFERED grants the choices actually triggered: grants on approval
	 * asks answered {@code true}, and grants on selected options.
	 *
	 * @param asks the request's validated asks
	 * @param answers map of ask id → answer
	 * @return the triggered offers (capability maps, as offered)
	 * @throws IllegalArgumentException on any violation — the record stays
	 *         open and the job unresolved
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> validateAnswers(AVector<ACell> asks, AMap<AString, ACell> answers) {
		AVector<ACell> triggered = Vectors.empty();
		Set<String> askIds = new HashSet<>();
		for (long i = 0; i < asks.count(); i++) {
			AMap<AString, ACell> ask = (AMap<AString, ACell>) asks.get(i);
			AString askId = RT.ensureString(ask.get(Hitl.ID));
			askIds.add(askId.toString());
			AString type = RT.ensureString(ask.get(Hitl.TYPE));
			ACell answer = answers.get(askId);
			if (answer == null) {
				if (CVMBool.TRUE.equals(ask.get(Hitl.REQUIRED))) {
					throw new IllegalArgumentException("required ask '" + askId + "' is unanswered");
				}
				continue;
			}
			if (Hitl.TEXT.equals(type)) {
				if (RT.ensureString(answer) == null) {
					throw new IllegalArgumentException("answer for '" + askId + "' must be a string");
				}
			} else if (Hitl.APPROVAL.equals(type)) {
				if (!(answer instanceof CVMBool)) {
					throw new IllegalArgumentException("answer for '" + askId + "' must be a boolean");
				}
				if (CVMBool.TRUE.equals(answer)) {
					triggered = appendGrants(triggered, ask.get(Hitl.GRANTS));
				}
			} else { // choice / checkboxes
				AVector<ACell> options = (AVector<ACell>) ask.get(Hitl.OPTIONS);
				if (Hitl.CHOICE.equals(type)) {
					AMap<AString, ACell> opt = findOption(options, RT.ensureString(answer));
					if (opt == null) {
						throw new IllegalArgumentException("answer for '" + askId + "' must name an option id");
					}
					triggered = appendGrants(triggered, opt.get(Hitl.GRANTS));
				} else {
					if (!(answer instanceof AVector)) {
						throw new IllegalArgumentException("answer for '" + askId + "' must be an array of option ids");
					}
					Set<String> seen = new HashSet<>();
					AVector<ACell> selected = (AVector<ACell>) answer;
					for (long j = 0; j < selected.count(); j++) {
						AString optId = RT.ensureString(selected.get(j));
						AMap<AString, ACell> opt = findOption(options, optId);
						if (opt == null || !seen.add(optId.toString())) {
							throw new IllegalArgumentException("answer for '" + askId
								+ "' contains an unknown or duplicate option id");
						}
						triggered = appendGrants(triggered, opt.get(Hitl.GRANTS));
					}
				}
			}
		}
		// Unknown answer keys are an error — answers bind to asks, exactly.
		for (long i = 0; i < answers.count(); i++) {
			AString key = RT.ensureString(answers.entryAt(i).getKey());
			if (key == null || !askIds.contains(key.toString())) {
				throw new IllegalArgumentException("answers contains unknown ask id: " + key);
			}
		}
		return triggered;
	}

	/**
	 * Echo-consent (COG-16): returns exactly the intersection of the ECHOED
	 * grants and the offers actually TRIGGERED by the choices made. Matching is
	 * by {@code {with, can}} equality; the result always carries the OFFER's
	 * own capability map (including its {@code exp}), never the echo's.
	 *
	 * @param echoedCell the response's {@code grants} field (may be null)
	 * @param triggered the offers triggered by the answers
	 * @return the approved grants to issue
	 * @throws IllegalArgumentException if any echoed grant was not
	 *         offered-and-triggered
	 */
	public static AVector<ACell> intersectEchoedGrants(ACell echoedCell, AVector<ACell> triggered) {
		if (echoedCell == null) return Vectors.empty();
		if (!(echoedCell instanceof AVector)) {
			throw new IllegalArgumentException("grants must be an array of {with, can} capabilities");
		}
		AVector<ACell> echoed = (AVector<ACell>) echoedCell;
		AVector<ACell> approved = Vectors.empty();
		for (long i = 0; i < echoed.count(); i++) {
			AMap<AString, ACell> e = RT.castMap(echoed.get(i));
			AString with = (e != null) ? RT.ensureString(e.get(Hitl.WITH)) : null;
			AString can = (e != null) ? RT.ensureString(e.get(Hitl.CAN)) : null;
			ACell match = null;
			if (with != null && can != null) {
				for (long j = 0; j < triggered.count(); j++) {
					ACell offer = triggered.get(j);
					if (with.equals(RT.getIn(offer, Hitl.WITH))
							&& can.equals(RT.getIn(offer, Hitl.CAN))) {
						match = offer;
						break;
					}
				}
			}
			if (match == null) {
				throw new IllegalArgumentException("echoed grant " + i + " was not offered by the "
					+ "choices made — the venue issues only offered-and-triggered grants");
			}
			approved = approved.conj(match);
		}
		return approved;
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> findOption(AVector<ACell> options, AString optId) {
		if (optId == null || options == null) return null;
		for (long i = 0; i < options.count(); i++) {
			AMap<AString, ACell> opt = (AMap<AString, ACell>) options.get(i);
			if (optId.equals(opt.get(Hitl.ID))) return opt;
		}
		return null;
	}

	private static AVector<ACell> appendGrants(AVector<ACell> acc, ACell grantsCell) {
		if (!(grantsCell instanceof AVector)) return acc;
		AVector<ACell> grants = (AVector<ACell>) grantsCell;
		for (long i = 0; i < grants.count(); i++) acc = acc.conj(grants.get(i));
		return acc;
	}
}
