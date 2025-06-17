package covia.adapter;

import convex.core.data.ACell;

public class MCPAdapter extends AAdapter {
    @Override
    public String getName() {
        return "mcp";
    }

    @Override
    public ACell invoke(String operation, ACell input) {
        // For now, throw UnsupportedOperationException
        throw new UnsupportedOperationException("MCP adapter operations not yet implemented");
    }
}
