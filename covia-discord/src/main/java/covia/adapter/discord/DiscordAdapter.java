package covia.adapter.discord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.messaging.AMessagingAdapter;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.adapter.messaging.ConversationRouter;
import covia.venue.RequestContext;

/** Discord bots as a venue front door and as an outbound messaging adapter. */
public class DiscordAdapter extends AMessagingAdapter<BotSpec, BotRunner> {
	public static final String NAME="discord";
	static final String DEFAULT_API_URL="https://discord.com/api/v10";
	static final AString K_BOTS=Strings.intern("bots"), K_API_URL=Strings.intern("apiUrl"),
		K_BOT=Strings.intern("bot"), K_METHOD=Strings.intern("method"), K_ROUTE=Strings.intern("route"),
		K_BODY=Strings.intern("body"), K_CHANNEL_ID=Strings.intern("channel_id"),
		K_CONTENT=Strings.intern("content"), K_REPLY_TO=Strings.intern("reply_to"),
		K_SUPPRESS_EMBEDS=Strings.intern("suppress_embeds"), K_DELETED=Strings.intern("deleted"),
		K_ENABLED=Strings.intern("enabled"), K_STATE_PATH=Strings.intern("statePath");
	public static final AString ABILITY_SEND=Strings.intern("discord/send"),
		ABILITY_CALL=Strings.intern("discord/call"), ABILITY_MANAGE=Strings.intern("discord/manage");
	private static final Set<AString> KNOWN=Set.of(K_BOTS,K_API_URL,K_ENABLED);
	private volatile String apiUrl=DEFAULT_API_URL;
	volatile DiscordGateway.Factory gatewayFactory=JDAGateway::connect;

	@Override public String getName(){return NAME;}
	@Override public String getDescription(){return "Discord bots route DMs and mentioned guild messages to agents or operations; Discord REST operations send messages and make capability-gated API calls.";}
	@Override public AMap<AString,ACell> publicConfig(){return Maps.of(K_API_URL,Strings.create(apiUrl));}
	@Override protected void installAssets(){
		installAsset("discord/send","/adapters/discord/send.json");
		installAsset("discord/call","/adapters/discord/call.json");
		installAsset("discord/create","/adapters/discord/create.json");
		installAsset("discord/delete","/adapters/discord/delete.json");
		installAsset("discord/bots","/adapters/discord/bots.json");
		// Module-owned path: the venue jar ships its own /skills/discord.json (the
		// Connections provider skill), so a shared classpath must not collide (#510).
		installSkill("adapters/discord", "/adapters/discord/skill.json");
		installAgentTemplate("discord","/agent-templates/discord.json");
	}

	@Override public boolean configure(AMap<AString,ACell> config,boolean strict){
		if(config==null)config=Maps.empty();
		if(config.containsKey(K_STATE_PATH))throw new IllegalArgumentException("adapters.discord.statePath is fixed at w/adapters/discord");
		if(strict)for(long i=0;i<config.count();i++){ACell k=config.entryAt(i).getKey();if(!(k instanceof AString s)||!KNOWN.contains(s))throw new IllegalArgumentException("adapters.discord: unknown setting "+k);}
		String url=DEFAULT_API_URL; ACell u=config.get(K_API_URL);
		if(u!=null){if(!(u instanceof AString s)||s.isEmpty())throw new IllegalArgumentException("adapters.discord.apiUrl must be a non-empty string");url=s.toString();if(!url.startsWith("http://")&&!url.startsWith("https://"))throw new IllegalArgumentException("adapters.discord.apiUrl must be an http(s) URL");url=url.replaceAll("/+$","");}
		Map<String,BotSpec> parsed=new LinkedHashMap<>(); ACell bc=config.get(K_BOTS);
		if(bc!=null){AMap<AString,ACell> bots=RT.castMap(bc);if(bots==null)throw new IllegalArgumentException("adapters.discord.bots must be an object");for(long i=0;i<bots.count();i++){var e=bots.entryAt(i);String name=String.valueOf(e.getKey());parsed.put(name,BotSpec.parse(name,e.getValue(),strict));}}
		apiUrl=url;configureBots(parsed);return true;
	}
	@Override protected BotSpec parseBot(String name,ACell settings,boolean strict){return BotSpec.parse(name,settings,strict);}
	@Override protected BotRunner newBot(BotSpec spec,Managed managed){return new BotRunner(this,spec,apiUrl,managed);}
	@Override protected boolean configurationMatches(BotRunner runner){return apiUrl.equals(runner.apiUrl);}
	String resolveToken(BotSpec spec){return resolveCredential(spec.tokenRef(),spec.userDID(engine));}
	BotRunner runnerForTest(String name){return runner(name);}
	BotRunner runnerForTest(AString owner,String name){return runner(owner,name);}
	void forgetForTest(AString owner,String name){forgetRuntimeBot(owner,name);}
	void rearmForTest(){rearmRuntimeBots();}

	@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,AMap<AString,ACell> meta,ACell input){
		requireInvoke(ctx);String op=getSubOperation(meta);if(op==null)throw new IllegalArgumentException("Insufficient specification for discord operation");
		return CompletableFuture.supplyAsync(()->switch(op){case "send"->send(ctx,input);case "call"->call(ctx,input);case "create"->handleCreate(ctx,input);case "delete"->handleDelete(ctx,input);case "bots"->handleBots(ctx);default->throw new UnsupportedOperationException("Unsupported discord operation: "+op);},VIRTUAL_EXECUTOR);
	}
	private ACell send(RequestContext ctx,ACell input){
		AMap<AString,ACell> in=RT.castMap(input);if(in==null)throw new IllegalArgumentException("send expects an object");BotRunner r=selectBot(ctx,RT.ensureString(in.get(K_BOT)));requireBotAccess(ctx,r,ABILITY_SEND);
		AString channel=RT.ensureString(in.get(K_CHANNEL_ID));AString content=RT.ensureString(in.get(K_CONTENT));
		if(channel==null||channel.isEmpty())throw new IllegalArgumentException("channel_id is required");if(content==null||content.isEmpty())throw new IllegalArgumentException("content is required");
		AMap<AString,ACell> body=Maps.of(K_CONTENT,content);AString reply=RT.ensureString(in.get(K_REPLY_TO));if(reply!=null)body=body.assoc(Strings.intern("message_reference"),Maps.of(Strings.intern("message_id"),reply,Strings.intern("fail_if_not_exists"),CVMBool.FALSE));
		if(CVMBool.TRUE.equals(in.get(K_SUPPRESS_EMBEDS)))body=body.assoc(Strings.intern("flags"),convex.core.data.prim.CVMLong.create(4));
		for(long i=0;i<in.count();i++){var e=in.entryAt(i);ACell k=e.getKey();if(!K_BOT.equals(k)&&!K_CHANNEL_ID.equals(k)&&!K_REPLY_TO.equals(k)&&!K_SUPPRESS_EMBEDS.equals(k))body=body.assoc((AString)k,e.getValue());}
		return r.sendMessage(channel.toString(),body);
	}
	private ACell call(RequestContext ctx,ACell input){
		BotRunner r=selectBot(ctx,RT.ensureString(RT.getIn(input,K_BOT)));requireBotAccess(ctx,r,ABILITY_CALL);
		AString method=RT.ensureString(RT.getIn(input,K_METHOD)),route=RT.ensureString(RT.getIn(input,K_ROUTE));if(method==null||route==null)throw new IllegalArgumentException("method and route are required");
		String m=method.toString().toUpperCase(),p=route.toString();if(!Set.of("GET","POST","PUT","PATCH","DELETE").contains(m))throw new IllegalArgumentException("method must be GET, POST, PUT, PATCH or DELETE");
		if(!p.startsWith("/")||p.contains("://")||p.contains("..")||p.startsWith("/gateway")||p.startsWith("/oauth2"))throw new IllegalArgumentException("route must be a safe Discord API path; Gateway and OAuth routes are managed/refused");
		return r.call(m,p,RT.getIn(input,K_BODY));
	}
}
