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
        String testOp = getSubOperation(meta);

        // Handle different test operations
        try {
            switch (testOp) {
                case "echo":
                    return CompletableFuture.completedFuture(handleEcho(input));
                case "taskcomplete":
                    return CompletableFuture.completedFuture(handleTaskComplete(input));
                case "llm":
                    return CompletableFuture.completedFuture(handleLlm(input));
                case "toolllm":
                    return CompletableFuture.completedFuture(handleToolLlm(input));
                case "taskllm":
                    return CompletableFuture.completedFuture(handleTaskLlm(input));
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
			installAsset(BASE+"empty.json");
			installAsset(BASE+"randomop.json");
			installAsset(BASE+"echoop.json");
			installAsset(BASE+"testllm.json");
			installAsset(BASE+"testtoolllm.json");
			installAsset(BASE+"testtaskllm.json");
			installAsset(BASE+"neverop.json");
			installAsset(BASE+"delayop.json");
			installAsset(BASE+"randomop.json");
			installAsset(BASE+"failop.json");
			installAsset(BASE+"chatop.json");
			installAsset(BASE+"pauseop.json");
			installAsset(BASE+"orch.json");
			installAsset(BASE+"taskcomplete.json");
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
    	CompletableFuture.runAsync(() -> {
    		try {
				ACell op = RT.getIn(input, Fields.OPERATION);
				ACell opInput = RT.getIn(input, Fields.INPUT);
				CVMLong delay = CVMLong.parse(RT.getIn(input, Fields.DELAY));
				Thread.sleep(delay.longValue());
				Job subJob = engine.jobs().invokeOperation(RT.ensureString(op), opInput, ctx);
				ACell result = subJob.awaitResult();
				job.completeWith(result);
    		} catch (InterruptedException e) {
    			job.fail("Delay operation interrupted");
    		} catch (Exception e) {
    			job.fail(e.getMessage());
    		}
    	}, VIRTUAL_EXECUTOR);
	}

	private ACell handleEcho(ACell input) {
        // Simply return the input
        return input;
    }

    /**
     * Test transition function that auto-completes all tasks.
     * Returns the transition function contract: {state, result, taskResults}.
     * Each task is completed with its own input as output.
     */
    @SuppressWarnings("unchecked")
    private ACell handleTaskComplete(ACell input) {
        ACell state = RT.getIn(input, "state");
        ACell tasksCell = RT.getIn(input, "tasks");

        AMap<AString, ACell> taskResults = Maps.empty();
        if (tasksCell instanceof AVector) {
            AVector<ACell> tasks = (AVector<ACell>) tasksCell;
            for (long i = 0; i < tasks.count(); i++) {
                AString jobId = RT.ensureString(RT.getIn(tasks.get(i), "jobId"));
                ACell taskInput = RT.getIn(tasks.get(i), "input");
                if (jobId != null) {
                    taskResults = taskResults.assoc(jobId, Maps.of(
                        "status", Strings.create("COMPLETE"),
                        "output", Maps.of("completed", taskInput)
                    ));
                }
            }
        }

        return Maps.of(
            "state", state,
            "result", Maps.of("tasksCompleted", CVMLong.create(taskResults.count())),
            "taskResults", taskResults
        );
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
     * a tool call request for "test:echo". Once tool results appear, returns a
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
            return Maps.of(
                "role", Strings.create("assistant"),
                "toolCalls", Vectors.of(Maps.of(
                    "id", Strings.create("call_1"),
                    "name", Strings.create("test:echo"),
                    "arguments", Strings.create("{\"echo\":\"" + lastUserMsg + "\"}")
                ))
            );
        }
        return Maps.of("role", Strings.create("assistant"), "content", Strings.create("(no messages)"));
    }
    
    /**
     * Test LLM for task completion: looks for "[Tasks assigned to you]" in user
     * messages, extracts the first job ID, and calls complete_task. After seeing
     * a tool result, returns a text summary. Used for testing the full
     * agent:request → agent:run → complete_task pipeline.
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

        // Look for task context in user messages — extract first job ID
        for (long i = 0; i < messages.count(); i++) {
            AString role = RT.ensureString(RT.getIn(messages.get(i), "role"));
            AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
            if (role != null && "user".equals(role.toString()) && content != null) {
                String text = content.toString();
                if (text.contains("[Tasks assigned to you]")) {
                    // Extract job ID: "- Task <hexid>: ..."
                    int idx = text.indexOf("- Task ");
                    if (idx >= 0) {
                        String rest = text.substring(idx + 7);
                        int colon = rest.indexOf(":");
                        if (colon > 0) {
                            String jobId = rest.substring(0, colon).trim();
                            // Call complete_task with this job ID
                            return Maps.of(
                                "role", Strings.create("assistant"),
                                "toolCalls", Vectors.of(Maps.of(
                                    "id", Strings.create("call_ct"),
                                    "name", Strings.create("complete_task"),
                                    "arguments", Strings.create(
                                        "{\"jobId\":\"" + jobId + "\",\"output\":{\"answer\":\"done\"}}")
                                ))
                            );
                        }
                    }
                }
            }
        }

        // No tasks found — return text
        return Maps.of("role", Strings.create("assistant"),
            "content", Strings.create("No tasks to complete"));
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