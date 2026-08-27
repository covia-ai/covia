package covia.adapter.documents;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.ModuleClassLoader;

/**
 * Child-process entry point for {@link DocumentsModuleIT}: only the venue jar
 * and the test classes are on the classpath; the parsers arrive with the
 * module. {@code args[0]} is the shaded module jar, {@code args[1]} a
 * directory holding {@code report.pdf}.
 */
public final class DocumentsModuleSmokeMain {

	private DocumentsModuleSmokeMain() {}

	public static void main(String[] args) throws Exception {
		Engine engine = Engine.createTemp(Maps.of(
			Config.MODULES, Vectors.of(Maps.of("path", args[0])),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			"file", Maps.of("roots", Maps.of("docs", args[1]))));
		try {
			Engine.addDemoAssets(engine);
			AAdapter adapter = engine.getAdapter("documents");
			if (adapter == null) throw new AssertionError("documents adapter did not load");
			if (!(adapter.getClass().getClassLoader() instanceof ModuleClassLoader)) {
				throw new AssertionError("documents adapter was not loaded as a module");
			}
			if (engine.resolvePath(Strings.create("v/skills/data/documents"), engine.venueContext()) == null) {
				throw new AssertionError("documents module skill was not installed");
			}
			Job job = engine.jobs().invokeOperation("v/ops/file/read",
				Maps.of("root", "docs", "path", "report.pdf", "mode", "extract"),
				engine.venueContext());
			ACell output = job.awaitResult(20_000);
			if (job.getStatus() != Status.COMPLETE) {
				throw new AssertionError("extract read failed: " + job.getErrorMessage());
			}
			String text = String.valueOf(RT.getIn(output, "text"));
			if (!text.contains("--- page 1 ---") || !text.contains("Smoke page")) {
				throw new AssertionError("Unexpected extraction: " + output);
			}
			System.out.println("DOCUMENTS_MODULE_SMOKE_OK");
		} finally {
			engine.close();
		}
	}
}
