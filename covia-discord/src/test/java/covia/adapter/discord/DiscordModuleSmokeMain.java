package covia.adapter.discord;

import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.AAdapter;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.ModuleClassLoader;

public final class DiscordModuleSmokeMain {
	private DiscordModuleSmokeMain(){}
	public static void main(String[] args)throws Exception{
		Engine engine=Engine.createTemp(Maps.of(Config.MODULES,Vectors.of(Maps.of("path",args[0])),Config.ADAPTERS,Maps.of("discord",Maps.empty())));
		try{Engine.addDemoAssets(engine);AAdapter adapter=engine.getAdapter("discord");if(adapter==null)throw new AssertionError("Discord adapter did not load");ClassLoader loader=adapter.getClass().getClassLoader();if(!(loader instanceof ModuleClassLoader))throw new AssertionError("Not a module classloader: "+loader);Class<?> jda=Class.forName("net.dv8tion.jda.api.JDA",false,loader);if(jda.getClassLoader()!=loader)throw new AssertionError("JDA leaked onto venue classpath");if(engine.resolvePath(Strings.create("v/skills/adapters/discord"),engine.venueContext())==null)throw new AssertionError("Discord skill missing");System.out.println("DISCORD_MODULE_SMOKE_OK");}finally{engine.close();}
	}
}
