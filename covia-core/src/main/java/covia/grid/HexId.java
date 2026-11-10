package covia.grid;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;

/**
 * Reads an id that arrives as text from outside the venue, such as a task id in
 * an operation's input. Callers may paste an id in the {@code 0x} form that REST
 * and lattice reads show, so the prefix is accepted here, at the edge, and goes
 * no further: what the venue writes and reads back is bare hex.
 *
 * <p>Use this wherever the text does more than map to bytes, such as a path
 * segment or a reference that may be a hash or a path: there, text that is not
 * exactly an id must name something else, or nothing.</p>
 */
public final class HexId {

	private static final String PREFIX = "0x";

	private HexId() {}

	/**
	 * Reads hex digits with an optional leading {@code 0x}. Nothing else is
	 * accepted: no surrounding whitespace, and no empty id.
	 *
	 * @param text The id as given, may be null
	 * @return The id's bytes, or null when the text is not such an id
	 */
	public static Blob parse(AString text) {
		if (text == null) return null;
		String s = text.toString();
		String hex = s.startsWith(PREFIX) ? s.substring(PREFIX.length()) : s;
		return hex.isEmpty() ? null : Blob.fromHex(hex);
	}

	/**
	 * Reads a content hash in the same form as {@link #parse(AString)}.
	 *
	 * @param text The hash as given, may be null
	 * @return The hash, or null when the text is not a whole hash
	 */
	public static Hash parseHash(AString text) {
		Blob id = parse(text);
		return (id == null) ? null : Hash.wrap(id);
	}
}
