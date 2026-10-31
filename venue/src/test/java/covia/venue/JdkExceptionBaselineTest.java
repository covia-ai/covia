package covia.venue;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Expected failures are Covia exception types (#644). Main code may throw
 * {@code IllegalArgumentException}, {@code IllegalStateException} or
 * {@code UnsupportedOperationException} only where the baseline records it:
 * the sites not yet migrated, and in the end the deliberate contract checks.
 * A file may not gain a site, and a file that loses one must lower its count,
 * so the baseline only shrinks.
 *
 * <p>After migrating sites, rewrite the baseline with
 * {@code -Dcovia.jdkExceptionBaseline=update} and commit it with the change.</p>
 */
public class JdkExceptionBaselineTest {

	/** Modules scanned, relative to the repository root. */
	private static final List<String> MODULES = List.of("covia-core", "venue");

	private static final Pattern THROWN = Pattern.compile(
		"new\\s+(IllegalArgumentException|IllegalStateException|UnsupportedOperationException)\\s*\\(");

	private static final Path BASELINE = Path.of("src/test/resources/jdk-exception-baseline.txt");

	@Test
	public void testJdkExceptionsOnlyShrink() throws IOException {
		Map<String, Integer> found = scan(Path.of("").toAbsolutePath().getParent());
		if ("update".equals(System.getProperty("covia.jdkExceptionBaseline"))) {
			Files.writeString(BASELINE, render(found), StandardCharsets.UTF_8);
			return;
		}
		Map<String, Integer> baseline = parse(Files.readString(BASELINE, StandardCharsets.UTF_8));
		List<String> problems = new ArrayList<>();
		for (var entry : found.entrySet()) {
			int allowed = baseline.getOrDefault(entry.getKey(), 0);
			if (entry.getValue() > allowed) {
				problems.add(entry.getKey() + ": " + entry.getValue() + " sites, baseline " + allowed
					+ " — throw a Covia exception type instead");
			}
		}
		for (var entry : baseline.entrySet()) {
			int now = found.getOrDefault(entry.getKey(), 0);
			if (now < entry.getValue()) {
				problems.add(entry.getKey() + ": " + now + " sites, baseline " + entry.getValue()
					+ " — lower the baseline");
			}
		}
		if (!problems.isEmpty()) {
			fail("JDK exception sites differ from " + BASELINE + " (#644):\n  "
				+ String.join("\n  ", problems)
				+ "\nRewrite it with -Dcovia.jdkExceptionBaseline=update once only shrinking remains.");
		}
	}

	private static Map<String, Integer> scan(Path root) throws IOException {
		Map<String, Integer> counts = new TreeMap<>();
		for (String module : MODULES) {
			Path sources = root.resolve(module).resolve("src/main/java");
			try (Stream<Path> files = Files.walk(sources)) {
				for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
					Matcher m = THROWN.matcher(Files.readString(file, StandardCharsets.UTF_8));
					int n = 0;
					while (m.find()) n++;
					if (n > 0) counts.put(root.relativize(file).toString().replace('\\', '/'), n);
				}
			}
		}
		return counts;
	}

	private static Map<String, Integer> parse(String text) {
		Map<String, Integer> counts = new TreeMap<>();
		for (String line : text.split("\n")) {
			line = line.strip();
			if (line.isEmpty() || line.startsWith("#")) continue;
			int space = line.lastIndexOf(' ');
			counts.put(line.substring(0, space), Integer.parseInt(line.substring(space + 1)));
		}
		return counts;
	}

	private static String render(Map<String, Integer> counts) {
		StringBuilder sb = new StringBuilder(
			"# JDK exception sites per file, which may only shrink (#644; JdkExceptionBaselineTest)\n");
		counts.forEach((file, n) -> sb.append(file).append(' ').append(n).append('\n'));
		return sb.toString();
	}
}
