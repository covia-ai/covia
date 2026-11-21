package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

/**
 * The text an adapter adds to its record of a request's route
 * (AUTHORITY.md, Provenance): written when the adapter stamps the record
 * ({@link RequestContext#via}), stored with it, and shown in brackets after
 * the part the venue writes, which names the adapter and the principal the
 * record vouches for. What the text says is the adapter's choice, and the
 * adapter may also word its record afresh for each reader as it is shown
 * ({@link covia.adapter.AAdapter#routeText}), in place of the stored text.
 *
 * <p>Fixed wording is the adapter's own ({@link Builder#text}). A value that
 * came from outside reaches the text only through a part whose shape is
 * checked each time the line is shown: a name, an identifier, a handle, a
 * time, a principal, whose something is, or a path in someone's namespace.
 * The last three are worded for whoever reads the line ({@link Provenance.Reader}).
 * The text is stored as built and never changed, so a stored turn reads the
 * same every time for the same reader.</p>
 */
public final class RouteText {

	/** A part naming something by the name its owner gave it, shown quoted. */
	public static final AString NAME = Strings.intern("name");
	/** A part naming something by an identifier, such as a sender's id. */
	public static final AString ID = Strings.intern("id");
	/** A part naming a platform handle, shown after {@code @}. */
	public static final AString HANDLE = Strings.intern("handle");
	/** A part naming a moment, in milliseconds since the epoch, shown in UTC. */
	public static final AString TIME = Strings.intern("time");
	/** A part naming a principal, shown as the agent's owner or not. */
	public static final AString WHO = Strings.intern("who");
	/** A part naming whose something is: the reader's, its owner's, or another's. */
	public static final AString WHOSE = Strings.intern("whose");
	/** A part naming a path in a principal's namespace ({@link covia.api.Fields#OWNER}), shown relative to a reader in that namespace. */
	public static final AString REF = Strings.intern("ref");

	/** Longest fixed wording one part keeps, in characters. */
	public static final int MAX_WORDS = 120;
	/** Most parts a text keeps. */
	public static final int MAX_PARTS = 24;

	private final AVector<ACell> parts;

	private RouteText(AVector<ACell> parts) {
		this.parts = parts;
	}

	/** The text as it is stored on its record. */
	public AVector<ACell> parts() {
		return parts;
	}

	/** A text of fixed wording alone. */
	public static RouteText of(String words) {
		return builder().text(words).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * {@code words} as one line of plain wording: control characters, line
	 * breaks and the characters that delimit a label or a route ({@code [ ] < >
	 * ; →}) become spaces, runs of spaces become one, and it is cut to
	 * {@link #MAX_WORDS} characters. The venue applies it when a text is built
	 * and again when it is shown.
	 */
	public static String clean(String words) {
		if (words == null) return "";
		StringBuilder sb = new StringBuilder();
		boolean space = false;
		for (int i = 0; i < words.length() && sb.length() < MAX_WORDS; i++) {
			char c = words.charAt(i);
			boolean blank = Character.isISOControl(c) || Character.isWhitespace(c) || c == ' ' || c == ' '
				|| c == '[' || c == ']' || c == '<' || c == '>' || c == ';' || c == '→';
			if (blank) {
				space = true;
				continue;
			}
			if (space && sb.length() > 0) sb.append(' ');
			space = false;
			sb.append(c);
		}
		return sb.toString();
	}

	/** Builds a text part by part, in the order it reads. */
	public static final class Builder {
		private AVector<ACell> parts = Vectors.empty();

		private Builder() {}

		private Builder add(ACell part) {
			if (parts.count() < MAX_PARTS) parts = parts.conj(part);
			return this;
		}

		/**
		 * Fixed wording of the adapter's own, kept with its spaces at either end
		 * so parts join as written. Never a value from outside: those go through
		 * the parts below.
		 */
		public Builder text(String words) {
			if (words == null || words.isEmpty()) return this;
			String cleaned = clean(words);
			if (cleaned.isEmpty()) return add(Strings.create(" "));
			String lead = Character.isWhitespace(words.charAt(0)) ? " " : "";
			String trail = Character.isWhitespace(words.charAt(words.length() - 1)) ? " " : "";
			return add(Strings.create(lead + cleaned + trail));
		}

		/** A name its owner gave something, such as a token's: shown quoted when it is one word of a name's characters, otherwise as unreadable. */
		public Builder name(ACell name) {
			return (name == null) ? this : add(Maps.of(NAME, name));
		}

		/** An identifier, such as a sender's or a chat's: shown when it is one word of an identifier's characters, otherwise as unreadable. */
		public Builder id(ACell id) {
			return (id == null) ? this : add(Maps.of(ID, id));
		}

		/** A platform handle: shown after {@code @} when it is one word of a handle's characters, otherwise left out. */
		public Builder handle(ACell handle) {
			return (handle == null) ? this : add(Maps.of(HANDLE, handle));
		}

		/** A moment, in milliseconds since the epoch: shown as a date and time in UTC. */
		public Builder time(long millis) {
			return add(Maps.of(TIME, CVMLong.create(millis)));
		}

		/** A principal: named as the reader refers to it, such as "your owner" or "user did:…". */
		public Builder who(AString did) {
			return (did == null) ? this : add(Maps.of(WHO, did));
		}

		/** Whose something is: "your owner's" for an agent's owner, "your" for the reader's own, otherwise the principal's. */
		public Builder whose(AString did) {
			return (did == null) ? this : add(Maps.of(WHOSE, did));
		}

		/**
		 * A path in {@code owner}'s namespace, such as a callable's
		 * {@code x/callable/<name>}: shown as the path to a reader in that
		 * namespace and as {@code <owner>/<path>} to anyone else, so either is a
		 * reference the reader can use. Shown when the path is one word of a
		 * path's characters, otherwise as unreadable.
		 */
		public Builder ref(AString owner, AString path) {
			if (path == null) return this;
			return add((owner == null) ? Maps.of(REF, path) : Maps.of(REF, path, covia.api.Fields.OWNER, owner));
		}

		public RouteText build() {
			return new RouteText(parts);
		}
	}
}
