package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.lang.RT;

public class TestAdapter extends AAdapter {
    @Override
    public String getName() {
        return "test";
    }

    @Override
    public ACell invoke(String operation, ACell input) {
        // Parse the operation to get the specific test operation
        String[] parts = operation.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid operation format: " + operation);
        }
        
        String testOp = parts[1];
        
        // Handle different test operations
        switch (testOp) {
            case "echo":
                return handleEcho(input);
            case "error":
                return handleError(input);
            default:
                throw new IllegalArgumentException("Unknown test operation: " + testOp);
        }
    }
    
    private ACell handleEcho(ACell input) {
        // Simply return the input
        return input;
    }
    
    private ACell handleError(ACell input) {
        // Always throw an error with the input message
        ACell message = RT.getIn(input, "message");
        if (message == null) {
            throw new IllegalArgumentException("Error operation requires a 'message' parameter");
        }
        throw new RuntimeException(RT.ensureString(message).toString());
    }
} 