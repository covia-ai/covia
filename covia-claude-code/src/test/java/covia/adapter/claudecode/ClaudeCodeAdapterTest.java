package covia.adapter.claudecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Functional tests for the Claude Code adapter against a REAL venue engine
 * and {@link FakeClaude} — a real subprocess speaking the CLI's stream-json
 * protocol (init, assistant, result; sessions persisted on disk so
 * {@code --resume} continues them). Real jobs, real cancellation, real
 * process lifecycle; no mocks of our own code.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "covia.tests.integration", matches = "true",
	disabledReason = "drives real Claude Code CLI subprocesses (timing/environment-sensitive); "
		+ "enable with -Dcovia.tests.integration=true")
@Execution(ExecutionMode.SAME_THREAD)
public class ClaudeCodeAdapterTest {

	private static final AString OWNER = Strings.create("did:test:claudecode:owner");
	private static final AString OTHER = Strings.create("did:test:claudecode:other");
	private static final RequestContext OWNER_CTX = RequestContext.of(OWNER);
	private static final RequestContext OTHER_CTX = RequestContext.of(OTHER);

	private static Engine engine;
	private static ClaudeCodeAdapter adapter;
	private static Path alphaDir, betaDir, gammaDir, bypassDir, stateDir;
	private static AMap<AString, ACell> baseConfig;

	@BeforeAll
	static void boot() throws Exception {
		Path root = Files.createTempDirectory("covia-claudecode-test");
		alphaDir = Files.createDirectories(root.resolve("alpha"));
		betaDir = Files.createDirectories(root.resolve("beta"));
		gammaDir = Files.createDirectories(root.resolve("gamma"));
		bypassDir = Files.createDirectories(root.resolve("bypass"));
		stateDir = Files.createDirectories(root.resolve("state"));

		baseConfig = Maps.of(
			ClaudeCodeAdapter.K_COMMAND, RT.cvm(FakeClaude.command()),
			ClaudeCodeAdapter.K_ENV, Maps.of(
				"FAKE_CLAUDE_STATE", stateDir.toString(),
				"FAKE_VAR", "hello",
				"SECRET_VAR", "s/FAKE_SECRET"),
			ClaudeCodeAdapter.K_MAX_SESSIONS, 4,
			ClaudeCodeAdapter.K_IDLE_SECONDS, 0,
			ClaudeCodeAdapter.K_PROJECTS, Maps.of(
				"alpha", Maps.of("path", alphaDir.toString(), "user", OWNER.toString(), "description", "Alpha project"),
				"beta", Maps.of("path", betaDir.toString(), "user", OWNER.toString(),
					"options", Maps.of("model", "haiku", "allowedTools", Vectors.of("Read", "Edit"),
						"maxTurns", 7, "keepAlive", false)),
				"gamma", Maps.of("path", gammaDir.toString(), "user", OTHER.toString()),
				"bypass", Maps.of("path", bypassDir.toString(), "user", OWNER.toString(),
					"options", Maps.of("permissionMode", "bypassPermissions"))));
		AMap<AString, ACell> config = Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of(Strings.create(ClaudeCodeAdapter.NAME), baseConfig));
		engine = Engine.createTemp(config);
		adapter = new ClaudeCodeAdapter();
		engine.registerAdapter(adapter);
		Engine.addDemoAssets(engine);

		// A venue secret the adapter env refers to.
		run(engine.venueContext(), "v/ops/secret/set", Maps.of("name", "FAKE_SECRET", "value", "s3cret"));
	}

	@AfterAll
	static void shutdown() {
		engine.close();
	}

	// ------------------------------------------------------------ config parsing

	@Test
	public void testProjectSpecValidation() {
		assertThrows(IllegalArgumentException.class, () -> ProjectSpec.parse("x", Maps.of("user", "did:test:a"), true,
			ProjectSpec.Managed.CONFIG, "venue"), "path required");
		assertThrows(IllegalArgumentException.class, () -> ProjectSpec.parse("x", Maps.of("path", alphaDir.resolve("nope").toString()),
			true, ProjectSpec.Managed.CONFIG, "venue"), "directory must exist");
		assertThrows(IllegalArgumentException.class, () -> ProjectSpec.parse("bad name", Maps.of("path", alphaDir.toString()),
			true, ProjectSpec.Managed.CONFIG, "venue"), "name charset");
		assertThrows(IllegalArgumentException.class, () -> ProjectSpec.parse("x", Maps.of("path", alphaDir.toString(), "user", "alice"),
			true, ProjectSpec.Managed.CONFIG, "venue"), "user must be a DID/public/venue");
		assertThrows(IllegalArgumentException.class, () -> ProjectSpec.parse("x", Maps.of("path", alphaDir.toString(), "colour", "red"),
			true, ProjectSpec.Managed.CONFIG, "venue"), "unknown key in strict mode");
		assertThrows(IllegalArgumentException.class, () -> ProjectSpec.parse("x",
			Maps.of("path", alphaDir.toString(), "options", Maps.of("permissionMode", "yolo")),
			true, ProjectSpec.Managed.CONFIG, "venue"), "permission mode enum");
		ProjectSpec plain = ProjectSpec.parse("x", Strings.create(alphaDir.toString()), true, ProjectSpec.Managed.CONFIG, "venue");
		assertEquals("venue", plain.userRef());
		assertEquals(engine.getDIDString(), plain.userDID(engine));
		assertEquals(alphaDir.toAbsolutePath().normalize(), plain.path());
	}

	@Test
	public void testRunOptionsValidation() {
		assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(Maps.of("maxTurns", 0), RunOptions.ALL_KEYS, "t", true));
		assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(Maps.of("effort", "turbo"), RunOptions.ALL_KEYS, "t", true));
		assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(Maps.of("addDirs", "/x"), RunOptions.CALL_KEYS, "t", true),
			"addDirs is not a call option");
		assertThrows(IllegalArgumentException.class, () -> RunOptions.parse(Maps.of("nonsense", 1), RunOptions.ALL_KEYS, "t", true));
		RunOptions o = RunOptions.parse(Maps.of("model", "opus", "allowedTools", "Read", "maxBudgetUsd", 2,
			"jsonSchema", "{\"type\":\"object\"}", "env", Maps.of("A", "b")), RunOptions.ALL_KEYS, "t", true);
		List<String> flags = o.flags(json -> "FILE");
		assertTrue(flags.containsAll(List.of("--model", "opus", "--allowedTools", "Read", "--max-budget-usd", "2.0", "--json-schema")), flags.toString());
		assertEquals("b", o.env().get("A"));
		assertNull(o.publicView().get(RunOptions.ENV), "env is not public");
		RunOptions merged = RunOptions.parse(Maps.of("model", "haiku"), RunOptions.ALL_KEYS, "t", true).overlay(o);
		assertEquals("opus", merged.string(RunOptions.MODEL));
		assertTrue(RunOptions.parse(Maps.of("mcpConfig", Maps.of("mcpServers", Maps.empty())), RunOptions.ALL_KEYS, "t", true)
			.flags(json -> "TMPFILE").containsAll(List.of("--mcp-config", "TMPFILE")), "JSON mcpConfig goes through a file");
	}

	@Test
	public void testAdapterConfigValidation() {
		ClaudeCodeAdapter a = new ClaudeCodeAdapter();
		assertThrows(IllegalArgumentException.class, () -> a.configure(Maps.of("maxSessions", 0), true));
		assertThrows(IllegalArgumentException.class, () -> a.configure(Maps.of("command", Vectors.empty()), true));
		assertThrows(IllegalArgumentException.class, () -> a.configure(Maps.of("bogus", 1), true));
		assertTrue(a.configure(Maps.of("bogus", 1), false), "lenient mode ignores unknown keys");
		assertTrue(a.configure(Maps.of("command", "claude", "defaults", Maps.of("model", "sonnet")), true));
		assertEquals(List.of("claude"), a.command());
	}

	// ------------------------------------------------------------------ running

	@Test
	public void testRunEchoAndResultShape() {
		AMap<AString, ACell> res = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "hello world"));
		assertEquals("echo: hello world", str(res, "result"));
		assertEquals("alpha", str(res, "project"));
		assertNotNull(str(res, "session"));
		assertEquals(CVMBool.FALSE, res.get(ClaudeSession.K_IS_ERROR));
		assertEquals("success", str(res, "subtype"));
		assertEquals("fake-model", str(res, "model"));
		assertNotNull(res.get(ClaudeSession.K_COST_USD));
		assertNotNull(res.get(ClaudeSession.K_TURNS));
		assertTrue(res.get(ClaudeSession.K_PERMISSION_DENIALS) instanceof AVector);
	}

	@Test
	public void testResumeKeepsProcessWarmAndConversation() {
		AMap<AString, ACell> first = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "count"));
		String session = str(first, "session");
		assertEquals("count: 1", str(first, "result"));

		// The process is still live and idle after the turn (keepAlive default).
		AMap<AString, ACell> status = sessionStatus(OWNER_CTX, session);
		assertNotNull(status, "session listed");
		assertEquals(CVMBool.TRUE, status.get(ClaudeSession.K_LIVE));
		assertEquals("IDLE", str(status, "state"));
		long pid = ((CVMLong) status.get(ClaudeSession.K_PID)).longValue();

		// Second turn on the same session: same process, conversation continues.
		AMap<AString, ACell> second = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("session", session, "prompt", "count"));
		assertEquals(session, str(second, "session"));
		assertEquals("count: 2", str(second, "result"));
		assertEquals(pid, ((CVMLong) sessionStatus(OWNER_CTX, session).get(ClaudeSession.K_PID)).longValue(), "same warm process");

		// project is optional when the session is known — but a wrong project is refused.
		assertThrows(IllegalArgumentException.class, () -> engine.jobs().invokeOperation("v/ops/claudecode/run",
			Maps.of("session", "not-a-known-session-id", "prompt", "count"), OWNER_CTX));

		// Stop the process explicitly; the session survives and resumes on a fresh process.
		AMap<AString, ACell> stopped = run(OWNER_CTX, "v/ops/claudecode/stop", Maps.of("session", session));
		assertEquals(CVMBool.TRUE, stopped.get(ClaudeCodeAdapter.K_STOPPED));
		await(() -> !RT.bool(sessionStatus(OWNER_CTX, session).get(ClaudeSession.K_LIVE)), 5000);
		AMap<AString, ACell> third = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("session", session, "prompt", "count"));
		assertEquals("count: 3", str(third, "result"), "resumed from disk");
		assertNotEquals(pid, ((CVMLong) sessionStatus(OWNER_CTX, session).get(ClaudeSession.K_PID)).longValue(), "new process");
	}

	@Test
	public void testOneShotProjectExitsAfterTurnAndStillResumes() {
		AMap<AString, ACell> res = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "beta", "prompt", "count"));
		String session = str(res, "session");
		await(() -> !RT.bool(sessionStatus(OWNER_CTX, session).get(ClaudeSession.K_LIVE)), 5000);
		AMap<AString, ACell> again = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("session", session, "prompt", "count"));
		assertEquals("count: 2", str(again, "result"));
	}

	@Test
	public void testFlagsFromProjectAndCallOptions() {
		AMap<AString, ACell> res = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "beta", "prompt", "args",
			"appendSystemPrompt", "be terse", "options", Maps.of("effort", "low")));
		String args = str(res, "result");
		for (String expected : List.of("-p", "--verbose", "--output-format stream-json", "--input-format stream-json",
				"--model haiku", "--allowedTools Read Edit", "--max-turns 7", "--append-system-prompt be terse", "--effort low")) {
			assertTrue(args.contains(expected), expected + " in " + args);
		}
		assertFalse(args.contains("--resume"), "fresh session");
	}

	@Test
	public void testCwdAndEnvironment() {
		assertEquals("cwd: " + alphaDir.toAbsolutePath().normalize(),
			str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "cwd")), "result"));
		assertEquals("env: hello", str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "env FAKE_VAR")), "result"));
		assertEquals("env: s3cret", str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "env SECRET_VAR")), "result"),
			"s/ references resolve to venue secrets");
		assertEquals("env: alpha", str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "env COVIA_PROJECT")), "result"));
	}

	@Test
	public void testStructuredOutput() {
		AMap<AString, ACell> res = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "structured",
			"jsonSchema", Maps.of("type", "object")));
		assertEquals(CVMLong.create(5), RT.getIn(res, "structured", "answer"));
		assertEquals("{\"answer\":5}", str(res, "result"));
	}

	@Test
	public void testProgressPublishedWhileRunning() {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "tool 1500"), OWNER_CTX);
		await(() -> job.getData().get(ClaudeCodeAdapter.K_PROGRESS) != null, 5000);
		AMap<AString, ACell> progress = RT.castMap(job.getData().get(ClaudeCodeAdapter.K_PROGRESS));
		assertEquals(CVMLong.create(1), progress.get(ClaudeSession.K_TOOL_CALLS));
		assertEquals("Bash: List files", str(progress, "lastTool"));
		assertNotNull(str(progress, "session"));
		AMap<AString, ACell> res = RT.castMap(job.awaitResult(30_000));
		assertEquals(Status.COMPLETE, job.getStatus());
		assertEquals("used a tool", str(res, "result"));
		assertNull(job.getData().get(ClaudeCodeAdapter.K_PROGRESS), "progress cleared on completion");
	}

	@Test
	public void testCancelKillsTheProcess() {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "sleep 20000"), OWNER_CTX);
		assertEquals(Status.STARTED, job.getStatus());
		// Find the busy session and make sure it is really running.
		ClaudeSession busy = null;
		await(() -> adapter.knownSessions().stream().anyMatch(s -> s.state() == ClaudeSession.State.BUSY), 5000);
		for (ClaudeSession s : adapter.knownSessions()) if (s.state() == ClaudeSession.State.BUSY) busy = s;
		assertNotNull(busy);
		job.cancel();
		assertEquals(Status.CANCELLED, job.getStatus());
		ClaudeSession victim = busy;
		await(() -> victim.state() == ClaudeSession.State.STOPPED, 5000);
	}

	@Test
	public void testErrorResultFailsJobWithResumableSession() {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "fail"), OWNER_CTX);
		assertThrows(RuntimeException.class, () -> job.awaitResult(30_000));
		assertEquals(Status.FAILED, job.getStatus());
		String err = job.getErrorMessage();
		assertTrue(err.contains("error_during_execution"), err);
		assertTrue(err.contains("boom"), err);
		assertTrue(err.contains("session "), err);

		Job max = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "maxturns"), OWNER_CTX);
		assertThrows(RuntimeException.class, () -> max.awaitResult(30_000));
		assertTrue(max.getErrorMessage().contains("error_max_turns"), max.getErrorMessage());
	}

	@Test
	public void testProcessCrashFailsJobWithStderr() {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "crash"), OWNER_CTX);
		assertThrows(RuntimeException.class, () -> job.awaitResult(30_000));
		assertEquals(Status.FAILED, job.getStatus());
		String err = job.getErrorMessage();
		assertTrue(err.contains("code 3"), err);
		assertTrue(err.contains("simulated crash"), err);
	}

	@Test
	public void testUnknownSessionOnResumeIsReported() {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/run",
			Maps.of("project", "alpha", "session", "00000000-dead-beef-0000-000000000000", "prompt", "count"), OWNER_CTX);
		assertThrows(RuntimeException.class, () -> job.awaitResult(30_000));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains("No conversation found"), job.getErrorMessage());
	}

	// ----------------------------------------------------------------- the pool

	@Test
	public void testLiveCapReapsIdleSessionsAndQueuesBusyOnes() throws Exception {
		stopAll();
		AMap<AString, ACell> tight = baseConfig.assoc(ClaudeCodeAdapter.K_MAX_SESSIONS, CVMLong.create(1));
		assertTrue(adapter.configure(tight, true));
		try {
			AMap<AString, ACell> a = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "count"));
			String sa = str(a, "session");
			assertTrue(RT.bool(sessionStatus(OWNER_CTX, sa).get(ClaudeSession.K_LIVE)));
			// A second session must evict the idle first one.
			AMap<AString, ACell> b = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "count"));
			String sb = str(b, "session");
			assertNotEquals(sa, sb);
			await(() -> !RT.bool(sessionStatus(OWNER_CTX, sa).get(ClaudeSession.K_LIVE)), 5000);
			assertTrue(RT.bool(sessionStatus(OWNER_CTX, sb).get(ClaudeSession.K_LIVE)));
			assertEquals(1, liveTotal());

			// While b is busy, a third session waits for the slot rather than exceeding the cap.
			Job busy = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("session", sb, "prompt", "sleep 1500"), OWNER_CTX);
			await(() -> adapter.knownSessions().stream().anyMatch(s -> s.state() == ClaudeSession.State.BUSY), 5000);
			long t0 = System.currentTimeMillis();
			Job third = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "count"), OWNER_CTX);
			Thread.sleep(300);
			assertEquals(Status.STARTED, third.getStatus());
			assertEquals(1, liveTotal(), "cap holds while waiting");
			busy.awaitResult(30_000);
			third.awaitResult(30_000);
			assertEquals(Status.COMPLETE, third.getStatus(), third.getErrorMessage());
			assertTrue(System.currentTimeMillis() - t0 >= 1000, "third waited for the busy slot");
			assertTrue(liveTotal() <= 1);
		} finally {
			assertTrue(adapter.configure(baseConfig, true));
		}
	}

	@Test
	public void testIdleReaper() throws Exception {
		AMap<AString, ACell> res = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "count"));
		String session = str(res, "session");
		assertTrue(RT.bool(sessionStatus(OWNER_CTX, session).get(ClaudeSession.K_LIVE)));
		adapter.reapForTest();
		assertTrue(RT.bool(sessionStatus(OWNER_CTX, session).get(ClaudeSession.K_LIVE)), "idleSeconds 0 = never reap");
		AMap<AString, ACell> quick = baseConfig.assoc(ClaudeCodeAdapter.K_IDLE_SECONDS, CVMLong.create(1));
		assertTrue(adapter.configure(quick, true));
		try {
			Thread.sleep(1200);
			adapter.reapForTest();
			await(() -> !RT.bool(sessionStatus(OWNER_CTX, session).get(ClaudeSession.K_LIVE)), 5000);
			// …and it resumes transparently.
			assertEquals("count: 2", str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("session", session, "prompt", "count")), "result"));
		} finally {
			assertTrue(adapter.configure(baseConfig, true));
		}
	}

	// ------------------------------------------------------------ authorisation

	@Test
	public void testProjectAccessIsGated() {
		// OTHER owns gamma only; alpha is OWNER's.
		Job denied = engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "hi"), OTHER_CTX);
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("claudecode/run"), denied.getErrorMessage());
		assertEquals("echo: hi", str(run(OTHER_CTX, "v/ops/claudecode/run", Maps.of("project", "gamma", "prompt", "hi")), "result"));
		// With exactly one accessible project it need not be named.
		assertEquals("gamma", str(run(OTHER_CTX, "v/ops/claudecode/run", Maps.of("prompt", "hi")), "project"));
		// OWNER has several: must name one.
		assertThrows(IllegalArgumentException.class, () -> engine.jobs().invokeOperation("v/ops/claudecode/run", Maps.of("prompt", "hi"), OWNER_CTX));

		AVector<ACell> mine = RT.ensureVector(run(OTHER_CTX, "v/ops/claudecode/projects", Maps.empty()).get(ClaudeCodeAdapter.K_PROJECTS));
		assertEquals(1, mine.count());
		assertEquals("gamma", str(RT.castMap(mine.get(0)), "name"));
		AVector<ACell> owners = RT.ensureVector(run(OWNER_CTX, "v/ops/claudecode/projects", Maps.empty()).get(ClaudeCodeAdapter.K_PROJECTS));
		assertTrue(owners.count() >= 3);

		// Sessions of another user's project are invisible; stopping them is refused.
		AMap<AString, ACell> res = run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "hi"));
		String session = str(res, "session");
		assertNull(sessionStatus(OTHER_CTX, session));
		Job stop = engine.jobs().invokeOperation("v/ops/claudecode/stop", Maps.of("session", session), OTHER_CTX);
		assertThrows(RuntimeException.class, () -> stop.awaitResult(10_000));
		assertEquals(Status.FAILED, stop.getStatus());
	}

	@Test
	public void testBypassPermissionsOnlyWhereConfigured() {
		Job denied = engine.jobs().invokeOperation("v/ops/claudecode/run",
			Maps.of("project", "alpha", "prompt", "args", "permissionMode", "bypassPermissions"), OWNER_CTX);
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("bypassPermissions"), denied.getErrorMessage());
		String args = str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "bypass", "prompt", "args", "permissionMode", "bypassPermissions")), "result");
		assertTrue(args.contains("--permission-mode bypassPermissions"), args);
		String plan = str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "alpha", "prompt", "args", "permissionMode", "plan")), "result");
		assertTrue(plan.contains("--permission-mode plan"), plan);
	}

	@Test
	public void testCallOptionsCannotSetOperatorSettings() {
		assertThrows(IllegalArgumentException.class, () -> engine.jobs().invokeOperation("v/ops/claudecode/run",
			Maps.of("project", "alpha", "prompt", "hi", "options", Maps.of("addDirs", Vectors.of("/etc"))), OWNER_CTX));
		assertThrows(IllegalArgumentException.class, () -> engine.jobs().invokeOperation("v/ops/claudecode/run",
			Maps.of("project", "alpha", "prompt", "hi", "options", Maps.of("env", Maps.of("X", "y"))), OWNER_CTX));
	}

	// -------------------------------------------------------- runtime projects

	@Test
	public void testRuntimeProjectsNeedVenueAuthorityAndPersist() throws Exception {
		Path dir = Files.createTempDirectory("covia-claudecode-runtime");
		// A plain user cannot register a directory.
		Job denied = awaitFinished(engine.jobs().invokeOperation("v/ops/claudecode/create", Maps.of("name", "rt", "path", dir.toString()), OWNER_CTX));
		assertEquals(Status.FAILED, denied.getStatus());
		assertTrue(denied.getErrorMessage().contains("claudecode/manage"), denied.getErrorMessage());

		// The venue can, naming an owner.
		AMap<AString, ACell> created = run(engine.venueContext(), "v/ops/claudecode/create",
			Maps.of("name", "rt", "path", dir.toString(), "user", OWNER.toString(), "description", "runtime project",
				"options", Maps.of("model", "sonnet")));
		assertEquals("runtime", str(created, "managed"));
		assertEquals(OWNER, created.get(ProjectSpec.K_USER));
		// Persisted in the venue workspace…
		ACell record = engine.resolvePath(Strings.create(ClaudeCodeAdapter.REGISTRY_PATH + "/rt"), engine.venueContext());
		assertNotNull(record);
		assertEquals(dir.toAbsolutePath().normalize().toString(), str(RT.castMap(record), "path"));
		// …usable by its owner…
		String args = str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "rt", "prompt", "args")), "result");
		assertTrue(args.contains("--model sonnet"), args);
		assertEquals("cwd: " + dir.toAbsolutePath().normalize(),
			str(run(OWNER_CTX, "v/ops/claudecode/run", Maps.of("project", "rt", "prompt", "cwd")), "result"));
		// …not replaceable while it exists, and config projects are off limits.
		Job dup = engine.jobs().invokeOperation("v/ops/claudecode/create", Maps.of("name", "rt", "path", dir.toString()), engine.venueContext());
		assertThrows(RuntimeException.class, () -> dup.awaitResult(10_000));
		Job cfg = engine.jobs().invokeOperation("v/ops/claudecode/create", Maps.of("name", "alpha", "path", dir.toString()), engine.venueContext());
		assertThrows(RuntimeException.class, () -> cfg.awaitResult(10_000));

		// Delete stops its sessions and forgets it; the record is gone.
		AMap<AString, ACell> deleted = run(engine.venueContext(), "v/ops/claudecode/delete", Maps.of("name", "rt"));
		assertEquals(CVMBool.TRUE, deleted.get(ClaudeCodeAdapter.K_DELETED));
		assertNull(engine.resolvePath(Strings.create(ClaudeCodeAdapter.REGISTRY_PATH + "/rt"), engine.venueContext()));
		assertThrows(IllegalArgumentException.class, () -> engine.jobs().invokeOperation("v/ops/claudecode/run",
			Maps.of("project", "rt", "prompt", "hi"), OWNER_CTX));
		Job delCfg = engine.jobs().invokeOperation("v/ops/claudecode/delete", Maps.of("name", "alpha"), engine.venueContext());
		assertThrows(RuntimeException.class, () -> delCfg.awaitResult(10_000));
	}

	@Test
	public void testRuntimeProjectsRearmFromTheLattice() throws Exception {
		Path dir = Files.createTempDirectory("covia-claudecode-rearm");
		run(engine.venueContext(), "v/ops/claudecode/create", Maps.of("name", "rearm", "path", dir.toString(), "user", OWNER.toString()));
		try {
			// A fresh adapter instance installed on the same engine (a restart, in effect) sees the project.
			ClaudeCodeAdapter fresh = new ClaudeCodeAdapter();
			fresh.configure(baseConfig, true);
			fresh.engine = engine;
			fresh.rearmForTest();
			assertNotNull(fresh.project("rearm"));
			assertEquals(dir.toAbsolutePath().normalize(), fresh.project("rearm").path());
			fresh.close();
		} finally {
			run(engine.venueContext(), "v/ops/claudecode/delete", Maps.of("name", "rearm"));
		}
	}

	// ------------------------------------------------------- long-lived sessions

	@Test
	public void testSessionJobConversation() throws Exception {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/session", Maps.of("project", "alpha", "prompt", "count"), OWNER_CTX);
		await(() -> Status.INPUT_REQUIRED.equals(job.getStatus()), 30_000);
		AMap<AString, ACell> out = output(job);
		assertEquals("count: 1", str(out, "result"));
		String session = str(out, "session");
		assertNotNull(session);
		assertEquals(Strings.create(session), job.getData().get(ClaudeCodeAdapter.K_SESSION), "session recorded on the job");
		assertEquals(Strings.create("alpha"), job.getData().get(ClaudeCodeAdapter.K_PROJECT));

		// A message is the next turn (REST wraps a bare body as {content}).
		engine.jobs().deliverMessage(job.getID(), Maps.of("content", "count"), OWNER_CTX);
		await(() -> "count: 2".equals(str(output(job), "result")), 30_000);
		assertEquals(Status.INPUT_REQUIRED, job.getStatus());

		// Stop the process out from under it: the next message resumes the conversation.
		run(OWNER_CTX, "v/ops/claudecode/stop", Maps.of("session", session));
		engine.jobs().deliverMessage(job.getID(), Maps.of("prompt", "count"), OWNER_CTX);
		await(() -> "count: 3".equals(str(output(job), "result")), 30_000);
		assertEquals(Status.INPUT_REQUIRED, job.getStatus());

		// end: true finishes it.
		engine.jobs().deliverMessage(job.getID(), Maps.of("end", true), OWNER_CTX);
		await(job::isFinished, 10_000);
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		AMap<AString, ACell> summary = RT.castMap(job.getOutput());  // finished now
		assertEquals(CVMBool.TRUE, summary.get(ClaudeCodeAdapter.K_ENDED));
		assertEquals(CVMLong.create(3), summary.get(Fields.TURNS));
		assertEquals(session, str(summary, "session"));
	}

	@Test
	public void testSessionJobWithoutPromptWaitsAndResultFutureIsFirstReply() throws Exception {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/session", Maps.of("project", "alpha"), OWNER_CTX);
		await(() -> Status.INPUT_REQUIRED.equals(job.getStatus()), 10_000);
		assertEquals("alpha", str(output(job), "project"));
		// The result-oriented path answers with the first reply while the job stays open.
		ACell first = engine.jobs().invokeInternal("v/ops/claudecode/session", Maps.of("project", "alpha", "prompt", "echo hey"), OWNER_CTX)
			.get(30, java.util.concurrent.TimeUnit.SECONDS);
		assertEquals("echo: hey", str(RT.castMap(first), "result"));
		// Cancel closes the conversation job.
		job.cancel();
		assertEquals(Status.CANCELLED, job.getStatus());
	}

	@Test
	public void testSessionJobRestoredAfterRestartResumesFromJobData() throws Exception {
		Job job = engine.jobs().invokeOperation("v/ops/claudecode/session", Maps.of("project", "alpha", "prompt", "count"), OWNER_CTX);
		await(() -> Status.INPUT_REQUIRED.equals(job.getStatus()), 30_000);
		String session = str(output(job), "session");
		// Simulate the venue forgetting the live state (restart): the adapter rebuilds it from the job record.
		adapter.forgetSessionJobForTest(job.getID());
		run(OWNER_CTX, "v/ops/claudecode/stop", Maps.of("session", session));
		engine.jobs().deliverMessage(job.getID(), Maps.of("content", "count"), OWNER_CTX);
		await(() -> "count: 2".equals(str(output(job), "result")), 30_000);
		assertEquals(session, str(output(job), "session"), "same conversation");
		engine.jobs().deliverMessage(job.getID(), Maps.of("end", true), OWNER_CTX);
		await(job::isFinished, 10_000);
	}

	// ------------------------------------------------------------------ helpers

	@Test
	public void testMessageTextAndFailureOf() {
		assertEquals("hi", ClaudeCodeAdapter.messageText(Strings.create("hi")));
		assertEquals("hi", ClaudeCodeAdapter.messageText(Maps.of("content", "hi")));
		assertEquals("p", ClaudeCodeAdapter.messageText(Maps.of("prompt", "p", "content", "c")));
		assertNull(ClaudeCodeAdapter.messageText(Maps.of("end", true)));
		assertNull(ClaudeCodeAdapter.failureOf(Maps.of(ClaudeSession.K_IS_ERROR, false)));
		String f = ClaudeCodeAdapter.failureOf(Maps.of(ClaudeSession.K_IS_ERROR, true, ClaudeSession.K_SUBTYPE, "error_max_turns",
			ClaudeSession.K_SESSION, "abc"));
		assertTrue(f.contains("error_max_turns") && f.contains("abc"), f);
	}

	private static AMap<AString, ACell> run(RequestContext ctx, String op, AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		ACell result = job.awaitResult(30_000);
		assertEquals(Status.COMPLETE, job.getStatus(), op + ": " + job.getErrorMessage());
		return RT.castMap(result);
	}

	private static AMap<AString, ACell> output(Job job) {
		return RT.castMap(job.getData().get(Fields.OUTPUT));
	}

	/** Await a job reaching a terminal status, returning it. */
	private static Job awaitFinished(Job job) {
		await(job::isFinished, 30_000);
		return job;
	}

	private static String str(AMap<AString, ACell> m, String key) {
		ACell v = (m == null) ? null : m.get(Strings.create(key));
		return (v == null) ? null : v.toString();
	}

	private static AMap<AString, ACell> sessionStatus(RequestContext ctx, String session) {
		AMap<AString, ACell> res = run(ctx, "v/ops/claudecode/sessions", Maps.empty());
		AVector<ACell> list = RT.ensureVector(res.get(ClaudeCodeAdapter.K_SESSIONS));
		for (long i = 0; i < list.count(); i++) {
			AMap<AString, ACell> s = RT.castMap(list.get(i));
			if (session.equals(str(s, "session"))) return s;
		}
		return null;
	}

	private static int liveInProject(String project) {
		int n = 0;
		for (ClaudeSession s : adapter.knownSessions()) if (s.isLive() && s.project.name().equals(project)) n++;
		return n;
	}

	private static int liveTotal() {
		int n = 0;
		for (ClaudeSession s : adapter.knownSessions()) if (s.isLive()) n++;
		return n;
	}

	private static void stopAll() {
		for (ClaudeSession s : adapter.knownSessions()) s.stop("test reset");
		await(() -> liveTotal() == 0, 10_000);
	}

	private static void await(BooleanSupplier condition, long timeoutMillis) {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) throw new AssertionError("Timed out waiting for condition");
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError(e);
			}
		}
	}
}
