package covia.adapter.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;

/** Live Gateway/REST side of one Discord bot. */
final class BotRunner {
	private static final Logger log=LoggerFactory.getLogger(BotRunner.class);
	static final int MAX_MESSAGE_LENGTH=2000;
	private static final String AGENT_CHAT="v/ops/agent/chat";
	static final AString K_STATE=Strings.intern("state"),K_USERNAME=Strings.intern("username"),K_BOT_ID=Strings.intern("bot_id"),K_TARGET=Strings.intern("target"),K_RECEIVED=Strings.intern("received"),K_SENT=Strings.intern("sent"),K_FAILED=Strings.intern("failed"),K_MANAGED=Strings.intern("managed");
	private static final AString K_BOT=Strings.intern("bot"),K_VIA=Strings.intern("via"),K_CHANNEL=Strings.intern("channel"),K_ACCESS=Strings.intern("access"),K_FROM=Strings.intern("from"),K_GUILD=Strings.intern("guild"),K_MESSAGE_ID=Strings.intern("message_id"),K_CHANNEL_ID=Strings.intern("channel_id"),K_NAME=Strings.intern("name"),K_ID=Strings.intern("id"),K_TYPE=Strings.intern("type"),K_ATTACHMENTS=Strings.intern("attachments");
	enum State {STARTING,PENDING,RUNNING,STOPPED}
	enum Managed {CONFIG,RUNTIME}
	private final DiscordAdapter adapter;
	final BotSpec spec;final String apiUrl;final Managed managed;
	private volatile DiscordGateway gateway;private volatile HttpClient http;private volatile String token,username,botId,error;private volatile State state=State.STARTING;private volatile boolean stopped;private volatile ScheduledFuture<?> retry;private final AtomicLong received=new AtomicLong(),sent=new AtomicLong(),failed=new AtomicLong();
	private final ConcurrentHashMap<String,CompletableFuture<Void>> channelTails=new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String,String> sessions=new ConcurrentHashMap<>();

	BotRunner(DiscordAdapter adapter,BotSpec spec,String apiUrl,Managed managed){this.adapter=adapter;this.spec=spec;this.apiUrl=apiUrl;this.managed=managed;}
	void start(){AAdapter.VIRTUAL_EXECUTOR.execute(this::tryStart);}
	private synchronized void tryStart(){
		if(stopped||state==State.RUNNING)return;String tok;
		try{tok=adapter.resolveToken(spec);}catch(RuntimeException e){pending("token resolution failed: "+e.getMessage());return;}
		if(tok==null){pending("token secret "+spec.tokenRef()+" not found");return;}
		DiscordGateway candidate;
		try{candidate=adapter.gatewayFactory.connect(tok,this::receive);}catch(Exception|LinkageError e){pending("Discord Gateway connection failed: "+concise(e));return;}
		if(stopped){candidate.close();return;}
		gateway=candidate;token=tok;username=candidate.username();botId=candidate.botId();http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).executor(AAdapter.VIRTUAL_EXECUTOR).build();error=null;state=State.RUNNING;
		log.info("Discord bot '{}' (@{}) running as {} -> {}",spec.name(),username,spec.userRef(),spec.target());
	}
	private void pending(String why){state=State.PENDING;error=why;log.warn("Discord bot '{}' not started: {} (will retry)",spec.name(),why);retry=adapter.scheduleRetry(this::tryStart,adapter.retryMillis);}
	synchronized void stop(){stopped=true;if(retry!=null)retry.cancel(false);DiscordGateway g=gateway;gateway=null;token=null;http=null;if(g!=null)try{g.close();}catch(RuntimeException e){log.debug("Discord Gateway close failed",e);}state=State.STOPPED;}
	AMap<AString,ACell> status(){AMap<AString,ACell> m=Maps.of(Fields.NAME,Strings.create(spec.name()),BotSpec.K_USER,Strings.create(spec.userRef()),K_TARGET,Strings.create(spec.target()),K_STATE,Strings.create(state.name()),K_MANAGED,Strings.create(managed.name().toLowerCase(Locale.ROOT)),K_RECEIVED,CVMLong.create(received.get()),K_SENT,CVMLong.create(sent.get()),K_FAILED,CVMLong.create(failed.get()));if(username!=null)m=m.assoc(K_USERNAME,Strings.create(username));if(botId!=null)m=m.assoc(K_BOT_ID,Strings.create(botId));if(error!=null)m=m.assoc(Fields.ERROR,Strings.create(error));return m;}

	private void receive(InboundMessage m){
		if(stopped||!adapter.isActive())return;received.incrementAndGet();
		if(!spec.allows(m.authorId(),m.username(),m.globalName())){log.info("Discord bot '{}': unauthorised message {} from {}",spec.name(),m.id(),m.authorId());if(m.direct())sendQuietly(m.channelId(),"Not authorised to use this bot. Your Discord user id is "+m.authorId(),m.id());return;}
		if(!m.direct()&&spec.mentionOnly()&&!m.mentionedBot())return;
		String text=stripMention(m.content());if(command(m,text))return;if(text==null||text.isBlank())return;
		enqueue(m.channelId(),()->{if(spec.routesToAgent())respondAgent(m,text);else respondOperation(m);});
	}
	private boolean command(InboundMessage m,String text){String cmd=text==null?"":text.strip().toLowerCase(Locale.ROOT);switch(cmd){case "!id","/id"->sendQuietly(m.channelId(),"Channel id: "+m.channelId()+"\nYour Discord user id: "+m.authorId(),m.id());case "!start","/start","!help","/help"->sendQuietly(m.channelId(),greeting(),m.id());case "!new","/new"->{if(spec.routesToAgent()){forgetSession(m.channelId(),context());sendQuietly(m.channelId(),"Started a new conversation.",m.id());}else sendQuietly(m.channelId(),"This bot has no conversation state.",m.id());}default->{return false;}}return true;}
	private String greeting(){if(spec.greeting()!=null)return spec.greeting();return spec.routesToAgent()?"Connected to agent '"+spec.agent()+"'. Send a message to talk; !new starts a fresh conversation.":"Messages are handled by "+spec.operation()+".";}
	private String stripMention(String text){if(text==null)return "";if(botId!=null){text=text.replace("<@"+botId+">","").replace("<@!"+botId+">","");}return text.trim();}
	private void enqueue(String id,Runnable work){channelTails.compute(id,(k,tail)->{CompletableFuture<Void> prev=tail==null?CompletableFuture.completedFuture(null):tail;CompletableFuture<Void> next=prev.handleAsync((r,e)->{work.run();return null;},AAdapter.VIRTUAL_EXECUTOR);next.whenComplete((r,e)->channelTails.remove(k,next));return next;});}
	private void respondAgent(InboundMessage m){respondAgent(m,m.content());}
	private void respondAgent(InboundMessage m,String text){try{String reply=DiscordAdapter.renderText(chatAgent(m.channelId(),agentMessage(m,text)));if(reply==null||reply.isBlank())reply="(no response)";send(m.channelId(),reply,m.id());}catch(Throwable t){failed.incrementAndGet();logFailure("failed to respond",m,t);sendQuietly(m.channelId(),"⚠️ "+concise(t),m.id());}}
	private void respondOperation(InboundMessage m){try{ACell result=runJob(spec.operation(),messageRecord(m),context());if(!spec.silent()){String reply=spec.fixedReply();if(reply==null)reply=DiscordAdapter.renderText(result);if(reply==null||reply.isBlank())reply="(no response)";send(m.channelId(),reply,m.id());}}catch(Throwable t){failed.incrementAndGet();logFailure("handler failed",m,t);sendQuietly(m.channelId(),"⚠️ "+concise(t),m.id());}}

	AMap<AString,ACell> messageRecord(InboundMessage m){AMap<AString,ACell> out=Maps.of(K_BOT,Strings.create(spec.name()),K_ID,Strings.create(m.id()),K_CHANNEL_ID,Strings.create(m.channelId()),Strings.intern("channel_type"),Strings.create(m.channelType()),K_FROM,userRecord(m),Fields.TEXT,Strings.create(m.content()==null?"":m.content()),K_ATTACHMENTS,m.attachments());if(m.channelName()!=null)out=out.assoc(Strings.intern("channel_name"),Strings.create(m.channelName()));if(m.guildId()!=null)out=out.assoc(K_GUILD,Maps.of(K_ID,Strings.create(m.guildId()),K_NAME,Strings.create(m.guildName())));return out;}
	AMap<AString,ACell> agentMessage(InboundMessage m,String text){AMap<AString,ACell> channel=Maps.of(K_ID,Strings.create(m.channelId()),K_TYPE,Strings.create(m.channelType()));if(m.channelName()!=null)channel=channel.assoc(K_NAME,Strings.create(m.channelName()));AMap<AString,ACell> via=Maps.of(K_CHANNEL,Strings.create("discord"),K_BOT,Strings.create(spec.name()),K_ACCESS,Strings.create(spec.open()?"open":"allow"),K_FROM,userRecord(m),Strings.intern("chat"),channel,K_MESSAGE_ID,Strings.create(m.id()));if(m.guildId()!=null)via=via.assoc(K_GUILD,Maps.of(K_ID,Strings.create(m.guildId()),K_NAME,Strings.create(m.guildName())));return Maps.of(Fields.TEXT,Strings.create(text),K_VIA,via,K_ATTACHMENTS,m.attachments());}
	private static AMap<AString,ACell> userRecord(InboundMessage m){AMap<AString,ACell> u=Maps.of(K_ID,Strings.create(m.authorId()),K_USERNAME,Strings.create(m.username()));if(m.globalName()!=null)u=u.assoc(Strings.intern("global_name"),Strings.create(m.globalName()));return u;}
	private ACell chatAgent(String channelId,ACell message){RequestContext ctx=context();String sid=sessionFor(channelId,ctx);AMap<AString,ACell> in=Maps.of(Fields.AGENT_ID,Strings.create(spec.agent()),Fields.MESSAGE,message);if(sid!=null)in=in.assoc(Fields.SESSION_ID,Strings.create(sid));ACell result;try{result=runJob(AGENT_CHAT,in,ctx);}catch(RuntimeException e){if(sid!=null&&isUnknownSession(e)){forgetSession(channelId,ctx);result=runJob(AGENT_CHAT,in.dissoc(Fields.SESSION_ID),ctx);}else throw e;}AString ns=RT.ensureString(RT.getIn(result,Fields.SESSION_ID));if(ns!=null&&!ns.toString().equals(sid))rememberSession(channelId,ns.toString(),ctx);return RT.getIn(result,Fields.RESPONSE);}
	private ACell runJob(String operation,ACell input,RequestContext ctx){Job j=adapter.engine.jobs().invokeOperation(operation,input,ctx);ACell result=j.awaitResult();if(j.getStatus()!=Status.COMPLETE)throw new JobFailedException(operation+" "+j.getStatus()+(j.getErrorMessage()!=null?": "+j.getErrorMessage():""));return result;}
	RequestContext context(){return RequestContext.of(spec.userDID(adapter.engine));}

	String sessionsPath(){return adapter.state().path(sessionsRelativePath());}
	private String sessionsRelativePath(){return managed==Managed.CONFIG
		? "config/"+spec.name()+"/sessions"
		: adapter.userStatePath(spec.userDID(adapter.engine),"sessions/"+spec.name());}
	private String sessionRelativePath(String channel){return sessionsRelativePath()+"/"+channel;}
	private String sessionFor(String channel,RequestContext ctx){String sid=sessions.get(channel);if(sid!=null)return sid;ACell v=adapter.state().read(sessionRelativePath(channel));AString s=RT.ensureString(v);if(s!=null&&!s.isEmpty()){sessions.put(channel,s.toString());return s.toString();}return null;}
	private void rememberSession(String channel,String sid,RequestContext ctx){sessions.put(channel,sid);adapter.state().write(sessionRelativePath(channel),Strings.create(sid));}
	private void forgetSession(String channel,RequestContext ctx){sessions.remove(channel);adapter.state().delete(sessionRelativePath(channel));}
	private static boolean isUnknownSession(Throwable t){for(Throwable c=t;c!=null&&c.getCause()!=c;c=c.getCause())if(c.getMessage()!=null&&c.getMessage().contains("Unknown session"))return true;return false;}

	ACell sendMessage(String channelId,AMap<AString,ACell> body){AString content=RT.ensureString(body.get(Strings.intern("content")));if(content==null||content.isBlank())throw new IllegalArgumentException("content is required");ACell last=null;boolean first=true;for(String chunk:split(content.toString(),MAX_MESSAGE_LENGTH)){AMap<AString,ACell> part=body.assoc(Strings.intern("content"),Strings.create(chunk));if(!first)part=part.dissoc(Strings.intern("message_reference"));last=call("POST","/channels/"+channelId+"/messages",part);sent.incrementAndGet();first=false;}return last;}
	private ACell send(String channel,String text,String reply){AMap<AString,ACell> body=Maps.of(Strings.intern("content"),Strings.create(text));if(reply!=null)body=body.assoc(Strings.intern("message_reference"),Maps.of(Strings.intern("message_id"),Strings.create(reply),Strings.intern("fail_if_not_exists"),CVMBool.FALSE));return sendMessage(channel,body);}
	private void sendQuietly(String channel,String text,String reply){try{send(channel,text,reply);}catch(RuntimeException e){log.debug("Discord bot '{}': notice failed: {}",spec.name(),concise(e));}}

	ACell call(String method,String route,ACell body){HttpClient client=http;String tok=token;if(client==null||tok==null)throw new IllegalStateException("Discord bot '"+spec.name()+"' is not running"+(error!=null?": "+error:""));return execute(client,tok,method,route,body,true);}
	private ACell execute(HttpClient client,String tok,String method,String route,ACell body,boolean retry429){
		try{HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(apiUrl+route)).timeout(Duration.ofSeconds(45)).header("Authorization","Bot "+tok).header("User-Agent","Covia (https://covia.ai, 0.9)");String json=body==null?"":JSON.print(body).toString();if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json));HttpResponse<String> r=client.send(b.build(),HttpResponse.BodyHandlers.ofString());
			if(r.statusCode()==429&&retry429){long wait=1000;try{ACell parsed=JSON.parse(r.body());Object ra=JSON.json(RT.getIn(parsed,Strings.intern("retry_after")));if(ra instanceof Number n)wait=Math.min(30_000,Math.max(100,Math.round(n.doubleValue()*1000)));}catch(RuntimeException ignored){}Thread.sleep(wait);return execute(client,tok,method,route,body,false);}
			if(r.statusCode()<200||r.statusCode()>=300){failed.incrementAndGet();throw new JobFailedException("Discord "+method+" "+route+" failed: HTTP "+r.statusCode()+" "+trim(r.body()));}
			if(r.body()==null||r.body().isBlank())return CVMBool.TRUE;return JSON.parse(r.body());
		}catch(InterruptedException e){Thread.currentThread().interrupt();throw new JobFailedException("Discord call interrupted");}catch(java.io.IOException|IllegalArgumentException e){throw new JobFailedException("Discord "+method+" "+route+" failed: "+concise(e));}
	}
	static List<String> split(String text,int max){List<String> out=new ArrayList<>();String rest=text;while(rest.length()>max){int cut=rest.lastIndexOf('\n',max);if(cut<max/2)cut=rest.lastIndexOf(' ',max);if(cut<max/2)cut=max;out.add(rest.substring(0,cut));rest=rest.substring(cut).stripLeading();}out.add(rest);return out;}
	static String concise(Throwable t){Throwable c=t;while((c instanceof CompletionException||c instanceof ExecutionException)&&c.getCause()!=null)c=c.getCause();String msg=AAdapter.describeFailure(c);return msg.length()>400?msg.substring(0,400)+"…":msg;}
	private static String trim(String s){if(s==null)return "";return s.length()>600?s.substring(0,600)+"…":s;}
	private void logFailure(String what,InboundMessage m,Throwable t){log.warn("Discord bot '{}': {} for message {} in channel {}: {}",spec.name(),what,m.id(),m.channelId(),concise(t));}
}
