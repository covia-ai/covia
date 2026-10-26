package covia.adapter.messaging;

import java.util.ArrayList;
import java.util.List;

/** Text helpers shared by messaging providers. */
public final class MessagingText {
	private MessagingText() { }

	/**
	 * Split text into chunks of at most {@code max} UTF-16 units, preferring a
	 * paragraph, then a line, then a word break in the second half of a chunk.
	 * A chunk never ends inside a surrogate pair, so the count is safe whether a
	 * provider limits characters or code points. Whitespace at a break is dropped.
	 *
	 * @return the chunks; empty for null text, {@code [""]} for empty text
	 */
	public static List<String> split(String text, int max) {
		if (max < 2) throw new IllegalArgumentException("max must be at least 2");
		List<String> out = new ArrayList<>();
		if (text == null) return out;
		String rest = text;
		while (rest.length() > max) {
			int limit = Character.isHighSurrogate(rest.charAt(max - 1)) ? max - 1 : max;
			int cut = rest.lastIndexOf("\n\n", limit);
			if (cut < limit / 2) cut = rest.lastIndexOf('\n', limit);
			if (cut < limit / 2) cut = rest.lastIndexOf(' ', limit);
			if (cut < limit / 2 || cut <= 0) cut = limit;
			out.add(rest.substring(0, cut));
			rest = rest.substring(cut).stripLeading();
		}
		if (out.isEmpty() || !rest.isEmpty()) out.add(rest);
		return out;
	}
}
