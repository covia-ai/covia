package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import convex.core.data.*;
import convex.core.lang.RT;
import covia.adapter.agent.ExternalAgents;
import covia.adapter.remote.*;
import covia.api.Fields;
import covia.grid.*;
import covia.venue.*;

/** One venue for the class, since each test registers a backend of its own, under its own name. */
class AgentFacetTest {
    private static final AtomicInteger PROBES = new AtomicInteger();
    private static Engine engine;
    private RequestContext ctx;
    private Probe probe;
    @BeforeAll static void start() { engine = Engine.createTemp(null); Engine.addDemoAssets(engine); }
    @AfterAll static void stop() { engine.close(); }
    @BeforeEach void setup(TestInfo info) {
        ctx = RequestContext.of(TestEngine.uniqueDID(info));
        engine.getVenueState().users().ensure(ctx.getUserDID());
        probe = new Probe("facetprobe-" + PROBES.incrementAndGet());
        engine.registerAdapter(probe);
    }
    private AMap<AString, ACell> facet() { return Maps.of(Fields.ADAPTER, probe.getName()); }
    private Job invoke(String operation, AMap<AString, ACell> input) {
        return engine.jobs().invokeOperation("v/ops/agent/" + operation, input, ctx);
    }
    private ACell run(String operation, AMap<AString, ACell> input) throws Exception {
        return invoke(operation, input).awaitResult(5000);
    }
    private AMap<AString, ACell> target() { return Maps.of(Fields.AGENT_ID, "external"); }
    private void create() throws Exception {
        run("create", target().assoc(Agent.KEY_AGENT, facet()));
    }
    private Agent agent(String id) { return engine.getVenueState().users().user(ctx.getUserDID()).agent(Strings.create(id)); }

    static class Probe extends ARemoteAgentAdapter<RemoteInteraction> {
        final AtomicInteger starts = new AtomicInteger(), cancels = new AtomicInteger();
        volatile Job child;
        volatile ACell previous;
        volatile ACell message;
        boolean waiting;
        private final String name;
        Probe(String name) { this.name = name; }
        public String getName() { return name; }
        public String getDescription() { return "Agent facade test backend"; }
        protected boolean isInteractionOperation(String op) { return true; }
        public AgentInvocation agentInvocation(String operation, AMap<AString, ACell> definition, ACell content, ACell session) {
            previous = session;
            return new AgentInvocation("turn", content);
        }
        public ACell agentSession(Job job) { return Strings.create("provider-session"); }
        protected void startInteraction(Job job, RequestContext context, AMap<AString, ACell> meta, ACell input) {
            starts.incrementAndGet(); child = job;
            bindInteraction(job, new RemoteInteraction() {
                public void cancel() { cancels.incrementAndGet(); }
                public void deliver(AMap<AString, ACell> record) { message = record.get(Fields.MESSAGE); }
            });
            if (waiting) job.setStatus(Status.INPUT_REQUIRED);
            else job.completeWith(input);
        }
        public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
            throw new UnsupportedOperationException();
        }
    }

    @Test void absentFacetPreservesNativeRecordAndTaskContract() throws Exception {
        run("create", Maps.of(Fields.AGENT_ID, "legacy", Fields.CONFIG,
            Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")));
        assertFalse(agent("legacy").getRecord().containsKey(Agent.KEY_AGENT));
        ACell result = run("request", Maps.of(Fields.AGENT_ID, "legacy", Fields.INPUT, Maps.of("task", "hello")));
        assertEquals(Strings.create("hello"), RT.getIn(result, Fields.OUTPUT, "completed", "task"));
        assertEquals(0, probe.starts.get());
    }

    @Test void invalidFacetNeverFallsBackOrCreatesRecord() {
        for (ACell value : new ACell[]{null, Strings.create("a2a"), Maps.empty(), Maps.of(Fields.ADAPTER, "missing"), Maps.of(Fields.ADAPTER, "test")}) {
            Job job = invoke("create", target().assoc(Agent.KEY_AGENT, value));
            assertEquals(Status.FAILED, job.getStatus());
            assertNull(agent("external"));
        }
        assertEquals(0, probe.starts.get());
    }

    @Test void chatKeepsCoviaIdAndPersistsProviderIdentityAndTranscript() throws Exception {
        create();
        ACell first = run("chat", target().assoc(Fields.MESSAGE, Strings.create("one")));
        assertEquals(Strings.create("one"), RT.getIn(first, Fields.RESPONSE));
        ACell sid = RT.getIn(first, Fields.SESSION_ID);
        assertNotEquals(Strings.create("provider-session"), sid);
        ACell second = run("chat", target().assoc(Fields.MESSAGE, Strings.create("two")).assoc(Fields.SESSION_ID, sid));
        assertEquals(sid, RT.getIn(second, Fields.SESSION_ID));
        assertEquals(Strings.create("provider-session"), probe.previous);
        ACell history = run("history", target().assoc(Fields.SESSION_ID, sid));
        assertEquals(4, RT.ensureVector(RT.getIn(history, "entries")).count());
        assertEquals(facet(), RT.getIn(run("info", target()), Agent.KEY_AGENT));
    }

    @Test void busySessionContinuationAndCancellationUseChildWithoutAnotherSubmission() throws Exception {
        create(); probe.waiting = true;
        Job first = invoke("request", target().assoc(Fields.INPUT, Maps.of("task", "wait")));
		assertEquals(Status.INPUT_REQUIRED, first.getStatus(), first.getErrorMessage());
        engine.getAdapter("agent").invoke(first, ctx, Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "agent:request")),
            target().assoc(Fields.INPUT, Maps.of("task", "duplicate")));
        assertEquals(1, probe.starts.get());
        ACell sid = first.getData().get(Fields.SESSION_ID);
        Job next = invoke("chat", target().assoc(Fields.SESSION_ID, sid).assoc(Fields.MESSAGE, Strings.create("overlap")));
        assertEquals(ExternalAgents.SESSION_BUSY, next.getErrorMessage());
        run("message", target().assoc(Fields.TASK_ID, Strings.create(first.getID().toHexString())).assoc(Fields.MESSAGE, Strings.create("continue")));
        assertEquals(Strings.create("continue"), probe.message);
        first.cancel();
        assertEquals(Status.CANCELLED, probe.child.getStatus());
        assertEquals(1, probe.cancels.get());
        assertEquals(1, probe.starts.get());
        probe.waiting = false;
        run("chat", target().assoc(Fields.SESSION_ID, sid).assoc(Fields.MESSAGE, Strings.create("again")));
        assertEquals(2, probe.starts.get());
    }

    @Test void messageToAnExternalAgentRefusesATaskIdThatIsNotHex() throws Exception {
        create();
        Job job = invoke("message", target().assoc(Fields.TASK_ID, Strings.create("not-hex")).assoc(Fields.MESSAGE, Strings.create("x")));
        assertEquals(Status.FAILED, job.getStatus());
        assertEquals(AgentAdapter.INVALID_TASK_ID + "not-hex", job.getErrorMessage());
    }

    @Test void messageToAgentWhoseBackendIsUnloadedIsUnavailable() throws Exception {
        create(); probe.waiting = true;
        Job first = invoke("request", target().assoc(Fields.INPUT, Maps.of("task", "wait")));
        assertEquals(Status.INPUT_REQUIRED, first.getStatus(), first.getErrorMessage());
        engine.removeAdapter(probe.getName());
        covia.exception.UnavailableException e = assertThrows(covia.exception.UnavailableException.class,
            () -> engine.jobs().deliverMessage(first.getID(), Maps.of(Fields.MESSAGE, Strings.create("continue")), ctx));
        assertEquals(ExternalAgents.UNAVAILABLE + probe.getName(), e.getMessage());
    }

    @Test void messageToAgentWithBrokenStoredDefinitionSaysItIsMisconfigured() throws Exception {
        create(); probe.waiting = true;
        Job first = invoke("request", target().assoc(Fields.INPUT, Maps.of("task", "wait")));
        assertEquals(Status.INPUT_REQUIRED, first.getStatus(), first.getErrorMessage());
        Agent agent = agent("external");
        agent.putRecord(agent.getRecord().assoc(Agent.KEY_AGENT, Maps.of(Fields.ADAPTER, "")));
        covia.exception.InvalidDefinitionException e = assertThrows(covia.exception.InvalidDefinitionException.class,
            () -> engine.jobs().deliverMessage(first.getID(), Maps.of(Fields.MESSAGE, Strings.create("continue")), ctx));
        assertEquals(ExternalAgents.MISCONFIGURED + ExternalAgents.INVALID, e.getMessage());
    }

    @Test void badOptionsUnknownSessionAndNativeOnlyOperationsAreExplicitFailures() throws Exception {
        create();
        Job unknown = invoke("chat", target().assoc(Fields.SESSION_ID, Strings.create("00112233445566778899aabbccddeeff"))
            .assoc(Fields.MESSAGE, Strings.create("hello")));
        assertEquals(ExternalAgents.UNKNOWN_SESSION, unknown.getErrorMessage());
        Job unsupported = invoke("request", target().assoc(Fields.INPUT, Maps.empty()).assoc(Fields.OUTPUT_PATH, Strings.create("w/result")));
        assertEquals(ExternalAgents.UNSUPPORTED + "outputPath", unsupported.getErrorMessage());
        assertEquals(ExternalAgents.UNSUPPORTED + "trigger", invoke("trigger", target()).getErrorMessage());
        assertEquals(0, probe.starts.get());
    }

    @Test void configuredAgentCapsBoundBackendInvocation() throws Exception {
        run("create", target().assoc(Agent.KEY_AGENT, facet())
            .assoc(Fields.CONFIG, Maps.of("caps", Vectors.empty())));
        Job denied = invoke("chat", target().assoc(Fields.MESSAGE, Strings.create("hello")));
        assertEquals(Status.FAILED, denied.getStatus());
        assertEquals(0, probe.starts.get());
    }

    @Test void suspensionAndDeletionCancelBackendAndDoNotWakeNativeLoop() throws Exception {
        create(); probe.waiting = true;
        Job first = invoke("request", target().assoc(Fields.INPUT, Maps.empty()));
        run("suspend", target());
        assertEquals(Status.CANCELLED, first.getStatus());
        assertEquals(Status.CANCELLED, probe.child.getStatus());
        run("resume", target());
        Job next = invoke("request", target().assoc(Fields.INPUT, Maps.empty()));
        run("delete", target());
        assertEquals(Status.CANCELLED, next.getStatus());
        assertEquals(Agent.TERMINATED, agent("external").getStatus());
        assertEquals(2, probe.starts.get());
    }

    @Test void providerFailureSettlesParentAndReleasesSession() throws Exception {
        create(); probe.waiting = true;
        Job first = invoke("request", target().assoc(Fields.INPUT, Maps.empty()));
        probe.child.fail(ExternalAgents.MISSING_CHILD);
        assertEquals(Status.FAILED, first.getStatus());
        assertEquals(ExternalAgents.MISSING_CHILD, first.getErrorMessage());
        probe.waiting = false;
        run("chat", target().assoc(Fields.SESSION_ID, first.getData().get(Fields.SESSION_ID)).assoc(Fields.MESSAGE, Strings.create("retry")));
    }

    @Test void definitionAssetCopiesFacetAndLegacyTemplateStillCreatesNativeAgent() throws Exception {
        engine.jobs().invokeOperation("v/ops/covia/write", Maps.of(Fields.PATH, "w/external-definition",
            Fields.VALUE, Maps.of("name", "Helper", "agent", facet())), ctx).awaitResult(5000);
        run("create", target().assoc(Fields.DEFINITION, Strings.create("w/external-definition")));
        assertEquals(facet(), agent("external").getRecord().get(Agent.KEY_AGENT));
        run("create", Maps.of(Fields.AGENT_ID, "legacy-template", Fields.CONFIG, "v/agents/templates/skilled"));
        assertFalse(agent("legacy-template").getRecord().containsKey(Agent.KEY_AGENT));
    }

    @Test void a2aFacetUsesCommonApiAndRetainsContext() throws Exception {
        ((HTTPAdapter) engine.getAdapter("http")).addAllowedHost("127.0.0.1");
        var received = new java.util.concurrent.atomic.AtomicReference<ACell>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rpc", exchange -> {
            ACell body = convex.core.util.JSON.parse(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            received.set(body);
            byte[] reply = convex.core.util.JSON.toString(Maps.of("jsonrpc", "2.0", "id", RT.getIn(body, "id"),
                "result", Maps.of("message", Maps.of("messageId", "reply", "contextId", "provider-context", "role", "ROLE_AGENT",
                    "parts", Vectors.of(Maps.of("text", "hello")))))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            run("create", target().assoc(Agent.KEY_AGENT, Maps.of(Fields.ADAPTER, "a2a",
                "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/rpc", "card", Maps.empty())));
            Job firstJob = invoke("chat", target().assoc(Fields.MESSAGE, Strings.create("first")));
            ACell first;
            try { first = firstJob.awaitResult(5000); }
            catch (Exception e) {
                ACell childId = RT.getIn(engine.jobs().adapterData(firstJob), ExternalAgents.CHILD);
                Job child = childId == null ? null : engine.jobs().getJob(Job.parseID(childId), ctx);
                throw new AssertionError("parent=" + firstJob.getData() + " child=" + (child == null ? null : child.getData()) + " received=" + received.get(), e);
            }
            ACell sid = RT.getIn(first, Fields.SESSION_ID);
            assertEquals(Strings.create("provider-context"), RT.getIn(first, Fields.RESPONSE, "contextId"));
            run("chat", target().assoc(Fields.SESSION_ID, sid).assoc(Fields.MESSAGE, Strings.create("second")));
            assertEquals(Strings.create("provider-context"), RT.getIn(received.get(), "params", "message", "contextId"));
        } finally { server.stop(0); }
    }

    @Test void realNativeProxyUsesExistingAgentRequestSurface() throws Exception {
        run("create", Maps.of(Fields.AGENT_ID, "native", Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete")));
        run("create", target().assoc(Agent.KEY_AGENT, Maps.of(Fields.ADAPTER, "remoteagent", Fields.AGENT_ID, "native")));
        ACell output = run("request", target().assoc(Fields.INPUT, Maps.of("task", "via proxy")));
        assertEquals(Strings.create("via proxy"), RT.getIn(output, Fields.OUTPUT, "completed", "task"));
    }
}
