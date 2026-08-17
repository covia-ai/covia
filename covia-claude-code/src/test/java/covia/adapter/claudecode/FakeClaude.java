package covia.adapter.claudecode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A stand-in for the {@code claude} CLI, speaking the subset of the
 * headless stream-json protocol the adapter relies on: an {@code init}
 * event, {@code assistant} events (text and tool_use blocks), and one
 * {@code result} event per user message on stdin; exit on stdin EOF.
 *
 * <p>Sessions persist like the real thing: the turn count of every session
 * is kept in a file under {@code FAKE_CLAUDE_STATE} (or the temp dir), so a
 * {@code --resume} on a fresh process continues counting — which is what
 * makes resume-after-stop testable.</p>
 *
 * <p>Behaviour is chosen by the prompt's first word:</p>
 * <ul>
 *   <li>{@code echo …} (default) — replies {@code echo: <prompt>}</li>
 *   <li>{@code count} — replies the session's total turn count</li>
 *   <li>{@code sleep <ms>} — waits, then replies</li>
 *   <li>{@code tool [<ms>]} — a tool_use block (Bash ls), optional wait, then a reply</li>
 *   <li>{@code fail} — an {@code is_error} result ({@code error_during_execution})</li>
 *   <li>{@code maxturns} — an {@code is_error} result ({@code error_max_turns}, no text)</li>
 *   <li>{@code crash} — writes to stderr and exits 3 without a result</li>
 *   <li>{@code args} — replies the process arguments</li>
 *   <li>{@code cwd} — replies the working directory</li>
 *   <li>{@code env <NAME>} — replies that environment variable</li>
 *   <li>{@code structured} — a success with {@code structured_output: {"answer": 5}}</li>
 * </ul>
 */
public final class FakeClaude {

	private FakeClaude() {}

	public static void main(String[] args) throws Exception {
		List<String> argv = List.of(args);
		String resume = null;
		String model = "fake-model";
		for (int i = 0; i < args.length; i++) {
			if ("--resume".equals(args[i]) && i + 1 < args.length) resume = args[i + 1];
			if ("--model".equals(args[i]) && i + 1 < args.length) model = args[i + 1];
		}
		String sessionId = (resume != null) ? resume : UUID.randomUUID().toString();
		Path stateDir = Path.of(System.getenv().getOrDefault("FAKE_CLAUDE_STATE", System.getProperty("java.io.tmpdir")));
		Files.createDirectories(stateDir);
		Path counter = stateDir.resolve("fake-claude-" + sessionId + ".count");
		if (resume != null && !Files.exists(counter)) {
			System.err.println("No conversation found with session ID: " + resume);
			System.exit(1);
		}
		String cwd = Path.of("").toAbsolutePath().toString();

		PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
		out.println("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":" + q(cwd) + ",\"session_id\":" + q(sessionId)
			+ ",\"tools\":[\"Bash\",\"Read\",\"Edit\"],\"model\":" + q(model) + ",\"permissionMode\":\"default\"}");

		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			if (line.isBlank()) continue;
			String prompt = extractContent(line);
			if (prompt == null) continue;
			int turns = bump(counter);
			long t0 = System.currentTimeMillis();
			String[] words = prompt.trim().split("\\s+", 3);
			String verb = words[0];
			String reply;
			switch (verb) {
				case "count" -> reply = "count: " + turns;
				case "sleep" -> {
					Thread.sleep(Long.parseLong(words[1]));
					reply = "slept " + words[1];
				}
				case "tool" -> {
					out.println(assistant(sessionId, model, "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls -la\",\"description\":\"List files\"}}"));
					if (words.length > 1) Thread.sleep(Long.parseLong(words[1]));
					out.println("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"toolu_1\",\"type\":\"tool_result\",\"content\":\"file.txt\"}]},\"session_id\":" + q(sessionId) + "}");
					reply = "used a tool";
				}
				case "fail" -> {
					out.println(result(sessionId, true, "error_during_execution", "boom: something went wrong", null, turns, t0));
					continue;
				}
				case "maxturns" -> {
					out.println(result(sessionId, true, "error_max_turns", null, null, turns, t0));
					continue;
				}
				case "crash" -> {
					System.err.println("fake claude: simulated crash");
					System.err.flush();
					System.exit(3);
					return;
				}
				case "args" -> reply = "args: " + String.join(" ", argv);
				case "cwd" -> reply = "cwd: " + cwd;
				case "env" -> reply = "env: " + System.getenv(words[1]);
				case "structured" -> {
					out.println(assistant(sessionId, model, "{\"type\":\"text\",\"text\":\"{\\\"answer\\\":5}\"}"));
					out.println(result(sessionId, false, "success", "{\"answer\":5}", "{\"answer\":5}", turns, t0));
					continue;
				}
				case "echo" -> reply = "echo: " + (words.length > 1 ? words[1] + (words.length > 2 ? " " + words[2] : "") : "");
				default -> reply = "echo: " + prompt;
			}
			out.println(assistant(sessionId, model, "{\"type\":\"text\",\"text\":" + q(reply) + "}"));
			out.println(result(sessionId, false, "success", reply, null, turns, t0));
		}
		System.exit(0);
	}

	private static int bump(Path counter) throws IOException {
		int n = 0;
		if (Files.exists(counter)) n = Integer.parseInt(Files.readString(counter).trim());
		n++;
		Files.writeString(counter, Integer.toString(n));
		return n;
	}

	private static String assistant(String sessionId, String model, String block) {
		return "{\"type\":\"assistant\",\"message\":{\"model\":" + q(model) + ",\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
			+ "\"content\":[" + block + "],\"stop_reason\":null},\"parent_tool_use_id\":null,\"session_id\":" + q(sessionId) + "}";
	}

	private static String result(String sessionId, boolean isError, String subtype, String text, String structured, int turns, long t0) {
		long dur = System.currentTimeMillis() - t0;
		StringBuilder sb = new StringBuilder("{\"type\":\"result\",\"subtype\":").append(q(subtype))
			.append(",\"is_error\":").append(isError)
			.append(",\"duration_ms\":").append(dur)
			.append(",\"num_turns\":").append(turns)
			.append(",\"session_id\":").append(q(sessionId))
			.append(",\"total_cost_usd\":0.0042")
			.append(",\"terminal_reason\":").append(q(isError ? subtype.replace("error_", "") : "completed"))
			.append(",\"permission_denials\":[]");
		if (text != null) sb.append(",\"result\":").append(q(text));
		if (structured != null) sb.append(",\"structured_output\":").append(structured);
		return sb.append("}").toString();
	}

	/** JSON string literal. */
	static String q(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		return sb.append('"').toString();
	}

	/** The {@code "content":"…"} string of a user message line (minimal JSON string decoding). */
	static String extractContent(String line) {
		int i = line.indexOf("\"content\":\"");
		if (i < 0) return null;
		i += "\"content\":\"".length();
		StringBuilder sb = new StringBuilder();
		while (i < line.length()) {
			char c = line.charAt(i++);
			if (c == '"') break;
			if (c == '\\' && i < line.length()) {
				char e = line.charAt(i++);
				switch (e) {
					case 'n' -> sb.append('\n');
					case 'r' -> sb.append('\r');
					case 't' -> sb.append('\t');
					case 'u' -> { sb.append((char) Integer.parseInt(line.substring(i, i + 4), 16)); i += 4; }
					default -> sb.append(e);
				}
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** The argv prefix that runs this fake as the {@code claude} command, for adapter configuration. */
	public static List<String> command() {
		String exe = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
		Path java = Path.of(System.getProperty("java.home"), "bin", exe);
		Path classes = classesDir();
		List<String> cmd = new ArrayList<>();
		cmd.add(java.toString());
		cmd.add("-cp");
		cmd.add(classes.toString());
		cmd.add(FakeClaude.class.getName());
		return cmd;
	}

	static Path classesDir() {
		try {
			return Path.of(FakeClaude.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
