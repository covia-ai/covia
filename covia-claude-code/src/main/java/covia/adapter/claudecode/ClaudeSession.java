package covia.adapter.claudecode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;

/**
 * One Claude Code process: a conversation (Claude Code session) in a project
 * directory, driven over the CLI's stream-json protocol.
 *
 * <p>The <b>session</b> is durable — Claude Code writes every turn to its own
 * transcript on disk, so a session survives its process and is continued with
 * {@code --resume}. The <b>process</b> is a warm cache: spawned on the first
 * turn, kept alive between turns while {@code keepAlive} is on so the next
 * turn costs only the model call, and stopped by the pool (idle timeout, live
 * cap, explicit {@code claudecode:stop}) or by the process ending. A stopped
 * session is respawned transparently on its next turn.</p>
 *
 * <p>Turns are strictly sequential per session: {@link #submit} queues a
 * prompt and returns a future for that turn's result; progress (tool calls,
 * latest text) is reported through the turn's callback as the stream arrives.
 * A turn cancelled while running kills the process — the transcript keeps
 * what was done so far, and the session remains resumable.</p>
 */
final class ClaudeSession {

	private static final Logger log = LoggerFactory.getLogger(ClaudeSession.class);

	enum State { NEW, IDLE, BUSY, STOPPED }

	// ---- result / progress field names (the operation output shapes)
	static final AString K_SESSION = Fields.SESSION;
	static final AString K_PROJECT = Strings.intern("project");
	static final AString K_RESULT = Fields.RESULT;
	static final AString K_STRUCTURED = Strings.intern("structured");
	static final AString K_SUBTYPE = Strings.intern("subtype");
	static final AString K_IS_ERROR = Fields.IS_ERROR;
	static final AString K_TURNS = Fields.TURNS;
	static final AString K_COST_USD = Strings.intern("costUsd");
	static final AString K_DURATION_MS = Strings.intern("durationMs");
	static final AString K_MODEL = Strings.intern("model");
	static final AString K_STOP_REASON = Strings.intern("stopReason");
	static final AString K_PERMISSION_DENIALS = Strings.intern("permissionDenials");
	static final AString K_TOOL_CALLS = Strings.intern("toolCalls");
	static final AString K_LAST_TOOL = Strings.intern("lastTool");
	static final AString K_TEXT = Fields.TEXT;
	static final AString K_STATE = Strings.intern("state");
	static final AString K_LIVE = Strings.intern("live");
	static final AString K_IDLE_SECONDS = Strings.intern("idleSeconds");
	static final AString K_STARTED_BY = Strings.intern("startedBy");
	static final AString K_PID = Strings.intern("pid");

	// ---- stream-json field names (Claude Code's protocol)
	private static final AString J_TYPE = Fields.TYPE;
	private static final AString J_SUBTYPE = Strings.intern("subtype");
	private static final AString J_SESSION_ID = Strings.intern("session_id");
	private static final AString J_MESSAGE = Fields.MESSAGE;
	private static final AString J_CONTENT = Fields.CONTENT;
	private static final AString J_ROLE = Strings.intern("role");
	private static final AString J_MODEL = Strings.intern("model");
	private static final AString J_NAME = Fields.NAME;
	private static final AString J_INPUT = Fields.INPUT;
	private static final AString J_RESULT = Fields.RESULT;
	private static final AString J_STRUCTURED_OUTPUT = Strings.intern("structured_output");
	private static final AString J_IS_ERROR = Strings.intern("is_error");
	private static final AString J_NUM_TURNS = Strings.intern("num_turns");
	private static final AString J_TOTAL_COST_USD = Strings.intern("total_cost_usd");
	private static final AString J_DURATION_MS = Strings.intern("duration_ms");
	private static final AString J_TERMINAL_REASON = Strings.intern("terminal_reason");
	private static final AString J_PERMISSION_DENIALS = Strings.intern("permission_denials");
	private static final AString[] J_TOOL_DETAIL = {
		Fields.DESCRIPTION, Strings.intern("command"), Strings.intern("file_path"), Strings.intern("pattern"),
		Strings.intern("prompt"), Strings.intern("query"), Strings.intern("url") };

	private static final int MAX_TEXT = 1000;
	private static final int MAX_DETAIL = 160;
	private static final int MAX_STDERR = 4000;
	private static final long KILL_GRACE_MS = 3000;

	/** One queued or running prompt. */
	static final class Turn {
		final String prompt;
		final CompletableFuture<AMap<AString, ACell>> future = new CompletableFuture<>();
		final Consumer<AMap<AString, ACell>> onProgress;
		int toolCalls;
		String lastTool;
		String lastText;
		Turn(String prompt, Consumer<AMap<AString, ACell>> onProgress) {
			this.prompt = prompt;
			this.onProgress = onProgress;
		}
	}

	private final ClaudeCodeAdapter adapter;
	final ProjectSpec project;
	final RunOptions options;
	final AString startedBy;
	final long created = System.currentTimeMillis();

	private volatile String sessionId;
	private volatile String model;
	private volatile State state = State.NEW;
	private volatile long lastUsed = created;
	private volatile int completedTurns;

	private Process process;
	private BufferedWriter stdin;
	private Turn current;
	private final Deque<Turn> queue = new ArrayDeque<>();
	private final StringBuilder stderr = new StringBuilder();
	private final List<Path> tempFiles = new ArrayList<>();

	ClaudeSession(ClaudeCodeAdapter adapter, ProjectSpec project, RunOptions options, String resumeSessionId, AString startedBy) {
		this.adapter = adapter;
		this.project = project;
		this.options = options;
		this.sessionId = resumeSessionId;
		this.startedBy = startedBy;
	}

	// ------------------------------------------------------------------ views

	/** The Claude Code session id — known once the process has announced it (or from the start when resuming). */
	String sessionId() { return sessionId; }
	State state() { return state; }
	boolean isLive() { return state == State.IDLE || state == State.BUSY; }
	boolean isIdle() { return state == State.IDLE; }
	long lastUsed() { return lastUsed; }
	int completedTurns() { return completedTurns; }
	String model() { return model; }

	/** Status record for {@code claudecode:sessions}. */
	AMap<AString, ACell> status() {
		AMap<AString, ACell> m = Maps.of(
			K_PROJECT, Strings.create(project.name()),
			K_STATE, Strings.create(state.name()),
			K_LIVE, CVMBool.create(isLive()),
			K_TURNS, CVMLong.create(completedTurns),
			K_IDLE_SECONDS, CVMLong.create(Math.max(0, (System.currentTimeMillis() - lastUsed) / 1000)));
		if (sessionId != null) m = m.assoc(K_SESSION, Strings.create(sessionId));
		if (model != null) m = m.assoc(K_MODEL, Strings.create(model));
		if (startedBy != null) m = m.assoc(K_STARTED_BY, startedBy);
		Process p = process;
		if (p != null && p.isAlive()) m = m.assoc(K_PID, CVMLong.create(p.pid()));
		return m;
	}

	// ------------------------------------------------------------------ turns

	/**
	 * Queue a prompt. The returned future completes with the turn's result
	 * record (see {@link #resultRecord}) or fails: {@link CancellationException}
	 * when cancelled, otherwise with the process's diagnostic.
	 */
	synchronized CompletableFuture<AMap<AString, ACell>> submit(String prompt, Consumer<AMap<AString, ACell>> onProgress) {
		Turn turn = new Turn(prompt, onProgress);
		queue.add(turn);
		lastUsed = System.currentTimeMillis();
		pump();
		return turn.future;
	}

	/** Cancel a turn: dropped if still queued; the process is killed if it is the running one. */
	synchronized void cancel(CompletableFuture<?> turnFuture) {
		if (current != null && current.future == turnFuture) {
			Turn t = current;
			current = null;
			t.future.cancel(true);
			stopLocked("turn cancelled");
			return;
		}
		queue.removeIf(t -> {
			if (t.future != turnFuture) return false;
			t.future.cancel(true);
			return true;
		});
	}

	/** Start the next queued turn if none is running. Caller holds the lock. */
	private void pump() {
		if (current != null) return;
		Turn next = queue.poll();
		if (next == null) {
			if (process != null && process.isAlive()) state = State.IDLE;
			return;
		}
		current = next;
		state = State.BUSY;
		boolean warm = (process != null && process.isAlive());
		try {
			if (!warm) spawn();
			writeTurn(next);
		} catch (Exception e) {
			// A warm process can die or close its stdin between turns (a slow or
			// loaded host may have reaped it, or it exited on its own). The
			// conversation lives in Claude Code's on-disk session, so discard the
			// dead process and respawn a fresh one for the same session, retrying
			// the turn once — the caller never sees the blip.
			if (warm) {
				log.debug("Claude Code warm process write failed ({}); respawning to resume", e.toString());
				try {
					discardProcess();
					spawn();
					writeTurn(next);
					return;
				} catch (Exception retry) {
					e = retry;
				}
			}
			current = null;
			next.future.completeExceptionally(new RuntimeException("Could not start Claude Code turn: " + AAdapter.describeFailure(e), e));
			stopLocked("start failed");
			// The remaining queue fails too: nothing will drain it.
			failQueue("Claude Code session could not start: " + AAdapter.describeFailure(e));
			adapter.onSlotFreed(this);
		}
	}

	/** Send one prompt to the running process as a stream-json user message. */
	private void writeTurn(Turn next) throws IOException {
		AMap<AString, ACell> msg = Maps.of(
			J_TYPE, Strings.create("user"),
			J_MESSAGE, Maps.of(J_ROLE, Strings.create("user"), J_CONTENT, Strings.create(next.prompt)));
		stdin.write(JSON.print(msg).toString());
		stdin.write('\n');
		stdin.flush();
	}

	/** Forcibly drop the current process for an immediate respawn — the session
	 *  (its on-disk conversation) is untouched, so a resume continues it. */
	private void discardProcess() {
		Process p = process;
		if (p == null) return;
		closeStdin();
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
		process = null;
		stdin = null;
	}

	private void failQueue(String reason) {
		Turn t;
		while ((t = queue.poll()) != null) t.future.completeExceptionally(new RuntimeException(reason));
	}

	// ---------------------------------------------------------------- process

	/** Spawn the CLI in the project directory. Caller holds the lock. */
	private void spawn() throws IOException {
		List<String> command = new ArrayList<>(adapter.command());
		command.add("-p");
		command.add("--verbose");
		command.add("--output-format"); command.add("stream-json");
		command.add("--input-format"); command.add("stream-json");
		if (sessionId != null) { command.add("--resume"); command.add(sessionId); }
		command.addAll(options.flags(this::tempJson));

		ProcessBuilder pb = new ProcessBuilder(command).directory(project.path().toFile());
		Map<String, String> env = pb.environment();
		// A venue started from inside a Claude Code terminal must not look like a nested session.
		env.remove("CLAUDECODE");
		env.putAll(adapter.environment(project));
		env.put("COVIA_VENUE_DID", adapter.engine.getDIDString().toString());
		env.put("COVIA_PROJECT", project.name());
		stderr.setLength(0);
		process = pb.start();
		stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		Process p = process;
		Thread.ofVirtual().name("claudecode-out-" + p.pid()).start(() -> readStdout(p));
		Thread.ofVirtual().name("claudecode-err-" + p.pid()).start(() -> readStderr(p));
		p.onExit().thenRun(() -> onExit(p));
		log.info("Claude Code process {} started in project '{}' ({})", p.pid(), project.name(),
			sessionId == null ? "new session" : "resume " + sessionId);
	}

	private String tempJson(String json) {
		try {
			Path f = Files.createTempFile("covia-claudecode-", ".json");
			Files.writeString(f, json, StandardCharsets.UTF_8);
			tempFiles.add(f);
			return f.toString();
		} catch (IOException e) {
			throw new RuntimeException("Could not write Claude Code option file: " + e.getMessage(), e);
		}
	}

	private void readStdout(Process p) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				try {
					onLine(line);
				} catch (RuntimeException e) {
					log.debug("Claude Code stream line ignored: {}", e.toString());
				}
			}
		} catch (IOException e) {
			// stream closed with the process
		}
	}

	private void readStderr(Process p) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				synchronized (stderr) {
					if (stderr.length() < MAX_STDERR) {
						stderr.append(line).append('\n');
					}
				}
			}
		} catch (IOException e) {
			// stream closed with the process
		}
	}

	private String stderrTail() {
		synchronized (stderr) {
			String s = stderr.toString().trim();
			return s.length() > 600 ? "…" + s.substring(s.length() - 600) : s;
		}
	}

	/** One stream-json event from the process. */
	private void onLine(String line) {
		ACell parsed = JSON.parse(line);
		AMap<AString, ACell> ev = RT.castMap(parsed);
		if (ev == null) return;
		String type = str(ev.get(J_TYPE));
		if (type == null) return;
		switch (type) {
			case "system" -> {
				if ("init".equals(str(ev.get(J_SUBTYPE)))) {
					String sid = str(ev.get(J_SESSION_ID));
					if (sid != null) sessionId = sid;
					String m = str(RT.getIn(ev, J_MODEL));
					if (m != null) model = m;
					adapter.onSessionAnnounced(this);
				}
			}
			case "assistant" -> onAssistant(ev);
			case "result" -> onResult(ev);
			default -> { /* user (tool results), rate_limit_event, thinking_tokens, … */ }
		}
	}

	private void onAssistant(AMap<AString, ACell> ev) {
		Turn t;
		synchronized (this) { t = current; }
		if (t == null) return;
		String sid = str(ev.get(J_SESSION_ID));
		if (sid != null && sessionId == null) sessionId = sid;
		String m = str(RT.getIn(ev, J_MESSAGE, J_MODEL));
		if (m != null) model = m;
		AVector<ACell> content = RT.ensureVector(RT.getIn(ev, J_MESSAGE, J_CONTENT));
		if (content == null) return;
		boolean changed = false;
		for (long i = 0; i < content.count(); i++) {
			AMap<AString, ACell> block = RT.castMap(content.get(i));
			if (block == null) continue;
			String bt = str(block.get(J_TYPE));
			if ("text".equals(bt)) {
				String text = str(block.get(K_TEXT));
				if (text != null && !text.isBlank()) { t.lastText = clip(text, MAX_TEXT); changed = true; }
			} else if ("tool_use".equals(bt)) {
				t.toolCalls++;
				String name = str(block.get(J_NAME));
				String detail = toolDetail(block.get(J_INPUT));
				t.lastTool = (detail == null) ? name : name + ": " + detail;
				changed = true;
			}
		}
		if (changed && t.onProgress != null) t.onProgress.accept(progressRecord(t));
	}

	private static String toolDetail(ACell input) {
		AMap<AString, ACell> in = RT.castMap(input);
		if (in == null) return null;
		for (AString k : J_TOOL_DETAIL) {
			String v = str(in.get(k));
			if (v != null && !v.isBlank()) return clip(v.replace('\n', ' '), MAX_DETAIL);
		}
		return null;
	}

	/** The progress record published on a job while a turn runs. */
	AMap<AString, ACell> progressRecord(Turn t) {
		AMap<AString, ACell> m = Maps.of(
			K_PROJECT, Strings.create(project.name()),
			K_TOOL_CALLS, CVMLong.create(t.toolCalls));
		if (sessionId != null) m = m.assoc(K_SESSION, Strings.create(sessionId));
		if (model != null) m = m.assoc(K_MODEL, Strings.create(model));
		if (t.lastTool != null) m = m.assoc(K_LAST_TOOL, Strings.create(t.lastTool));
		if (t.lastText != null) m = m.assoc(K_TEXT, Strings.create(t.lastText));
		return m;
	}

	private void onResult(AMap<AString, ACell> ev) {
		Turn t;
		boolean keep;
		synchronized (this) {
			t = current;
			current = null;
			completedTurns++;
			lastUsed = System.currentTimeMillis();
			String sid = str(ev.get(J_SESSION_ID));
			if (sid != null) sessionId = sid;
			keep = options.keepAlive();
			if (keep) {
				pump();
			} else {
				// One-shot: closing stdin ends the process after this turn.
				closeStdin();
			}
		}
		if (t != null) t.future.complete(resultRecord(ev));
		adapter.onSlotFreed(this);
	}

	/** The operation result for a completed turn, from Claude Code's {@code result} event. */
	AMap<AString, ACell> resultRecord(AMap<AString, ACell> ev) {
		AMap<AString, ACell> r = Maps.of(
			K_PROJECT, Strings.create(project.name()),
			K_IS_ERROR, CVMBool.create(RT.bool(ev.get(J_IS_ERROR))));
		String sid = str(ev.get(J_SESSION_ID));
		if (sid == null) sid = sessionId;
		if (sid != null) r = r.assoc(K_SESSION, Strings.create(sid));
		String result = str(ev.get(J_RESULT));
		if (result != null) r = r.assoc(K_RESULT, Strings.create(result));
		ACell structured = ev.get(J_STRUCTURED_OUTPUT);
		if (structured != null) r = r.assoc(K_STRUCTURED, structured);
		String subtype = str(ev.get(J_SUBTYPE));
		if (subtype != null) r = r.assoc(K_SUBTYPE, Strings.create(subtype));
		CVMLong turns = RT.ensureLong(ev.get(J_NUM_TURNS));
		if (turns != null) r = r.assoc(K_TURNS, turns);
		CVMDouble cost = RT.castDouble(ev.get(J_TOTAL_COST_USD));
		if (cost != null) r = r.assoc(K_COST_USD, cost);
		CVMLong duration = RT.ensureLong(ev.get(J_DURATION_MS));
		if (duration != null) r = r.assoc(K_DURATION_MS, duration);
		String reason = str(ev.get(J_TERMINAL_REASON));
		if (reason != null) r = r.assoc(K_STOP_REASON, Strings.create(reason));
		if (model != null) r = r.assoc(K_MODEL, Strings.create(model));
		AVector<ACell> denials = RT.ensureVector(ev.get(J_PERMISSION_DENIALS));
		r = r.assoc(K_PERMISSION_DENIALS, denials == null ? Vectors.empty() : denials);
		return r;
	}

	private void closeStdin() {
		try {
			if (stdin != null) stdin.close();
		} catch (IOException e) {
			// process is going away regardless
		}
	}

	private void onExit(Process p) {
		Turn failed;
		int code = p.exitValue();
		synchronized (this) {
			if (process != p) return;    // a later spawn replaced it
			process = null;
			stdin = null;
			cleanupTemp();               // the dead process's option files
			failed = current;
			current = null;
			state = State.STOPPED;
			String tail = stderrTail();
			if (failed != null && !failed.future.isDone()) {
				failed.future.completeExceptionally(new RuntimeException(
					"Claude Code exited (code " + code + ") before finishing the turn"
					+ (tail.isEmpty() ? "" : ": " + tail)
					+ (sessionId != null ? " [session " + sessionId + "]" : "")));
			}
			// Anything still queued can run on a fresh process.
			if (!queue.isEmpty()) {
				log.info("Claude Code process {} ended with {} turn(s) queued; respawning", p.pid(), queue.size());
				state = State.NEW;
				pump();
				if (state != State.STOPPED) return;
			}
		}
		if (code != 0 && failed == null) {
			log.info("Claude Code process {} ended (code {}) in project '{}'", p.pid(), code, project.name());
		} else {
			log.debug("Claude Code process {} ended (code {})", p.pid(), code);
		}
		adapter.onSlotFreed(this);
	}

	private void cleanupTemp() {
		for (Path f : tempFiles) {
			try { Files.deleteIfExists(f); } catch (IOException ignored) { }
		}
		tempFiles.clear();
	}

	/** Stop the process (the session stays resumable). Idempotent. */
	synchronized void stop(String reason) {
		stopLocked(reason);
	}

	private void stopLocked(String reason) {
		Process p = process;
		if (p == null) {
			if (state != State.STOPPED) {
				state = State.STOPPED;
				failQueue("Claude Code session stopped: " + reason);
			}
			return;
		}
		log.info("Stopping Claude Code process {} in project '{}': {}", p.pid(), project.name(), reason);
		if (current != null && !current.future.isDone()) {
			current.future.completeExceptionally(new RuntimeException("Claude Code session stopped: " + reason));
			current = null;
		}
		failQueue("Claude Code session stopped: " + reason);
		state = State.STOPPED;
		closeStdin();
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroy();
		CompletableFuture.delayedExecutor(KILL_GRACE_MS, TimeUnit.MILLISECONDS).execute(() -> {
			if (p.isAlive()) p.destroyForcibly();
		});
	}

	// ---------------------------------------------------------------- helpers

	private static String str(ACell c) {
		return (c instanceof AString s) ? s.toString() : null;
	}

	private static String clip(String s, int max) {
		return s.length() > max ? s.substring(0, max) + "…" : s;
	}

	@Override
	public String toString() {
		return "ClaudeSession[" + project.name() + " " + (sessionId == null ? "(new)" : sessionId) + " " + state + "]";
	}
}
