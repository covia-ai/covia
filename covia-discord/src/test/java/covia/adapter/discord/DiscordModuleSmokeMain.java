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
		try{Engine.addDemoAssets(engine);AAdapter adapter=engine.getAdapter("discord");if(adapter==null)throw new AssertionError("Discord adapter did not load");ClassLoader loader=adapter.getClass().getClassLoader();if(!(loader instanceof ModuleClassLoader))throw new AssertionError("Not a module classloader: "+loader);Class<?> jda=Class.forName("net.dv8tion.jda.api.JDA",false,loader);if(jda.getClassLoader()!=loader)throw new AssertionError("JDA leaked onto venue classpath");
			// Kotlin and Jackson are not bundled: JDA and OkHttp must run on the venue's copies
			for(String shared:new String[]{"com.fasterxml.jackson.databind.ObjectMapper","kotlin.Unit"})if(Class.forName(shared,false,loader).getClassLoader()==loader)throw new AssertionError(shared+" bundled in the module");
			Class<?> data=Class.forName("net.dv8tion.jda.api.utils.data.DataObject",true,loader);Object parsed=data.getMethod("fromJson",String.class).invoke(null,"{\"ok\":7}");if(!Integer.valueOf(7).equals(data.getMethod("getInt",String.class).invoke(parsed,"ok")))throw new AssertionError("JDA JSON over the venue's Jackson failed");
			Class.forName("okhttp3.OkHttpClient",true,loader).getConstructor().newInstance();
			if(engine.resolvePath(Strings.create("v/skills/adapters/discord"),engine.venueContext())==null)throw new AssertionError("Discord skill missing");System.out.println("DISCORD_MODULE_SMOKE_OK");}finally{engine.close();}
	}
}
