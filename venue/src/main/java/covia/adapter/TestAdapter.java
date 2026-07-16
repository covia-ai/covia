package covia.adapter;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;

public class TestAdapter extends AAdapter {
	
	public static final Logger log=LoggerFactory.getLogger(TestAdapter.class);

    /**
     * Test-only sink: the {@code capturectx} op records the {@link RequestContext}
     * it was invoked under, keyed by the owner DID it ran as (its caller DID).
     * Lets tests assert deterministically which identity / proofs / caps a
     * transition actually ran with — including that two same-named agents owned
     * by different users run under their own identities (see #91).
     */
    public static final java.util.concurrent.ConcurrentHashMap<AString, RequestContext> CAPTURED_CTX
        = new java.util.concurrent.ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();
    
    @Override
    public String getName() {
        return "test";
    }
    
    @Override
    public String getDescription() {
        return "Provides various test operations for development and debugging. " +
               "Supports echo operations, random data generation, error simulation, delay operations, and never-completing tasks. " +
               "Perfect for testing async behavior, error handling, and orchestration workflows in the Covia platform.";
    }

    @Override
    public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
        requireInvoke(ctx);
        String testOp = getSubOperation(meta);

        // Handle different test operations
        try {
            switch (testOp) {
                case "echo":
                    return CompletableFuture.completedFuture(handleEcho(input));
                case "capturectx":
                    return CompletableFuture.completedFuture(handleCaptureCtx(ctx, input));
                case "taskcomplete":
                    return CompletableFuture.completedFuture(handleTaskComplete(ctx, input));
                case "wakeresponse":
                    return CompletableFuture.completedFuture(handleWakeResponse(input));
                case "llm":
                    return CompletableFuture.completedFuture(withMockTokens(handleLlm(input), input));
                case "toolllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleToolLlm(input), input));
                case "badargsllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleBadArgsLlm(input), input));
                case "loopllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleLoopLlm(input), input));
                case "nevertoolllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleNeverToolLlm(input, false), input));
                case "neverfailllm": {
                    ACell r = handleNeverToolLlm(input, true);
                    return (r == null)
                        ? CompletableFuture.failedFuture(new IllegalStateException("scripted L3 failure"))
                        : CompletableFuture.completedFuture(withMockTokens(r, input));
                }
                case "subgoalllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleSubgoalLlm(input), input));
                case "taskllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleTaskLlm(input), input));
                case "textctlllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleTextCtlLlm(input), input));
                case "stubbornllm":
                    return CompletableFuture.completedFuture(withMockTokens(Maps.of(
                        "role", Strings.create("assistant"),
                        "content", Strings.create("Working on it.")), input));
                case "workspacellm":
                    return CompletableFuture.completedFuture(withMockTokens(handleWorkspaceLlm(input), input));
                case "compactllm":
                    return CompletableFuture.completedFuture(withMockTokens(handleCompactLlm(input), input));
                case "selfchat":
                    return CompletableFuture.completedFuture(withMockTokens(handleSelfChatLlm(ctx, input), input));
                case "never":
                    return new CompletableFuture<>();
                case "delay":
                    // Handled via invoke() override for caller DID propagation
                    return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("delay operation uses invoke path for caller DID propagation")
                    );
                case "error":
                    return CompletableFuture.failedFuture(handleError(input));
                case "random":
                    return CompletableFuture.completedFuture(handleRandom(input));
                case "chat":
                    // Multi-turn: handled via invoke() override, not invokeFuture()
                    return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("chat operation uses multi-turn invoke path")
                    );
                default:
                    return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Unknown test operation: " + testOp)
                    );
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override public void installAssets() {
		String BASE="/asset-examples/";

		try {
			// Test primitives — registered under /v/test/ops/<name>, not /v/ops/.
			installTestAsset("random",       BASE+"randomop.json");
			installTestAsset("echo",         BASE+"echoop.json");
			installTestAsset("capturectx",   BASE+"capturectxop.json");
			installTestAsset("llm",          BASE+"testllm.json");
			installTestAsset("toolllm",      BASE+"testtoolllm.json");
			installTestAsset("badargsllm",   BASE+"testbadargsllm.json");
			installTestAsset("loopllm",      BASE+"testloopllm.json");
			installTestAsset("nevertoolllm", BASE+"testnevertoolllm.json");
			installTestAsset("neverfailllm", BASE+"testneverfailllm.json");
			installTestAsset("subgoalllm",   BASE+"testsubgoalllm.json");
			installTestAsset("taskllm",      BASE+"testtaskllm.json");
			installTestAsset("textctlllm",   BASE+"testtextctlllm.json");
			installTestAsset("stubbornllm",  BASE+"teststubbornllm.json");
			installTestAsset("workspacellm", BASE+"testworkspacellm.json");
			installTestAsset("compactllm",   BASE+"testcompactllm.json");
			installTestAsset("selfchat",     BASE+"testselfchat.json");
			installTestAsset("never",        BASE+"neverop.json");
			installTestAsset("delay",        BASE+"delayop.json");
			installTestAsset("error",        BASE+"failop.json");
			installTestAsset("chat",         BASE+"chatop.json");
			installTestAsset("pause",        BASE+"pauseop.json");
			installTestAsset("taskcomplete", BASE+"taskcomplete.json");
			installTestAsset("wakeresponse", BASE+"wakeresponse.json");

			// Demos and content fixtures — stored in CAS only, not in any catalog.
			installExampleAsset(BASE+"empty.json");
			installExampleAsset(BASE+"orch.json");
			Hash iris=engine.storeAsset(Utils.readResourceAsAString(BASE+"iris.json"),null);
			engine.putContent(iris,this.getClass().getResourceAsStream(BASE+"iris.csv"));
			Hash shake=engine.storeAsset(Utils.readResourceAsAString(BASE+"shakespeare.json"),null);
			engine.putContent(shake,this.getClass().getResourceAsStream(BASE+"hamlet.txt"));
		} catch(Exception e) {
			log.warn("Failed to install test assets",e);
		}
    }
    
    @Override
    public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
        requireInvoke(ctx);
        String subOp = getSubOperation(meta);
        if ("chat".equals(subOp)) {
            // Multi-turn: set INPUT_REQUIRED and wait for messages
            job.setStatus(Status.INPUT_REQUIRED);
        } else if ("pause".equals(subOp)) {
            // Auto-pause: immediately pauses, stores input for later completion
            job.update(data -> data.assoc(Fields.INPUT, input));
            job.setStatus(Status.PAUSED);
        } else if ("delay".equals(subOp)) {
            // Delay: needs Job for caller DID propagation to sub-invocation
            handleDelay(job, ctx, input);
        } else {
            // Default one-shot path
            super.invoke(job, ctx, meta, input);
        }
    }

    @Override
    public void handleMessage(Job job, AMap<AString, ACell> messageRecord) {
        // If job was paused, resume and complete with original input
        if (Status.PAUSED.equals(job.getStatus())) {
            ACell originalInput = job.getData().get(Fields.INPUT);
            job.completeWith(originalInput);
            return;
        }

        ACell message = messageRecord.get(Fields.MESSAGE);
        ACell content = RT.getIn(message, "content");

        // If message content is "done", complete the job with a summary
        if (content != null && "done".equals(content.toString())) {
            job.completeWith(Maps.of("status", Strings.create("conversation complete")));
        } else {
            // Echo the message back and stay in INPUT_REQUIRED
            job.update(data -> {
                data = data.assoc(Fields.STATUS, Status.INPUT_REQUIRED);
                data = data.assoc(Fields.OUTPUT, Maps.of(
                    "echo", content,
                    "message", Strings.create("Send more messages or 'done' to finish")
                ));
                return data;
            });
        }
    }

    @Override
    public boolean supportsMultiTurn() {
        return true;
    }

    private void handleDelay(Job job, RequestContext ctx, ACell input) {
    	// Use submit() (not CompletableFuture.runAsync) so the returned Future
    	// is interruptible — the cancel hook below calls f.cancel(true), which
    	// interrupts Thread.sleep. CompletableFuture from runAsync ignores
    	// mayInterruptIfRunning and would leave the sleep running.
    	java.util.concurrent.Future<?> f = VIRTUAL_EXECUTOR.submit(() -> {
    		try {
				ACell op = RT.getIn(input, Fields.OPERATION);
				ACell opInput = RT.getIn(input, Fields.INPUT);
				CVMLong delay = CVMLong.parse(RT.getIn(input, Fields.DELAY));
				Thread.sleep(delay.longValue());
				ACell result = engine.jobs().invokeInternal(RT.ensureString(op), opInput, ctx).join();
				job.completeWith(result);
    		} catch (InterruptedException e) {
    			// cancelled — Job.cancel already updated lattice status
    			Thread.currentThread().interrupt();
    		} catch (Exception e) {
    			job.fail(e.getMessage());
    		}
    	});
    	job.setCancelHook(() -> f.cancel(true));
	}

	private ACell handleEcho(ACell input) {
        // Simply return the input
        return input;
    }

    /**
     * Records the invoking {@link RequestContext} keyed by agentId, then behaves
     * like echo so the run loop merges cleanly and sleeps. Used by tests to
     * assert the identity / proofs / caps a transition ran under.
     */
    private ACell handleCaptureCtx(RequestContext ctx, ACell input) {
        if (ctx.getCallerDID() != null) CAPTURED_CTX.put(ctx.getCallerDID(), ctx);
        return input;
    }

    /**
     * Test transition that completes the picked task via the
     * {@code agent:complete-task} venue op. The framework dispatches with a
     * cycle ctx scoped to the agent + task, so the venue op can identify
     * which task to complete from the RequestContext alone.
     *
     * <p>Returns {@code {response}} so the framework still records a
     * non-null timeline result for the cycle.</p>
     */
    private ACell handleTaskComplete(RequestContext ctx, ACell input) {
        ACell newInput = RT.getIn(input, Fields.NEW_INPUT);
        ACell response = Maps.of(Strings.create("completed"), newInput);

        if (ctx.getTaskId() != null) {
            engine.jobs().invokeInternal(
                "v/ops/agent/complete-task",
                Maps.of(Fields.RESULT, response),
                ctx).join();
        }

        return Maps.of(Fields.RESPONSE, response);
    }

    /**
     * Test transition that returns a {@code wakeTime} in the transition
     * result (B8.8 per-thread scheduled wake). Reads the requested wake
     * value from {@code state.wakeTime} in the transition input, echoes
     * it back in the result alongside a fixed {@code response}. Does not
     * complete any task — the session survives post-merge so the
     * framework's per-thread wake wire-up can install a SESSION ref in
     * the scheduler.
     */
    private ACell handleWakeResponse(ACell input) {
        ACell wt = RT.getIn(input, "state", Fields.WAKE_TIME);
        AMap<AString, ACell> out = Maps.of(
            Fields.RESPONSE, Strings.create("ack"));
        if (wt instanceof CVMLong) {
            out = out.assoc(Fields.WAKE_TIME, wt);
        }
        return out;
    }

    /**
     * Test LLM operation: reads the messages array, echoes the last user message
     * as a response. Returns an assistant message map matching the level 3 contract.
     */
    @SuppressWarnings("unchecked")
    private ACell handleLlm(ACell input) {
        String text = "(no user message)";
        ACell messagesCell = RT.getIn(input, "messages");
        if (messagesCell instanceof AVector) {
            AVector<ACell> messages = (AVector<ACell>) messagesCell;
            for (long i = messages.count() - 1; i >= 0; i--) {
                ACell entry = messages.get(i);
                AString role = RT.ensureString(RT.getIn(entry, "role"));
                if (role != null && "user".equals(role.toString())) {
                    AString content = RT.ensureString(RT.getIn(entry, "content"));
                    text = (content != null) ? content.toString() : "(empty)";
                    break;
                }
            }
        }
        return Maps.of(
            "role", Strings.create("assistant"),
            "content", Strings.create(text)
        );
    }

    /**
     * Test LLM with tool calls: if no tool result messages are present, returns
     * a tool call request for "v/test/ops/echo". Once tool results appear, returns a
     * text response summarising them. Used for testing the tool call loop.
     */
    @SuppressWarnings("unchecked")
    private ACell handleToolLlm(ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        if (messagesCell instanceof AVector) {
            AVector<ACell> messages = (AVector<ACell>) messagesCell;
            // Check if any tool result messages exist
            for (long i = 0; i < messages.count(); i++) {
                AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
                if (role != null && "tool".equals(role.toString())) {
                    // Tool results present — return a text response
                    AString toolContent = RT.ensureString(RT.getIn(messages.get(i), "content"));
                    return Maps.of(
                        "role", Strings.create("assistant"),
                        "content", Strings.create("Tool returned: " + toolContent)
                    );
                }
            }
            // No tool results — request a tool call
            String lastUserMsg = "(none)";
            for (long i = messages.count() - 1; i >= 0; i--) {
                AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
                if (role != null && "user".equals(role.toString())) {
                    AString c = RT.ensureString(RT.getIn(messages.get(i), "content"));
                    if (c != null) lastUserMsg = c.toString();
                    break;
                }
            }
            // JSON-escape the interpolated message — session-rendered messages
            // can contain quotes and newlines, which would otherwise break
            // the arguments
            String escaped = lastUserMsg.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
            return Maps.of(
                "role", Strings.create("assistant"),
                "toolCalls", Vectors.of(Maps.of(
                    "id", Strings.create("call_1"),
                    "name", Strings.create("v/test/ops/echo"),
                    "arguments", Strings.create("{\"echo\":\"" + escaped + "\"}")
                ))
            );
        }
        return Maps.of("role", Strings.create("assistant"), "content", Strings.create("(no messages)"));
    }
    
    /**
     * Test LLM that emits a tool call with MALFORMED (non-JSON) arguments on
     * its first turn, then echoes the tool result content. Exercises the wire
     * boundary rule (#89): broken tool arguments must produce a visible tool
     * error the LLM can react to, never a silent empty-map invocation.
     */
    private ACell handleBadArgsLlm(ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        if (messagesCell instanceof AVector) {
            @SuppressWarnings("unchecked")
            AVector<ACell> messages = (AVector<ACell>) messagesCell;
            for (long i = 0; i < messages.count(); i++) {
                AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
                if (role != null && "tool".equals(role.toString())) {
                    AString toolContent = RT.ensureString(RT.getIn(messages.get(i), "content"));
                    return Maps.of(
                        "role", Strings.create("assistant"),
                        "content", Strings.create("Tool said: " + toolContent));
                }
            }
        }
        return Maps.of(
            "role", Strings.create("assistant"),
            "toolCalls", Vectors.of(Maps.of(
                "id", Strings.create("call_bad"),
                "name", Strings.create("v/test/ops/echo"),
                "arguments", Strings.create("not json {{{")
            )));
    }

    /** True if any message in the L3 input has the "tool" role. */
    @SuppressWarnings("unchecked")
    private static boolean hasToolResultMessage(ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        if (!(messagesCell instanceof AVector)) return false;
        AVector<ACell> messages = (AVector<ACell>) messagesCell;
        for (long i = 0; i < messages.count(); i++) {
            AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
            if (role != null && "tool".equals(role.toString())) return true;
        }
        return false;
    }

    /**
     * Test LLM that calls the never-completing op v/test/ops/never as its
     * first tool call — with a short toolCallTimeoutMs the dispatch blocks
     * for a deterministic window, letting tests observe mid-run lattice state
     * (dangling toolCall, inCycle claim, live frames). After the tool result
     * (a timeout error) arrives: returns text, or — {@code failAfterTool} —
     * signals the caller to fail the L3 call (scripted mid-cycle failure).
     * Returns null in the fail case.
     */
    private ACell handleNeverToolLlm(ACell input, boolean failAfterTool) {
        if (hasToolResultMessage(input)) {
            if (failAfterTool) return null;
            return Maps.of(
                "role", Strings.create("assistant"),
                "content", Strings.create("gave up on the slow tool"));
        }
        return Maps.of(
            "role", Strings.create("assistant"),
            "toolCalls", Vectors.of(Maps.of(
                "id", Strings.create("call_never"),
                "name", Strings.create("v/test/ops/never"),
                "arguments", Strings.create("{}")
            )));
    }

    /**
     * Test LLM for subgoal recursion with an observable mid-run window: the
     * ROOT frame's first turn issues a subgoal; the CHILD frame (recognised
     * by its goal text) calls the never op (blocking for toolCallTimeoutMs),
     * then completes with text; the root then completes on seeing the
     * subgoal's tool result. Drives push → live child frame (with callId)
     * → atomic pop, all observable on the lattice during the block.
     */
    @SuppressWarnings("unchecked")
    private ACell handleSubgoalLlm(ACell input) {
        boolean inChild = false;
        ACell messagesCell = RT.getIn(input, "messages");
        if (messagesCell instanceof AVector) {
            AVector<ACell> messages = (AVector<ACell>) messagesCell;
            for (long i = 0; i < messages.count(); i++) {
                AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
                if (content != null && content.toString().contains("run the sub-task")) {
                    inChild = true;
                    break;
                }
            }
        }
        if (hasToolResultMessage(input)) {
            return Maps.of(
                "role", Strings.create("assistant"),
                "content", Strings.create(inChild ? "sub done" : "root done"));
        }
        if (inChild) {
            return Maps.of(
                "role", Strings.create("assistant"),
                "toolCalls", Vectors.of(Maps.of(
                    "id", Strings.create("call_child_never"),
                    "name", Strings.create("v/test/ops/never"),
                    "arguments", Strings.create("{}")
                )));
        }
        return Maps.of(
            "role", Strings.create("assistant"),
            "toolCalls", Vectors.of(Maps.of(
                "id", Strings.create("call_subgoal"),
                "name", Strings.create("subgoal"),
                "arguments", Strings.create("{\"description\":\"run the sub-task\"}")
            )));
    }

    /**
     * Test LLM that ALWAYS requests a tool call — never returns text and never
     * completes a task — so the agent tool-call loop runs to MAX_TOOL_ITERATIONS.
     * Used to verify the agent fails the Job on give-up (covia-ai/covia#138)
     * instead of leaving a task STARTED.
     */
    private ACell handleLoopLlm(ACell input) {
        return Maps.of(
            "role", Strings.create("assistant"),
            "toolCalls", Vectors.of(Maps.of(
                "id", Strings.create("call_loop"),
                "name", Strings.create("v/test/ops/echo"),
                "arguments", Strings.create("{\"echo\":\"loop\"}")
            ))
        );
    }

    /**
     * Deterministic mock token accounting (#217): UTF-8 byte lengths stand in
     * for provider token counts — {@code input} = the request's message
     * contents, {@code output} = the reply's content plus tool-call
     * arguments, {@code total} = their sum. Real plumbing, fake units: lets
     * tests assert the accounting pipeline end-to-end (timeline entries,
     * session meta.tokens, job records) without a live LLM. A mock that set
     * its own {@code tokens} keeps it.
     */
    @SuppressWarnings("unchecked")
    static ACell withMockTokens(ACell msg, ACell input) {
        if (!(msg instanceof AMap)) return msg;
        AMap<AString, ACell> m = (AMap<AString, ACell>) msg;
        if (m.get(Fields.TOKENS) != null) return msg;
        long in = 0;
        ACell messagesCell = RT.getIn(input, "messages");
        if (messagesCell instanceof AVector<?> mv) {
            for (long i = 0; i < mv.count(); i++) {
                in += utf8Count(RT.getIn(mv.get(i), "content"));
            }
        }
        long out = utf8Count(RT.getIn(m, "content"));
        ACell tcs = RT.getIn(m, "toolCalls");
        if (tcs instanceof AVector<?> tv) {
            for (long i = 0; i < tv.count(); i++) {
                out += utf8Count(RT.getIn(tv.get(i), "arguments"));
            }
        }
        return m.assoc(Fields.TOKENS, Maps.of(
            Fields.INPUT,  CVMLong.create(in),
            Fields.OUTPUT, CVMLong.create(out),
            Fields.TOTAL,  CVMLong.create(in + out)));
    }

    /** UTF-8 byte length of a cell's textual form (0 for null). */
    static long utf8Count(ACell c) {
        if (c == null) return 0;
        if (c instanceof AString s) return s.count();
        return convex.core.util.JSON.print(c).count();
    }

    /**
     * Scripted small-model behaviour for #215: emits {@code complete_task}
     * as plain assistant TEXT (no structural toolCalls) when a task is in
     * scope. Exercises the harness's textual control-tool fallback — without
     * it, this reply is unrecognised and the task never resolves.
     */
    @SuppressWarnings("unchecked")
    private ACell handleTextCtlLlm(ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        if (messagesCell instanceof AVector) {
            AVector<ACell> messages = (AVector<ACell>) messagesCell;
            // Tool result already present → the fallback fired; wind down.
            for (long i = 0; i < messages.count(); i++) {
                AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
                if (role != null && "tool".equals(role.toString())) {
                    return Maps.of(
                        "role", Strings.create("assistant"),
                        "content", Strings.create("All done."));
                }
            }
            for (long i = 0; i < messages.count(); i++) {
                AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
                AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
                if (role != null && "user".equals(role.toString()) && content != null
                        && content.toString().contains("[Tasks assigned to you]")) {
                    return Maps.of(
                        "role", Strings.create("assistant"),
                        "content", Strings.create(
                            "complete_task {\"result\": {\"answer\": \"done-via-text\"}}"));
                }
            }
        }
        return Maps.of("role", Strings.create("assistant"),
            "content", Strings.create("(no task)"));
    }

    /**
     * Test LLM for task completion: looks for "[Tasks assigned to you]" in user
     * messages, extracts the first job ID, and calls complete_task. After seeing
     * a tool result, returns a text summary. Used for testing the full
     * agent:request → agent:trigger → complete_task pipeline.
     */
    @SuppressWarnings("unchecked")
    private ACell handleTaskLlm(ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        if (!(messagesCell instanceof AVector)) {
            return Maps.of("role", Strings.create("assistant"), "content", Strings.create("(no messages)"));
        }
        AVector<ACell> messages = (AVector<ACell>) messagesCell;

        // Check if tool results already present — return text summary
        for (long i = 0; i < messages.count(); i++) {
            AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
            if (role != null && "tool".equals(role.toString())) {
                AString toolName = RT.ensureString(RT.getIn(messages.get(i), "name"));
                AString toolContent = RT.ensureString(RT.getIn(messages.get(i), "content"));
                return Maps.of(
                    "role", Strings.create("assistant"),
                    "content", Strings.create("Task completed via " + toolName + ": " + toolContent)
                );
            }
        }

        // If a task context is present, call complete_task. The in-scope task
        // is read from the framework-populated RequestContext — no jobId arg.
        for (long i = 0; i < messages.count(); i++) {
            AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
            AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
            if (role != null && "user".equals(role.toString()) && content != null) {
                String text = content.toString();
                if (text.contains("[Tasks assigned to you]")) {
                    return Maps.of(
                        "role", Strings.create("assistant"),
                        "toolCalls", Vectors.of(Maps.of(
                            "id", Strings.create("call_ct"),
                            "name", Strings.create("complete_task"),
                            "arguments", Strings.create("{\"result\":{\"answer\":\"done\"}}")
                        ))
                    );
                }
            }
        }

        // No tasks found — return text
        return Maps.of("role", Strings.create("assistant"),
            "content", Strings.create("No tasks to complete"));
    }

    /**
     * Test LLM that exercises workspace tools across multiple tool call rounds
     * within a single agent run:
     * <ol>
     *   <li>Write knowledge to workspace via covia_write</li>
     *   <li>Append to a log via covia_append</li>
     *   <li>Read back the knowledge via covia_read</li>
     *   <li>Return text summary after all tool results received</li>
     * </ol>
     * Tracks progress by counting tool result messages in the conversation.
     */
    @SuppressWarnings("unchecked")
    private ACell handleWorkspaceLlm(ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        if (!(messagesCell instanceof AVector)) {
            return Maps.of("role", Strings.create("assistant"),
                "content", Strings.create("(no messages)"));
        }
        AVector<ACell> messages = (AVector<ACell>) messagesCell;

        // Count tool results after the last user message (current run only)
        int toolResults = 0;
        int lastUserIdx = -1;
        for (long i = messages.count() - 1; i >= 0; i--) {
            AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
            if (role != null && "user".equals(role.toString())) {
                lastUserIdx = (int) i;
                break;
            }
        }
        for (long i = Math.max(0, lastUserIdx); i < messages.count(); i++) {
            AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
            if (role != null && "tool".equals(role.toString())) toolResults++;
        }

        if (toolResults == 0) {
            // Round 1: Write knowledge to workspace
            return Maps.of("role", Strings.create("assistant"),
                "toolCalls", Vectors.of(Maps.of(
                    "id", Strings.create("call_w1"),
                    "name", Strings.create("covia_write"),
                    "arguments", Strings.create(
                        "{\"path\":\"w/knowledge/topic\",\"value\":\"lattice technology\"}")
                )));
        } else if (toolResults == 1) {
            // Round 2: Append to event log
            return Maps.of("role", Strings.create("assistant"),
                "toolCalls", Vectors.of(Maps.of(
                    "id", Strings.create("call_w2"),
                    "name", Strings.create("covia_append"),
                    "arguments", Strings.create(
                        "{\"path\":\"w/log\",\"value\":\"Learned about lattice technology\"}")
                )));
        } else if (toolResults == 2) {
            // Round 3: Read back the knowledge
            return Maps.of("role", Strings.create("assistant"),
                "toolCalls", Vectors.of(Maps.of(
                    "id", Strings.create("call_w3"),
                    "name", Strings.create("covia_read"),
                    "arguments", Strings.create("{\"path\":\"w/knowledge/topic\"}")
                )));
        } else {
            // Round 4: All tool results received — return text summary
            return Maps.of("role", Strings.create("assistant"),
                "content", Strings.create(
                    "Done. Wrote knowledge, appended to log, and verified by reading back."));
        }
    }

    /**
     * Test LLM that exercises the compact harness tool. First call returns
     * tool calls [test:echo, compact]. After deferred compaction the second
     * call sees a compacted segment and returns text confirming it.
     */
    @SuppressWarnings("unchecked")
    private ACell handleCompactLlm(ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        if (messagesCell instanceof AVector) {
            AVector<ACell> messages = (AVector<ACell>) messagesCell;
            // Check for compacted segment marker
            for (long i = 0; i < messages.count(); i++) {
                AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
                AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
                if (role != null && "system".equals(role.toString())
                        && content != null && content.toString().contains("[Compacted:")) {
                    return Maps.of(
                        "role", Strings.create("assistant"),
                        "content", Strings.create("Compact verified. Segment found.")
                    );
                }
            }
        }
        // No segments — first round: call test:echo + compact
        return Maps.of(
            "role", Strings.create("assistant"),
            "toolCalls", Vectors.of(
                Maps.of(
                    "id", Strings.create("call_e1"),
                    "name", Strings.create("test_echo"),
                    "arguments", Strings.create("{\"input\":\"hello\"}")),
                Maps.of(
                    "id", Strings.create("call_c1"),
                    "name", Strings.create("compact"),
                    "arguments", Strings.create("{\"summary\":\"Made an echo call.\"}")))
        );
    }

    /**
     * Test LLM that issues a self-message during its transition. First cycle:
     * sends {@code agent:message} to itself with a fresh session carrying
     * "FOLLOWUP", then returns a plain-text response. Second cycle (triggered
     * by the self-message landing on {@code session.pending}): sees FOLLOWUP
     * in its inbound messages and returns "handled followup".
     *
     * <p>Regression for the virtual-thread run loop — proves that a nested
     * invocation writing to the agent's own lattice during a transition is
     * picked up by the same loop's next iteration without a race.</p>
     */
    @SuppressWarnings("unchecked")
    private ACell handleSelfChatLlm(RequestContext ctx, ACell input) {
        ACell messagesCell = RT.getIn(input, "messages");
        String lastUser = "";
        if (messagesCell instanceof AVector) {
            AVector<ACell> messages = (AVector<ACell>) messagesCell;
            for (long i = messages.count() - 1; i >= 0; i--) {
                AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
                if (role != null && "user".equals(role.toString())) {
                    AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
                    if (content != null) lastUser = content.toString();
                    break;
                }
            }
        }

        if (lastUser.contains("FOLLOWUP")) {
            return Maps.of(
                "role", Strings.create("assistant"),
                "content", Strings.create("handled followup"));
        }

        AString agentId = ctx.getAgentId();
        if (agentId == null) {
            return Maps.of(
                "role", Strings.create("assistant"),
                "content", Strings.create("no agent in context — cannot self-message"));
        }

        // Mint a fresh session id for the self-message so it doesn't collide
        // with the current session we're transitioning on.
        byte[] sidBytes = new byte[16];
        random.nextBytes(sidBytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : sidBytes) hex.append(String.format("%02x", b));

        AMap<AString, ACell> msgInput = Maps.of(
            Fields.AGENT_ID,   agentId,
            Fields.SESSION_ID, Strings.create(hex.toString()),
            Fields.MESSAGE,    Maps.of(Strings.create("content"), Strings.create("FOLLOWUP")));

        try {
            // agent:message is Job-aware; use invokeOperation, not invokeInternal.
            Job msgJob = engine.jobs().invokeOperation(
                "v/ops/agent/message", msgInput, ctx);
            msgJob.awaitResult(5000);
        } catch (Exception e) {
            return Maps.of(
                "role", Strings.create("assistant"),
                "content", Strings.create("self-message failed: " + e.getMessage()));
        }

        return Maps.of(
            "role", Strings.create("assistant"),
            "content", Strings.create("kicked self-message"));
    }

    private RuntimeException handleError(ACell input) {
        // Always throw an error with the input message
        ACell message = RT.getIn(input, "message");
        if (message == null) {
            throw new IllegalArgumentException("Error operation requires a 'message' parameter");
        }
        return new RuntimeException(RT.ensureString(message).toString());
    }
    
    private ACell handleRandom(ACell input) {
        // Get length parameter
        ACell lengthCell = RT.getIn(input, "length");
        if (lengthCell == null) {
            throw new IllegalArgumentException("Random operation requires a 'length' parameter");
        }
        
        // Parse length
        int length;
        try {
            length = (int)CVMLong.parse(lengthCell).longValue();
            if (length <= 0 || length > 1024) {
                throw new IllegalArgumentException("Length must be between 1 and 1024");
            }
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException("Length must be a valid number");
        }
        
        // Generate random bytes
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        
        // Convert to hex string
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        
        return Maps.of(
            "bytes", Strings.create(hex.toString())
        );
    }
} 