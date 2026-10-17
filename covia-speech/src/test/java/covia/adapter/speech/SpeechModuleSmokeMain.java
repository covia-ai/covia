package covia.adapter.speech;

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

/** Child-process entry point for {@link SpeechModuleIT}: speak, then transcribe what was spoken. */
public final class SpeechModuleSmokeMain {
	private SpeechModuleSmokeMain() {}

	public static void main(String[] args) throws Exception {
		try (FakeSpeechApi api = new FakeSpeechApi()) {
			Engine engine = Engine.createTemp(Maps.of(
				Config.MODULES, Vectors.of(Maps.of("path", args[0])),
				Config.ADAPTERS, Maps.of("speech", Maps.of("baseUrl", api.baseUrl()))));
			try {
				Engine.addDemoAssets(engine);
				AAdapter adapter = engine.getAdapter("speech");
				if (adapter == null) throw new AssertionError("Speech adapter did not load");
				if (!(adapter.getClass().getClassLoader() instanceof ModuleClassLoader)) {
					throw new AssertionError("Speech adapter was not loaded as a module");
				}
				if (engine.resolvePath(Strings.create("v/skills/adapters/speech"), engine.venueContext()) == null) {
					throw new AssertionError("Speech module skill was not installed");
				}
				ACell spoken = run(engine, "speak", Maps.of("text", "Hello from the module"));
				ACell heard = run(engine, "transcribe", Maps.of("ref", RT.getIn(spoken, "ref")));
				if (!"Hello world.".equals(String.valueOf(RT.getIn(heard, "text")))) {
					throw new AssertionError("Unexpected transcription: " + heard);
				}
				System.out.println("SPEECH_MODULE_SMOKE_OK");
			} finally {
				engine.close();
			}
		}
	}

	private static ACell run(Engine engine, String op, ACell input) throws Exception {
		Job job = engine.jobs().invokeOperation("v/ops/speech/" + op, input, engine.venueContext());
		ACell output = job.awaitResult(10_000);
		if (job.getStatus() != Status.COMPLETE) throw new AssertionError("speech:" + op + " failed: " + job.getErrorMessage());
		return output;
	}
}
