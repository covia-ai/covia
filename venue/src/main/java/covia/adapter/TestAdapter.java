package covia.adapter;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

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
    public CompletableFuture<ACell> invokeFuture(String operation, ACell meta,ACell input) {
        // Parse the operation to get the specific test operation
        String[] parts = operation.split(":");
        if (parts.length != 2) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Invalid operation format: " + operation)
            );
        }
        
        String testOp = parts[1];
        
        // Handle different test operations
        try {
            switch (testOp) {
                case "echo":
                    return CompletableFuture.completedFuture(handleEcho(input));
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
			installAsset(BASE+"neverop.json");
			installAsset(BASE+"delayop.json");
			installAsset(BASE+"randomop.json");
			installAsset(BASE+"failop.json");
			installAsset(BASE+"chatop.json");
			installAsset(BASE+"pauseop.json");
			installAsset(BASE+"orch.json");
			Hash iris=engine.storeAsset(Utils.readResourceAsAString(BASE+"iris.json"),null);
			engine.putContent(iris,this.getClass().getResourceAsStream(BASE+"iris.csv"));
			Hash shake=engine.storeAsset(Utils.readResourceAsAString(BASE+"shakespeare.json"),null);
			engine.putContent(shake,this.getClass().getResourceAsStream(BASE+"hamlet.txt"));
		} catch(Exception e) {
			log.warn("Failed to install test assets",e);
		}
    }
    
    @Override
    public void invoke(Job job, String operation, ACell meta, ACell input) {
        if (operation.equals("test:chat")) {
            // Multi-turn: set INPUT_REQUIRED and wait for messages
            job.setStatus(Status.INPUT_REQUIRED);
        } else if (operation.equals("test:pause")) {
            // Auto-pause: immediately pauses, stores input for later completion
            job.update(data -> data.assoc(Fields.INPUT, input));
            job.setStatus(Status.PAUSED);
        } else if (operation.equals("test:delay")) {
            // Delay: needs Job for caller DID propagation to sub-invocation
            handleDelay(job, input);
        } else {
            // Default one-shot path
            super.invoke(job, operation, meta, input);
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

    private void handleDelay(Job job, ACell input) {
    	CompletableFuture.runAsync(() -> {
    		try {
				ACell op = RT.getIn(input, Fields.OPERATION);
				ACell opInput = RT.getIn(input, Fields.INPUT);
				CVMLong delay = CVMLong.parse(RT.getIn(input, Fields.DELAY));
				Thread.sleep(delay.longValue());
				AString callerDID = RT.ensureString(job.getData().get(Fields.CALLER));
				Job subJob = engine.jobs().invokeOperation(RT.ensureString(op), opInput, callerDID);
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