package covia.adapter.discord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.AdapterWorkspace;
import covia.venue.Engine;
import covia.venue.RequestContext;

/** Discord bots as a venue front door and as an outbound messaging adapter. */
public class DiscordAdapter extends AAdapter implements AutoCloseable {
	private static final Logger log=LoggerFactory.getLogger(DiscordAdapter.class);
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
	private static final AString[] TEXT_KEYS={Fields.TEXT,Fields.RESPONSE,Strings.intern("content"),Fields.MESSAGE,Fields.RESULT};

	private volatile Map<String,BotSpec> specs=Map.of();
	private volatile String apiUrl=DEFAULT_API_URL;
	private final Map<String,BotRunner> runners=new LinkedHashMap<>();
	private final ScheduledExecutorService retries=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"discord-retry");t.setDaemon(true);return t;});
	volatile long retryMillis=30_000;
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
		installSkill("adapters/discord", "/skills/discord.json");
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
		apiUrl=url;specs=Map.copyOf(parsed);if(engine!=null)reconcile();return true;
	}
	@Override public void install(Engine engine){super.install(engine);reconcile();rearmRuntimeBots();}
	private synchronized void reconcile(){
		List<String> stale=new ArrayList<>();for(var e:runners.entrySet()){BotRunner r=e.getValue();if(r.managed!=BotRunner.Managed.CONFIG)continue;BotSpec want=specs.get(e.getKey());if(want==null||!want.equals(r.spec)||!apiUrl.equals(r.apiUrl))stale.add(e.getKey());}
		for(String k:stale)runners.remove(k).stop();
		for(BotSpec s:specs.values())if(!runners.containsKey(s.name())){BotRunner r=new BotRunner(this,s,apiUrl,BotRunner.Managed.CONFIG);runners.put(s.name(),r);r.start();}
	}
	private synchronized void rearmRuntimeBots(){
		AMap<AString,ACell> users=RT.castMap(state().read("users"));
		if(users==null)return;
		for(var ue:users.entrySet()){
			if(!(ue.getKey() instanceof AString owner)||!owner.toString().startsWith("did:")){
				log.warn("Discord: skipping invalid adapter-state user key {}",ue.getKey());continue;
			}
			AMap<AString,ACell> registry=RT.castMap(RT.getIn(ue.getValue(),K_BOTS));
			if(registry==null)continue;
			for(var be:registry.entrySet()){
				String name=String.valueOf(be.getKey()),key=runtimeKey(owner,name);if(runners.containsKey(key))continue;
				try{AMap<AString,ACell> m=RT.castMap(be.getValue());if(m==null)throw new IllegalArgumentException("record is not an object");m=m.dissoc(BotSpec.K_USER).assoc(BotSpec.K_USER,owner);BotSpec s=BotSpec.parse(name,m,true);BotRunner r=new BotRunner(this,s,apiUrl,BotRunner.Managed.RUNTIME);runners.put(key,r);r.start();}catch(RuntimeException e){log.warn("Discord: skipping bot '{}' of {}: {}",name,owner,e.getMessage());}
			}
		}
	}
	@Override public synchronized void close(){for(BotRunner r:runners.values())r.stop();runners.clear();retries.shutdownNow();}
	boolean isActive(){return engine!=null&&engine.getAdapter(NAME)==this;}
	ScheduledFuture<?> scheduleRetry(Runnable r,long ms){return retries.isShutdown()?null:retries.schedule(r,ms,TimeUnit.MILLISECONDS);}
	String resolveToken(BotSpec spec){String ref=spec.tokenRef();if(!(ref.startsWith("s/")||ref.startsWith("/s/")))return ref;String v=engine.resolveSecret(ref,RequestContext.of(spec.userDID(engine)));return v!=null?v:engine.resolveSecret(ref,engine.venueContext());}
	private static String runtimeKey(AString owner,String name){return owner+"#"+name;}
	private synchronized List<BotRunner> runnerList(){return new ArrayList<>(runners.values());}
	private synchronized BotRunner runner(String name){return runners.get(name);}
	/** Package-private test visibility without exposing runners in the adapter API. */
	BotRunner runnerForTest(String name){return runner(name);}
	BotRunner runnerForTest(AString owner,String name){return runner(owner,name);}
	void forgetForTest(AString owner,String name){synchronized(this){BotRunner r=runners.remove(runtimeKey(owner,name));if(r!=null)r.stop();}}
	void rearmForTest(){rearmRuntimeBots();}
	private synchronized BotRunner runner(AString owner,String name){return runners.get(runtimeKey(owner,name));}
	AdapterWorkspace state(){return adapterWorkspace();}
	String userStatePath(AString owner,String relative){return state().userPath(owner,relative);}
	String runtimeBotPath(AString owner,String name){return state().path(userStatePath(owner,"bots/"+name));}

	@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx,AMap<AString,ACell> meta,ACell input){
		requireInvoke(ctx);String op=getSubOperation(meta);if(op==null)throw new IllegalArgumentException("Insufficient specification for discord operation");
		return CompletableFuture.supplyAsync(()->switch(op){case "send"->send(ctx,input);case "call"->call(ctx,input);case "create"->create(ctx,input);case "delete"->delete(ctx,input);case "bots"->bots(ctx);default->throw new UnsupportedOperationException("Unsupported discord operation: "+op);},VIRTUAL_EXECUTOR);
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
	private ACell bots(RequestContext ctx){AString user=ctx.getUserDID();boolean venue=engine.getDIDString().equals(ctx.getCallerDID());AVector<ACell> out=Vectors.empty();for(BotRunner r:runnerList())if(venue||(user!=null&&user.equals(r.spec.userDID(engine))))out=out.conj(r.status());return Maps.of(K_BOTS,out);}
	private ACell create(RequestContext ctx,ACell input){
		AString owner=ctx.getUserDID();if(owner==null)throw new AuthException("discord:create requires an authenticated caller");AMap<AString,ACell> in=RT.castMap(input);if(in==null)throw new IllegalArgumentException("create expects bot settings");AString nc=RT.ensureString(in.get(Fields.NAME));if(nc==null||nc.isEmpty())throw new IllegalArgumentException("name is required");String name=nc.toString();
		if(in.containsKey(BotSpec.K_USER))throw new IllegalArgumentException("user is implicit for a created bot");AString token=RT.ensureString(in.get(BotSpec.K_TOKEN));if(token==null||!(token.toString().startsWith("s/")||token.toString().startsWith("/s/")))throw new IllegalArgumentException("token must be an s/NAME secret reference");
		AMap<AString,ACell> settings=in.dissoc(Fields.NAME);BotSpec spec=BotSpec.parse(name,settings.assoc(BotSpec.K_USER,owner),true);engine.requireAuthority(ctx,Strings.create(owner+"/discord/"+name),ABILITY_MANAGE);String key=runtimeKey(owner,name);BotRunner r;
		synchronized(this){if(runners.containsKey(key))throw new IllegalArgumentException("You already have a Discord bot named '"+name+"'");state().write(userStatePath(owner,"bots/"+name),settings);r=new BotRunner(this,spec,apiUrl,BotRunner.Managed.RUNTIME);runners.put(key,r);}r.start();return r.status();
	}
	private ACell delete(RequestContext ctx,ACell input){
		AString owner=ctx.getUserDID();if(owner==null)throw new AuthException("discord:delete requires an authenticated caller");AString nc=RT.ensureString(RT.getIn(input,Fields.NAME));if(nc==null||nc.isEmpty())throw new IllegalArgumentException("name is required");String name=nc.toString();engine.requireAuthority(ctx,Strings.create(owner+"/discord/"+name),ABILITY_MANAGE);BotRunner r;
		synchronized(this){r=runners.remove(runtimeKey(owner,name));if(r==null){BotRunner c=runners.get(name);if(c!=null&&owner.equals(c.spec.userDID(engine)))throw new IllegalArgumentException("Discord bot '"+name+"' is declared in venue config");throw new IllegalArgumentException("You have no Discord bot named '"+name+"'");}}
		r.stop();state().delete(userStatePath(owner,"bots/"+name));state().delete(userStatePath(owner,"sessions/"+name));return Maps.of(Fields.NAME,nc,K_DELETED,CVMBool.TRUE);
	}
	private void requireBotAccess(RequestContext ctx,BotRunner r,AString ability){AString owner=r.spec.userDID(engine);engine.requireLocalAccess(ctx,Strings.create(owner+"/discord/"+r.spec.name()),ability);}
	private BotRunner selectBot(RequestContext ctx,AString name){AString user=ctx.getUserDID();if(name!=null&&!name.isEmpty()){BotRunner r=user==null?null:runner(user,name.toString());if(r==null)r=runner(name.toString());if(r==null)throw new IllegalArgumentException("Unknown Discord bot: "+name);return r;}List<BotRunner> mine=new ArrayList<>();for(BotRunner r:runnerList())if(user!=null&&user.equals(r.spec.userDID(engine)))mine.add(r);if(mine.size()==1)return mine.getFirst();if(mine.isEmpty())throw new IllegalArgumentException("No Discord bot is configured for "+user);throw new IllegalArgumentException("Several Discord bots are available; specify bot");}
	static String renderText(ACell value){if(value==null)return null;if(value instanceof AString s)return s.toString();if(value instanceof AMap<?,?> m)for(AString k:TEXT_KEYS){ACell v=RT.getIn(m,k);if(v instanceof AString s)return s.toString();}return JSON.printPretty(value).toString();}
}
