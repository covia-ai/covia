package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import java.security.SecureRandom;

public class TestAdapter extends AAdapter {
    private final SecureRandom random = new SecureRandom();
    
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
            case "random":
                return handleRandom(input);
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
    
    private ACell handleRandom(ACell input) {
        // Get length parameter
        ACell lengthCell = RT.getIn(input, "length");
        if (lengthCell == null) {
            throw new IllegalArgumentException("Random operation requires a 'length' parameter");
        }
        
        // Parse length
        int length;
        try {
            length = Integer.parseInt(RT.ensureString(lengthCell).toString());
            if (length <= 0 || length > 1024) {
                throw new IllegalArgumentException("Length must be between 1 and 1024");
            }
        } catch (NumberFormatException e) {
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