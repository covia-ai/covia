package covia.adapter.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

@Execution(ExecutionMode.SAME_THREAD)
class DiscordAdapterTest {
	private static final AString OWNER=Strings.create("did:test:discord:owner"),OTHER=Strings.create("did:test:discord:other");
	private static Engine engine;private static DiscordAdapter adapter;private static FakeGatewayHub gateway;private static FakeDiscordAPI api;

	@BeforeAll static void boot() throws Exception {
		api=new FakeDiscordAPI();gateway=new FakeGatewayHub();
		AMap<AString,ACell> config=Maps.of(Config.USERS,Maps.of(Config.AUTO_CREATE,true),Config.ADAPTERS,Maps.of("discord",Maps.of(
			"apiUrl",api.url(),"bots",Maps.of("echo",Maps.of("token","literal-test-token","user",OWNER,"operation","v/test/ops/echo","allow",Vectors.of("alice"))))));
		engine=Engine.createTemp(config);adapter=new DiscordAdapter();adapter.gatewayFactory=gateway;adapter.retryMillis=50;engine.registerAdapter(adapter);Engine.addDemoAssets(engine);
		awaitRunning();
	}
	@AfterAll static void close(){if(engine!=null)engine.close();if(api!=null)api.close();}
	@BeforeEach void clearRequests(){api.requests.clear();}

	@Test void validatesSpecsAndDoesNotLeakToken(){
		assertThrows(IllegalArgumentException.class,()->BotSpec.parse("x",Maps.of("user",OWNER,"agent","a"),false));
		assertThrows(IllegalArgumentException.class,()->BotSpec.parse("x",Maps.of("token","t","user",OWNER,"agent","a","operation","o"),false));
		BotSpec s=BotSpec.parse("x",Maps.of("token","s/DISCORD","user",OWNER,"agent","a","allow",Vectors.of("123","@Alice")),true);
		assertTrue(s.mentionOnly());assertTrue(s.allows("123",null,null));assertTrue(s.allows("9","ALICE",null));assertFalse(s.allows("9","bob",null));assertFalse(s.toString().contains("DISCORD"));
		DiscordAdapter fresh=new DiscordAdapter();
		assertThrows(IllegalArgumentException.class,()->fresh.configure(Maps.of("statePath","w/elsewhere"),false));
		fresh.close();
	}

	@Test void inboundAccessAndGuildMentionsAreFailClosed() throws Exception {
		gateway.emit(message("1","chan-dm","alice","hello discord",true,false));
		Request sent=api.requests.poll(10,TimeUnit.SECONDS);assertNotNull(sent);assertEquals("/api/v10/channels/chan-dm/messages",sent.path());assertTrue(sent.body().contains("hello discord"));

		gateway.emit(message("2","guild-channel","alice","ignored",false,false));
		assertEquals(null,api.requests.poll(400,TimeUnit.MILLISECONDS),"guild messages require a mention by default");
		gateway.emit(message("3","guild-channel","mallory","<@999> denied",false,true));
		assertEquals(null,api.requests.poll(400,TimeUnit.MILLISECONDS),"disallowed guild users get no response");
		gateway.emit(message("4","guild-channel","alice","<@999> addressed",false,true));
		Request guild=api.requests.poll(10,TimeUnit.SECONDS);assertNotNull(guild);assertTrue(guild.body().contains("addressed"));assertFalse(guild.body().contains("&lt;@999&gt;"));
	}

	@Test void outboundSendSplitsAndUsesBotAuthorization(){
		String content="x".repeat(2001);ACell result=run(OWNER,"v/ops/discord/send",Maps.of("channel_id","out","content",content,"reply_to","55"));assertNotNull(result);
		Request first=take(),second=take();assertEquals("Bot literal-test-token",first.authorization());assertTrue(first.body().contains("message_reference"));assertFalse(second.body().contains("message_reference"));
		assertTrue(first.body().length()>second.body().length());
	}

	@Test void arbitraryCallIsCapabilityGatedAndManagedRoutesAreRefused(){
		ACell result=run(OWNER,"v/ops/discord/call",Maps.of("method","GET","route","/channels/123"));assertNotNull(result);assertEquals("GET",take().method());
		assertFailed(OTHER,"v/ops/discord/send",Maps.of("bot","echo","channel_id","x","content","no"));
		assertFailed(OWNER,"v/ops/discord/call",Maps.of("method","GET","route","/gateway/bot"));
	}

	@Test void catalogStatusSkillAndTemplateAreInstalled(){
		ACell status=run(OWNER,"v/ops/discord/bots",Maps.empty());assertTrue(status.toString().contains("RUNNING"));assertFalse(status.toString().contains("literal-test-token"));
		assertNotNull(engine.resolvePath(Strings.create("v/skills/adapters/discord"),engine.venueContext()));
		assertNotNull(engine.resolvePath(Strings.create("v/agents/templates/discord"),engine.venueContext()));
		assertEquals(List.of("abc","def"),BotRunner.split("abc def",3));
	}

	@Test void runtimeBotsRequireSecretReferencesAndCanBeDeleted() throws Exception {
		assertFailed(OWNER,"v/ops/discord/create",Maps.of("name","bad","token","literal","operation","v/test/ops/echo"));
		run(OWNER,"v/ops/secret/set",Maps.of("name","DISCORD_CREATED","value","created-token"));
		run(OWNER,"v/ops/discord/create",Maps.of("name","created","token","s/DISCORD_CREATED","operation","v/test/ops/echo","open",true));
		for(int i=0;i<100&&!gateway.sinks.containsKey("created-token");i++)Thread.sleep(20);
		assertTrue(gateway.sinks.containsKey("created-token"));
		String recordPath=adapter.runtimeBotPath(OWNER,"created");
		ACell record=engine.resolvePath(Strings.create(recordPath),engine.venueContext());
		assertNotNull(record);assertEquals(Strings.create("s/DISCORD_CREATED"),RT.getIn(record,"token"));assertEquals(null,RT.getIn(record,"user"));
		assertEquals(null,engine.resolvePath(Strings.create(recordPath),RequestContext.of(OWNER)));
		adapter.forgetForTest(OWNER,"created");assertEquals(null,adapter.runnerForTest(OWNER,"created"));
		adapter.rearmForTest();assertNotNull(adapter.runnerForTest(OWNER,"created"));
		ACell deleted=run(OWNER,"v/ops/discord/delete",Maps.of("name","created"));assertEquals(CVMBool.TRUE,RT.getIn(deleted,"deleted"));
		assertFalse(gateway.sinks.containsKey("created-token"));
		assertEquals(null,engine.resolvePath(Strings.create(recordPath),engine.venueContext()));
	}

	private static InboundMessage message(String id,String channel,String username,String text,boolean dm,boolean mention){return new InboundMessage(id,channel,dm?"PRIVATE":"TEXT",dm?username:"general",dm?null:"guild-1",dm?null:"Test Guild","42",username,username,text,dm,mention,Vectors.empty());}
	private static void awaitRunning() throws Exception {for(int i=0;i<100;i++){BotRunner r=adapter.runnerForTest("echo");if(r!=null&&r.status().toString().contains("RUNNING"))return;Thread.sleep(20);}throw new AssertionError("bot did not start");}
	private static ACell run(AString user,String op,AMap<AString,ACell> in){Job j=engine.jobs().invokeOperation(op,in,RequestContext.of(user));ACell out=j.awaitResult(20_000);if(j.getStatus()!=Status.COMPLETE)throw new AssertionError(op+" failed: "+j.getErrorMessage());return RT.cvm(out);}
	private static void assertFailed(AString user,String op,AMap<AString,ACell> in){Job j=engine.jobs().invokeOperation(op,in,RequestContext.of(user));try{j.awaitResult(20_000);}catch(RuntimeException expected){}assertTrue(j.getStatus()==Status.FAILED||j.getStatus()==Status.REJECTED,j.getStatus()+": "+j.getErrorMessage());}
	private static Request take(){try{Request r=api.requests.poll(10,TimeUnit.SECONDS);assertNotNull(r);return r;}catch(InterruptedException e){throw new AssertionError(e);}}

	private static final class FakeGatewayHub implements DiscordGateway.Factory {
		final ConcurrentHashMap<String,Consumer<InboundMessage>> sinks=new ConcurrentHashMap<>();
		@Override public DiscordGateway connect(String token,Consumer<InboundMessage> messages){sinks.put(token,messages);return new DiscordGateway(){public String botId(){return "999";}public String username(){return "covia-test";}public void close(){sinks.remove(token,messages);}};}
		void emit(InboundMessage m){Consumer<InboundMessage> s=sinks.get("literal-test-token");if(s==null)throw new AssertionError("not connected");s.accept(m);}
	}
	private record Request(String method,String path,String authorization,String body){}
	private static final class FakeDiscordAPI implements AutoCloseable {
		final HttpServer server;final BlockingQueue<Request> requests=new LinkedBlockingQueue<>();
		FakeDiscordAPI() throws IOException {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/v10",this::handle);server.start();}
		String url(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/api/v10";}
		void handle(HttpExchange x)throws IOException{String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requests.add(new Request(x.getRequestMethod(),x.getRequestURI().getPath(),x.getRequestHeaders().getFirst("Authorization"),body));byte[] out=("{\"id\":\"sent-1\",\"channel_id\":\"x\",\"content\":"+(body.isBlank()?"\"\"":extractContent(body))+"}").getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,out.length);x.getResponseBody().write(out);x.close();}
		private static String extractContent(String body){int i=body.indexOf("\"content\"");if(i<0)return "\"\"";int c=body.indexOf(':',i),q=body.indexOf('"',c),end=q+1;boolean esc=false;for(;end<body.length();end++){char ch=body.charAt(end);if(ch=='"'&&!esc)break;esc=ch=='\\'&&!esc;if(ch!='\\')esc=false;}return body.substring(q,Math.min(body.length(),end+1));}
		@Override public void close(){server.stop(0);}
	}
}
