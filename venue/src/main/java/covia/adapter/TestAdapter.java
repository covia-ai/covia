package covia.adapter;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.venue.Venue;

public class TestAdapter extends AAdapter {
	
	public static final Logger log=LoggerFactory.getLogger(TestAdapter.class);
	
    private final SecureRandom random = new SecureRandom();
    
    @Override
    public String getName() {
        return "test";
    }

    @Override
    public CompletableFuture<ACell> invoke(String operation, ACell meta,ACell input) {
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
                    return handleDelay(input);
                case "error":
                    return CompletableFuture.failedFuture(handleError(input));
                case "random":
                    return CompletableFuture.completedFuture(handleRandom(input));
                default:
                    return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Unknown test operation: " + testOp)
                    );
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
    
    @Override public void install(Venue venue) {
    	super.install(venue);
		String BASE="/asset-examples/";

		try {
			venue.storeAsset(Utils.readResourceAsString(BASE+"empty.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"randomop.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"echoop.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"neverop.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"delayop.json"),null);
			venue.storeAsset(Utils.readResourceAsString(BASE+"randomop.json"),null);
		} catch(Exception e) {
			log.warn("Failed to install test assets",e);
		}

    }
    
    private CompletableFuture<ACell> handleDelay(ACell input) {
        try {
        	Hash op = RT.getIn(input, Fields.OPERATION);
        	ACell opInput = RT.getIn(input, Fields.INPUT);
        	CVMLong delay = CVMLong.parse(RT.getIn(input, Fields.DELAY));
        	ACell result = venue.invokeOperation(op, opInput);
			Thread.sleep(delay.longValue());
			return CompletableFuture.completedFuture(result);
		} catch (InterruptedException e) {
			return CompletableFuture.failedFuture(e);
		}
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