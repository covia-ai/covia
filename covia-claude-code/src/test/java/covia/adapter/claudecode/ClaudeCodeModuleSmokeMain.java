package covia.adapter.claudecode;

import java.nio.file.Files;
import java.nio.file.Path;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.ModuleClassLoader;
import covia.venue.RequestContext;

/**
 * Child-process entry point for {@link ClaudeCodeModuleIT}: boots a venue with
 * only covia.jar on the classpath, loads the shaded module, and drives one
 * Claude Code run end to end against {@link FakeClaude} — proving the adapter
 * works from inside the module classloader with no third-party leakage.
 */
public final class ClaudeCodeModuleSmokeMain {
	private ClaudeCodeModuleSmokeMain() {}

	public static void main(String[] args) throws Exception {
		AString owner = Strings.create("did:key:zClaudeCodeModuleSmoke");
		Path projectDir = Files.createTempDirectory("covia-claudecode-smoke");
		Path stateDir = Files.createDirectories(projectDir.resolve(".state"));
		AMap<AString, ACell> config = Maps.of(
			Config.MODULES, convex.core.data.Vectors.of(Maps.of("path", args[0])),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of(
				Strings.create("claudecode"), Maps.of(
					Strings.create("command"), RT.cvm(FakeClaude.command()),
					Strings.create("env"), Maps.of("FAKE_CLAUDE_STATE", stateDir.toString()),
					Strings.create("projects"), Maps.of(
						Strings.create("smoke"), Maps.of(
							Strings.create("path"), Strings.create(projectDir.toString()),
							Strings.create("user"), owner)))));
		Engine engine = Engine.createTemp(config);
		try {
			Engine.addDemoAssets(engine);
			AAdapter adapter = engine.getAdapter("claudecode");
			if (adapter == null) throw new AssertionError("Claude Code adapter did not load");
			ClassLoader loader = adapter.getClass().getClassLoader();
			if (!(loader instanceof ModuleClassLoader)) {
				throw new AssertionError("Adapter was not loaded as a module: " + loader);
			}
			if (engine.resolvePath(Strings.create("v/skills/claudecode"), engine.venueContext()) == null) {
				throw new AssertionError("Claude Code module skill was not installed");
			}

			RequestContext user = RequestContext.of(owner);
			ACell res = run(engine, user, "v/ops/claudecode/run",
				Maps.of("project", "smoke", "prompt", "hello module"));
			if (!"echo: hello module".equals(str(res, "result"))) {
				throw new AssertionError("Bad run result: " + res);
			}
			String session = str(RT.castMap(res), "session");
			// Resume the same conversation on the warm process.
			ACell again = run(engine, user, "v/ops/claudecode/run", Maps.of("session", session, "prompt", "count"));
			if (!"count: 2".equals(str(again, "result"))) {
				throw new AssertionError("Bad resume result: " + again);
			}
			ACell projects = run(engine, user, "v/ops/claudecode/projects", Maps.empty());
			if (!projects.toString().contains("smoke")) throw new AssertionError("Project missing: " + projects);
			System.out.println("CLAUDECODE_MODULE_SMOKE_OK");
		} finally {
			engine.close();
		}
	}

	private static ACell run(Engine engine, RequestContext user, String operation, AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(operation, input, user);
		ACell result = job.awaitResult(60_000);
		if (job.getStatus() != Status.COMPLETE) {
			throw new AssertionError(operation + " failed: " + job.getErrorMessage());
		}
		return RT.cvm(result);
	}

	private static String str(ACell cell, String key) {
		ACell v = RT.getIn(cell, Strings.create(key));
		return (v == null) ? null : v.toString();
	}
}
