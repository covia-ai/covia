package covia.utils;

import java.util.Collection;
import java.util.Locale;

/**
 * The name a caller most likely meant when it gave one an operation or
 * setting does not know, picked from the short list of names it does know
 * (its fields, a setting's choices, the kinds of value it makes), so a
 * refusal can say "did you mean".
 *
 * <p>Names are compared ignoring case, {@code -} and {@code _}. Another name
 * counts as close when the shorter of the two, at least three characters
 * long, is part of the other, or when it is a few typing slips away: one
 * for names of three or four characters, and two, or a quarter of the
 * shorter name's length if more, for longer ones. A slip is a character
 * added, removed, changed, or swapped with its neighbour. Nothing is close
 * to a name of fewer than three characters unless it differs only in case or
 * separators.
 */
public final class NearestNames {

	private NearestNames() {}

	/** What {@link #hint} adds to a refusal: the name the caller most likely meant. */
	public static final String DID_YOU_MEAN = " (did you mean '%s'?)";

	/**
	 * The name in {@code known} closest to {@code given}, or null when none is
	 * even remotely close (or {@code given} is null). Of names equally close,
	 * the first in {@code known}'s order is chosen, so a caller wanting the same
	 * answer every time passes an ordered collection. A name equal to
	 * {@code given} is its own nearest.
	 */
	public static String nearest(String given, Collection<String> known) {
		if (given == null) return null;
		String a = normal(given);
		String best = null;
		int bestScore = Integer.MAX_VALUE;
		for (String name : known) {
			int score = score(a, normal(name));
			if (score >= 0 && score < bestScore) {
				best = name;
				bestScore = score;
			}
		}
		return best;
	}

	/**
	 * {@link #DID_YOU_MEAN} naming the {@link #nearest} known name, or the
	 * empty string when none is close, for the end of a refusal's first clause.
	 */
	public static String hint(String given, Collection<String> known) {
		String near = nearest(given, known);
		return near == null ? "" : DID_YOU_MEAN.formatted(near);
	}

	private static String normal(String name) {
		return name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
	}

	/** How far apart two normalised names are, lower being closer, or -1 when they are not close. */
	private static int score(String a, String b) {
		if (a.equals(b)) return 0;
		String shorter = a.length() <= b.length() ? a : b;
		String longer = shorter == a ? b : a;
		int n = shorter.length();
		if (n < 3) return -1;
		if (longer.contains(shorter)) return longer.length() - n;
		int slips = n < 5 ? 1 : Math.max(2, n / 4);
		if (longer.length() - n > slips) return -1;
		int d = distance(a, b);
		return d <= slips ? d : -1;
	}

	/** Edits from one name to the other: a character added, removed, changed, or swapped with its neighbour. */
	private static int distance(String a, String b) {
		int[][] d = new int[a.length() + 1][b.length() + 1];
		for (int i = 0; i <= a.length(); i++) d[i][0] = i;
		for (int j = 0; j <= b.length(); j++) d[0][j] = j;
		for (int i = 1; i <= a.length(); i++) {
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
					d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
				}
			}
		}
		return d[a.length()][b.length()];
	}
}
