package covia.adapter.slack;

import convex.core.data.*;
import covia.adapter.AAdapter;
import covia.adapter.webhook.*;
import covia.venue.*;

/** Runs with the venue jar as parent; provider implementation is loaded only from the module. */
public final class SlackModuleSmokeMain {
  public static void main(String[] args) throws Exception {
    Engine engine = Engine.createTemp(Maps.of(Config.MODULES, Vectors.of(Maps.of("path", args[0]))));
    try {
      Engine.addDemoAssets(engine);
      AAdapter adapter = engine.getAdapter("slack");
      if (adapter == null || !(adapter.getClass().getClassLoader() instanceof ModuleClassLoader)) throw new AssertionError("Module did not load in isolation");
      if (!(adapter instanceof WebhookHandler)) throw new AssertionError("Not a webhook receiver");
      for (String path : new String[]{"v/ops/slack/send", "v/ops/slack/create", "v/ops/slack/delete", "v/ops/slack/bots", "v/skills/adapters/slack", "v/adapters/slack/info"}) {
        if (engine.resolvePath(Strings.create(path), engine.venueContext()) == null) throw new AssertionError("Missing " + path);
      }
      String module = engine.moduleOf("slack").name();
      Modules.unload(engine, module);
      if (engine.getAdapter("slack") != null) throw new AssertionError("Adapter survived unload");
      if (engine.resolvePath(Strings.create("v/adapters/slack/info"), engine.venueContext()) != null) throw new AssertionError("Public info survived unload");
      if (((WebhookHandler) adapter).handleWebhook("c-main", new WebhookRequest("POST", java.util.Map.of(), java.util.Map.of(), new byte[0])).status() != 404) throw new AssertionError("Unloaded receiver remained active");
      System.out.println("slack_MODULE_SMOKE_OK");
    } finally { engine.close(); }
  }
}
