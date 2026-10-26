package covia.adapter.speech;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import covia.exception.JobFailedException;

/**
 * What a lexicon voice can say, and text made sayable before one is asked. Such a
 * voice (VITS on LJSpeech) says only the words in its lexicon, and sherpa-onnx drops
 * any other without a sound: a name, every number. So first numbers and the common
 * symbols become words and accents are folded; then a Latin word the lexicon lacks is
 * spelled letter by letter, and said to have been ({@link Sayable#spelled}); anything
 * still unsayable fails the request, naming it. Nothing is left out quietly. Words are
 * split as sherpa-onnx splits them: lower case, at whitespace, and at ASCII
 * punctuation other than the apostrophe.
 */
final class Lexicon {

	public static final String UNSAYABLE = "The voice cannot say %s";

	/** Text a lexicon voice says all of, and the words in it that are spelled out. */
	record Sayable(String text, List<String> spelled) {}

	private final Set<String> words;

	Lexicon(Set<String> words) {
		this.words = words;
	}

	/** The lexicon in {@code file}: a word, then its phones, on each line. */
	static Lexicon load(Path file) throws IOException {
		Set<String> words = new HashSet<>();
		try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			for (String line; (line = in.readLine()) != null;) {
				int space = line.indexOf(' ');
				if (space > 0) words.add(line.substring(0, space));
			}
		}
		return new Lexicon(words);
	}

	Sayable sayable(String text) {
		String t = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
			.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
			.replace('–', '-').replace('—', '-');
		t = numbers(t);
		StringBuilder out = new StringBuilder();
		StringBuilder word = new StringBuilder();
		List<String> spelled = new ArrayList<>();
		Set<String> unsayable = new LinkedHashSet<>();
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (Character.isWhitespace(c) || (c < 128 && c != '\'' && isPunct(c))) {
				say(word, out, spelled, unsayable);
				String symbol = SYMBOLS.get(c);
				out.append(symbol != null ? " " + symbol + " " : String.valueOf(c));
			} else {
				word.append(c);
			}
		}
		say(word, out, spelled, unsayable);
		if (!unsayable.isEmpty()) throw new JobFailedException(UNSAYABLE.formatted(String.join(", ", unsayable)));
		return new Sayable(out.toString().replaceAll(" {2,}", " ").replaceAll(" ([.,;:!?])", "$1").strip(), spelled);
	}

	private static boolean isPunct(char c) {
		return (c >= 0x21 && c <= 0x2F) || (c >= 0x3A && c <= 0x40) || (c >= 0x5B && c <= 0x60) || (c >= 0x7B && c <= 0x7E);
	}

	/** Symbols that mean a word, said as it. */
	private static final Map<Character, String> SYMBOLS = Map.of('%', "percent", '&', "and", '+', "plus", '=', "equals",
		'@', "at");

	private void say(StringBuilder word, StringBuilder out, List<String> spelled, Set<String> unsayable) {
		if (word.isEmpty()) return;
		String original = word.toString();
		word.setLength(0);
		String w = original.toLowerCase(Locale.ROOT);
		if (words.contains(w)) {
			out.append(w);
			return;
		}
		String bare = w.replaceAll("^'+|'+$", "");
		if (bare.isEmpty()) return; // a quotation mark of apostrophes says nothing
		if (words.contains(bare)) {
			out.append(bare);
			return;
		}
		if (bare.endsWith("'s") && words.contains(bare.substring(0, bare.length() - 2))) {
			out.append(bare, 0, bare.length() - 2);
			return;
		}
		String letters = bare.replace("'", "");
		if (letters.matches("[a-z]+") && letters.chars().allMatch(ch -> words.contains(String.valueOf((char) ch)))) {
			out.append(String.join(" ", letters.split("")));
			spelled.add(original);
			return;
		}
		unsayable.add(original);
	}

	// ---- numbers ----

	private static final Pattern NUMBER = Pattern.compile(
		"([$£€])?(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?(st|nd|rd|th)?", Pattern.CASE_INSENSITIVE);
	private static final Map<String, String> CURRENCY = Map.of("$", "dollars", "£", "pounds", "€", "euros");
	private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
		"ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
	private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
	private static final String[] SCALES = {"", "thousand", "million", "billion"};

	/** Every number in {@code text} as words, with its currency, decimals or ordinal ending. */
	static String numbers(String text) {
		Matcher m = NUMBER.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String digits = m.group(2).replace(",", "");
			String said;
			if (digits.length() > 1 && digits.startsWith("0") || digits.length() > 12) said = digitByDigit(digits);
			else said = cardinal(Long.parseLong(digits));
			if (m.group(3) != null) said += " point " + digitByDigit(m.group(3).substring(1));
			else if (m.group(4) != null) said = ordinal(said);
			if (m.group(1) != null) said += " " + CURRENCY.get(m.group(1));
			m.appendReplacement(out, Matcher.quoteReplacement(" " + said + " "));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String digitByDigit(String digits) {
		List<String> said = new ArrayList<>();
		for (char d : digits.toCharArray()) said.add(ONES[d - '0']);
		return String.join(" ", said);
	}

	static String cardinal(long n) {
		if (n == 0) return ONES[0];
		List<String> groups = new ArrayList<>();
		for (int scale = 0; n > 0; scale++, n /= 1000) {
			int group = (int) (n % 1000);
			if (group == 0) continue;
			String said = hundreds(group);
			groups.add(0, scale == 0 ? said : said + " " + SCALES[scale]);
		}
		return String.join(" ", groups);
	}

	private static String hundreds(int n) {
		List<String> said = new ArrayList<>();
		if (n >= 100) {
			said.add(ONES[n / 100] + " hundred");
			n %= 100;
			if (n > 0) said.add("and");
		}
		if (n >= 20) {
			said.add(n % 10 == 0 ? TENS[n / 10] : TENS[n / 10] + " " + ONES[n % 10]);
		} else if (n > 0) {
			said.add(ONES[n]);
		}
		return String.join(" ", said);
	}

	private static final Map<String, String> ORDINALS = Map.of("one", "first", "two", "second", "three", "third",
		"five", "fifth", "eight", "eighth", "nine", "ninth", "twelve", "twelfth");

	private static String ordinal(String cardinal) {
		int space = cardinal.lastIndexOf(' ');
		String last = cardinal.substring(space + 1);
		String head = cardinal.substring(0, space + 1);
		String nth = ORDINALS.getOrDefault(last, last.endsWith("y") ? last.substring(0, last.length() - 1) + "ieth" : last + "th");
		return head + nth;
	}
}
