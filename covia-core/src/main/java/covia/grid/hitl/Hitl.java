package covia.grid.hitl;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

/**
 * Human-in-the-Loop shapes (COG-16): the shared field vocabulary and fluent
 * builders for HITL requests and responses.
 *
 * <p>A HITL flow is driven with three calls against any venue (local Engine or
 * remote via the Grid API — the built maps are transport-portable JSON):</p>
 *
 * <pre>
 * // 1. Request — returns a Job that parks INPUT_REQUIRED until the human acts
 * Job job = venue.invoke("v/ops/hitl/request",
 *     Hitl.request("Pay invoice INV-4711")
 *         .description("Acme Ltd, £12,400, matched to PO-2231.")
 *         .ask(Hitl.approval("pay", "Approve payment?").required()
 *             .grant("w/payments/", "crud/read"))
 *         .timeout(86400)
 *         .build());
 *
 * // 2. Response — the inbox owner answers (or Hitl.reject(id, reason))
 * venue.invoke("v/ops/hitl/respond",
 *     Hitl.answer(requestId)
 *         .answer("pay", true)
 *         .echo("w/payments/", "crud/read")   // explicit grant consent
 *         .comment("Approved for this invoice only")
 *         .build());
 *
 * // 3. Completion — the requester's Job resolves
 * ACell output = job.awaitResult();           // {id, outcome, answers, token?, ...}
 * </pre>
 *
 * <p>Builders construct shapes only; all validation is venue-side at
 * submission/response time, so a malformed build fails loudly at the venue,
 * never silently.</p>
 */
public class Hitl {

	// ========== Field names ==========

	public static final AString ID          = Strings.intern("id");
	public static final AString FROM        = Strings.intern("from");
	public static final AString AGENT       = Strings.intern("agent");
	public static final AString TITLE       = Strings.intern("title");
	public static final AString DESCRIPTION = Strings.intern("description");
	public static final AString ASKS        = Strings.intern("asks");
	public static final AString STATUS      = Strings.intern("status");
	public static final AString CREATED     = Strings.intern("created");
	public static final AString EXPIRES     = Strings.intern("expires");
	public static final AString RESPONSE    = Strings.intern("response");

	public static final AString TYPE     = Strings.intern("type");
	public static final AString PROMPT   = Strings.intern("prompt");
	public static final AString OPTIONS  = Strings.intern("options");
	public static final AString REQUIRED = Strings.intern("required");
	public static final AString LABEL    = Strings.intern("label");
	public static final AString GRANTS   = Strings.intern("grants");

	public static final AString USER     = Strings.intern("user");
	public static final AString TIMEOUT  = Strings.intern("timeout");
	public static final AString OUTCOME  = Strings.intern("outcome");
	public static final AString ANSWERS  = Strings.intern("answers");
	public static final AString COMMENTS = Strings.intern("comments");
	public static final AString COMMENT  = Strings.intern("comment");
	public static final AString TOKEN    = Strings.intern("token");
	public static final AString ITEMS    = Strings.intern("items");
	public static final AString COUNT    = Strings.intern("count");

	public static final AString WITH = Strings.intern("with");
	public static final AString CAN  = Strings.intern("can");
	public static final AString EXP  = Strings.intern("exp");

	// ===== Token-ask (COG-18) — self-sovereign cross-venue token transport =====
	/** Token-ask request spec: requested capabilities, {@code [{with, can}]}. */
	public static final AString CAPS     = Strings.intern("caps");
	/** Token-ask request spec: intended audience DID of the signed token
	 *  (default = the request's {@code from}, the requesting agent). */
	public static final AString AUDIENCE = Strings.intern("audience");
	/** Token-ask request spec: the target venue (informational, for the UI). */
	public static final AString VENUE    = Strings.intern("venue");
	/** Job-output field: transported self-sovereign tokens, ask id → signed JWT.
	 *  Distinct from {@link #TOKEN} (a venue-MINTED grant token). */
	public static final AString TOKENS   = Strings.intern("tokens");

	// ========== Record statuses ==========

	public static final AString OPEN      = Strings.intern("open");
	public static final AString ANSWERED  = Strings.intern("answered");
	public static final AString REJECTED  = Strings.intern("rejected");
	public static final AString EXPIRED   = Strings.intern("expired");
	public static final AString CANCELLED = Strings.intern("cancelled");

	// ========== Ask types ==========

	public static final AString TEXT       = Strings.intern("text");
	public static final AString APPROVAL   = Strings.intern("approval");
	public static final AString CHOICE     = Strings.intern("choice");
	public static final AString CHECKBOXES = Strings.intern("checkboxes");
	/** COG-18: a request for a self-sovereign access token. The human signs a
	 *  UCAN with their own key client-side; the answer is that signed JWT, which
	 *  the venue verifies and TRANSPORTS (never mints). Its request spec lives
	 *  under the ask's {@code token} field ({@link #TOKEN}). */
	public static final AString TOKEN_ASK  = Strings.intern("token");

	// ========== Outcomes ==========

	public static final AString ANSWER = Strings.intern("answer");
	public static final AString REJECT = Strings.intern("reject");

	private Hitl() {}

	// ========== Entry points ==========

	/** Starts a request to the caller's own user (an agent asking its owner). */
	public static RequestBuilder request(String title) {
		return new RequestBuilder(title);
	}

	public static AskBuilder text(String id, String prompt)       { return new AskBuilder(id, TEXT, prompt); }
	public static AskBuilder approval(String id, String prompt)   { return new AskBuilder(id, APPROVAL, prompt); }
	public static AskBuilder choice(String id, String prompt)     { return new AskBuilder(id, CHOICE, prompt); }
	public static AskBuilder checkboxes(String id, String prompt) { return new AskBuilder(id, CHECKBOXES, prompt); }

	/** A capability grant offer {with, can} — bare paths mean the responder's own namespace. */
	public static AMap<AString, ACell> grant(String with, String can) {
		return Maps.of(WITH, Strings.create(with), CAN, Strings.create(can));
	}

	/** A grant offer with an explicit expiry (unix seconds). */
	public static AMap<AString, ACell> grant(String with, String can, long exp) {
		return grant(with, can).assoc(EXP, CVMLong.create(exp));
	}

	/** Starts an {@code answer} response for a request in the caller's inbox. */
	public static ResponseBuilder answer(String requestId) {
		return new ResponseBuilder(requestId, ANSWER);
	}

	/** A complete {@code reject} response — the comment is the reason the requester sees. */
	public static AMap<AString, ACell> reject(String requestId, String comment) {
		AMap<AString, ACell> m = Maps.of(ID, Strings.create(requestId), OUTCOME, REJECT);
		if (comment != null) m = m.assoc(COMMENT, Strings.create(comment));
		return m;
	}

	// ========== Builders ==========

	/** Builds a {@code hitl:request} input. */
	public static class RequestBuilder {
		private AMap<AString, ACell> map;
		private AVector<ACell> asks = Vectors.empty();

		private RequestBuilder(String title) {
			map = Maps.of(TITLE, Strings.create(title));
		}

		/** Targets another user's inbox (requires a hitl/request delegation from them). */
		public RequestBuilder to(String userDID) {
			map = map.assoc(USER, Strings.create(userDID));
			return this;
		}

		/** Markdown context — the human sees only the record, so include everything. */
		public RequestBuilder description(String markdown) {
			map = map.assoc(DESCRIPTION, Strings.create(markdown));
			return this;
		}

		public RequestBuilder ask(AskBuilder ask) {
			return ask(ask.build());
		}

		public RequestBuilder ask(AMap<AString, ACell> ask) {
			asks = asks.conj(ask);
			return this;
		}

		/** Seconds until the request expires (Job FAILED, record 'expired'). */
		public RequestBuilder timeout(long seconds) {
			map = map.assoc(TIMEOUT, CVMLong.create(seconds));
			return this;
		}

		public AMap<AString, ACell> build() {
			return map.assoc(ASKS, asks);
		}
	}

	/** Builds one typed ask. */
	public static class AskBuilder {
		private AMap<AString, ACell> map;
		private AVector<ACell> options = Vectors.empty();
		private AVector<ACell> grants = Vectors.empty();

		private AskBuilder(String id, AString type, String prompt) {
			map = Maps.of(ID, Strings.create(id), TYPE, type, PROMPT, Strings.create(prompt));
		}

		/** An {@code answer} outcome must answer this ask. */
		public AskBuilder required() {
			map = map.assoc(REQUIRED, CVMBool.TRUE);
			return this;
		}

		/** Clients should offer a free-text comment alongside the answer. */
		public AskBuilder allowComment() {
			map = map.assoc(COMMENT, CVMBool.TRUE);
			return this;
		}

		/** Offers a grant conferred if this (approval) ask is approved. */
		public AskBuilder grant(String with, String can) {
			return grant(Hitl.grant(with, can));
		}

		public AskBuilder grant(AMap<AString, ACell> grant) {
			grants = grants.conj(grant);
			return this;
		}

		/** Adds a selectable option (choice/checkboxes). */
		public AskBuilder option(String id, String label) {
			options = options.conj(Maps.of(ID, Strings.create(id), LABEL, Strings.create(label)));
			return this;
		}

		/** Adds an option that confers a grant when selected. */
		public AskBuilder option(String id, String label, AMap<AString, ACell> grant) {
			options = options.conj(Maps.of(
				ID, Strings.create(id), LABEL, Strings.create(label),
				GRANTS, Vectors.of((ACell) grant)));
			return this;
		}

		public AMap<AString, ACell> build() {
			AMap<AString, ACell> m = map;
			if (options.count() > 0) m = m.assoc(OPTIONS, options);
			if (grants.count() > 0) m = m.assoc(GRANTS, grants);
			return m;
		}
	}

	/** Builds a {@code hitl:respond} input for an {@code answer} outcome. */
	public static class ResponseBuilder {
		private AMap<AString, ACell> map;
		private AMap<AString, ACell> answers = Maps.empty();
		private AMap<AString, ACell> comments = Maps.empty();
		private AVector<ACell> echoed = Vectors.empty();

		private ResponseBuilder(String requestId, AString outcome) {
			map = Maps.of(ID, Strings.create(requestId), OUTCOME, outcome);
		}

		/** Answers an approval ask. */
		public ResponseBuilder answer(String askId, boolean approved) {
			answers = answers.assoc(Strings.create(askId), CVMBool.of(approved));
			return this;
		}

		/** Answers a text ask, or a choice ask by option id. */
		public ResponseBuilder answer(String askId, String value) {
			answers = answers.assoc(Strings.create(askId), Strings.create(value));
			return this;
		}

		/** Answers a checkboxes ask with the selected option ids. */
		public ResponseBuilder select(String askId, String... optionIds) {
			AVector<ACell> ids = Vectors.empty();
			for (String o : optionIds) ids = ids.conj(Strings.create(o));
			answers = answers.assoc(Strings.create(askId), ids);
			return this;
		}

		/** Raw answer form, for anything the typed overloads don't cover. */
		public ResponseBuilder answerRaw(String askId, ACell value) {
			answers = answers.assoc(Strings.create(askId), value);
			return this;
		}

		/** Per-ask free-text comment. */
		public ResponseBuilder commentOn(String askId, String text) {
			comments = comments.assoc(Strings.create(askId), Strings.create(text));
			return this;
		}

		/** Overall comment. */
		public ResponseBuilder comment(String text) {
			map = map.assoc(COMMENT, Strings.create(text));
			return this;
		}

		/** Echoes an offered grant the responder explicitly approves — without an
		 *  echo, nothing is conferred; an echo that was not offered-and-triggered
		 *  fails the response. */
		public ResponseBuilder echo(String with, String can) {
			return echo(Hitl.grant(with, can));
		}

		public ResponseBuilder echo(AMap<AString, ACell> grant) {
			echoed = echoed.conj(grant);
			return this;
		}

		public AMap<AString, ACell> build() {
			AMap<AString, ACell> m = map.assoc(ANSWERS, answers);
			if (comments.count() > 0) m = m.assoc(COMMENTS, comments);
			if (echoed.count() > 0) m = m.assoc(GRANTS, echoed);
			return m;
		}
	}
}
