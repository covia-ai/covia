package covia.adapter.telegram;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.ModuleClassLoader;
import covia.venue.RequestContext;

/**
 * Child-process entry point for {@link TelegramModuleIT}: boots a venue with
 * only covia.jar on the classpath, loads the shaded module, and drives one
 * bot end to end against {@link FakeTelegramServer} — proving the Telegram
 * client works from inside the module classloader.
 */
public final class TelegramModuleSmokeMain {
	private TelegramModuleSmokeMain() {}

	public static void main(String[] args) throws Exception {
		AString owner = Strings.create("did:key:zTelegramModuleSmoke");
		try (FakeTelegramServer telegram = new FakeTelegramServer()) {
			AMap<AString, ACell> config = Maps.of(
				Config.MODULES, Vectors.of(Maps.of("path", args[0])),
				Config.USERS, Maps.of(Config.AUTO_CREATE, true),
				Config.ADAPTERS, Maps.of(
					Strings.create("telegram"), Maps.of(
						Strings.create("apiUrl"), Strings.create(telegram.apiUrl()),
						Strings.create("bots"), Maps.of(
							Strings.create("smoke"), Maps.of(
								Strings.create("token"), Strings.create(FakeTelegramServer.TOKEN),
								Strings.create("user"), owner,
								Strings.create("operation"), Strings.create("v/test/ops/echo"),
								Strings.create("open"), convex.core.data.prim.CVMBool.TRUE)))));
			Engine engine = Engine.createTemp(config);
			try {
				Engine.addDemoAssets(engine);
				AAdapter adapter = engine.getAdapter("telegram");
				if (adapter == null) throw new AssertionError("Telegram adapter did not load");
				ClassLoader loader = adapter.getClass().getClassLoader();
				if (!(loader instanceof ModuleClassLoader)) {
					throw new AssertionError("Adapter was not loaded as a module: " + loader);
				}
				Class<?> client = Class.forName("com.pengrad.telegrambot.TelegramBot", false, loader);
				if (client.getClassLoader() != loader) {
					throw new AssertionError("Telegram client leaked onto the venue classpath");
				}
				if (engine.resolvePath(Strings.create("v/skills/telegram"), engine.venueContext()) == null) {
					throw new AssertionError("Telegram module skill was not installed");
				}

				// Inbound: an open bot answers a stranger via the echo operation.
				telegram.push(4242L, 99L, "smoker", "round trip");
				FakeTelegramServer.Sent reply = telegram.awaitSent(30_000);
				if (reply == null || !reply.text().contains("round trip")) {
					throw new AssertionError("Bad inbound reply: " + reply);
				}

				// Outbound: the owner sends through the bot.
				RequestContext user = RequestContext.of(owner);
				ACell out = run(engine, user, "v/ops/telegram/send", Maps.of(
					"chat_id", CVMLong.create(4242L), "text", "outbound"));
				FakeTelegramServer.Sent sent = telegram.awaitSent(10_000);
				if (sent == null || !"outbound".equals(sent.text())) {
					throw new AssertionError("Bad outbound send: " + sent + " / " + out);
				}
				ACell status = run(engine, user, "v/ops/telegram/bots", Maps.empty());
				if (!status.toString().contains("RUNNING")) {
					throw new AssertionError("Bot not RUNNING: " + status);
				}
				System.out.println("TELEGRAM_MODULE_SMOKE_OK");
			} finally {
				engine.close();
			}
		}
	}

	private static ACell run(Engine engine, RequestContext user, String operation,
			AMap<AString, ACell> input) {
		Job job = engine.jobs().invokeOperation(operation, input, user);
		ACell result = job.awaitResult(30_000);
		if (job.getStatus() != Status.COMPLETE) {
			throw new AssertionError(operation + " failed: " + job.getErrorMessage());
		}
		return RT.cvm(result);
	}
}
