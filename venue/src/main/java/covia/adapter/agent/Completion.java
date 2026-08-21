package covia.adapter.agent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.json.schema.JsonSchema;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * The completion boundary — one judgement for every way a model hands work
 * back: a control tool's arguments ({@code complete_task}, {@code complete},
 * {@code fail}…) or the reply text of a typed frame. What value was
 * delivered, and does it meet the contract in force? The verdict is phrased
 * for the model when it does not; how it reaches the model — a tool error,
 * a retry turn, a log line — is the caller's policy, as is which contract
 * is in force.
 *
 * <p>The rules, the same on every surface (#215, #376):</p>
 * <ul>
 *   <li>Nothing delivered — no payload, a blank string, an empty object —
 *       falls back to the turn's text: models routinely write the answer as
 *       prose and send an empty control call beside it. With no text either,
 *       the completion is rejected.</li>
 *   <li>With a schema in force, a string payload that parses as JSON
 *       conforming to the schema is taken as that value — the answer often
 *       arrives as text carrying JSON. Otherwise the payload is judged as it
 *       is. A mismatch is rejected with the reason and the schema.</li>
 * </ul>
 *
 * @param value the accepted value; null when rejected
 * @param rejection why it was not accepted, phrased for the model; null when accepted
 */
public record Completion(ACell value, String rejection) {

	public boolean accepted() {
		return rejection == null;
	}

	/** The rejection in the tool-result convention: {@code "Error: …"}. */
	public AString toolError() {
		return Strings.create("Error: " + rejection);
	}

	/**
	 * @param payload what was handed over: a tool's {@code result} argument,
	 *        the whole arguments object, or the reply text
	 * @param turnText the assistant text of the same turn — the fallback for
	 *        an empty payload; null when there is none
	 * @param schema the contract in force; null for free-form
	 * @param tool the control tool that delivered it; null for the reply
	 *        itself — shapes the wording only
	 */
	public static Completion of(ACell payload, AString turnText, AMap<AString, ACell> schema, String tool) {
		ACell value = payload;
		if (isBlank(value)) {
			if (turnText == null || turnText.toString().isBlank()) return reject(nothingDelivered(tool));
			value = turnText;
		}
		if (schema == null) return new Completion(value, null);

		ACell candidate = value;
		AString text = RT.ensureString(value);
		if (text != null) {
			try {
				ACell parsed = JSON.parse(text.toString());
				if (JsonSchema.validate(schema, parsed) == null) candidate = parsed;
			} catch (Exception e) {
				// Not JSON — judged as the string it is.
			}
		}
		String error = JsonSchema.validate(schema, candidate);
		if (error != null) return reject(doesNotConform(error, schema, tool));
		return new Completion(candidate, null);
	}

	/** Absent, a blank string, or an empty object — a payload the model plainly
	 *  did not fill. Any other value, however small, was meant. */
	private static boolean isBlank(ACell v) {
		if (v == null) return true;
		if (v instanceof AString s) return s.toString().isBlank();
		return v instanceof AMap<?, ?> m && m.isEmpty();
	}

	private static String nothingDelivered(String tool) {
		if (tool == null) return "The reply was empty. Respond again with the answer.";
		return tool + " was called with nothing to deliver, and this turn carried no message text to use "
			+ "instead. Call again with the answer in its arguments, or write the answer as your message "
			+ "text and call " + tool + " in the same turn.";
	}

	private static String doesNotConform(String error, AMap<AString, ACell> schema, String tool) {
		return "The result does not conform to the required response schema — " + error
			+ ". The schema is: " + JSON.print(schema) + ". "
			+ ((tool != null)
				? "Correct the result and call " + tool + " again."
				: "Respond again with valid JSON matching it.");
	}

	private static Completion reject(String why) {
		return new Completion(null, why);
	}
}
