package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;

/**
 * What an access token keeps for the venue's MCP endpoint, its {@code access.mcp}
 * entry (ACCESS_TOKENS.md): the tools it offers there, as entries of an
 * agent's {@code config.tools}. Only the MCP endpoint and adapter read it.
 */
public final class McpAccess extends ALatticeComponent<AMap<AString, ACell>>
		implements TransactionalComponent<AMap<AString, ACell>, McpAccess> {

	/** The entry over {@code cursor}, as {@link covia.adapter.token.AccessToken#access} binds it. */
	public McpAccess(AForkableCursor<AMap<AString, ACell>> cursor) {
		super(cursor);
	}

	@Override
	public McpAccess rebind(AForkableCursor<AMap<AString, ACell>> cursor) {
		return new McpAccess(cursor);
	}

	/** An entry offering {@code tools}. */
	public static AMap<AString, ACell> entry(AVector<ACell> tools) {
		return Maps.of(Fields.TOOLS, tools);
	}

	/** The tools it offers; empty when it names none. */
	public AVector<ACell> tools() {
		AMap<AString, ACell> entry = RT.ensureMap(cursor().getOrDefault(null));
		AVector<ACell> tools = (entry == null) ? null : RT.ensureVector(entry.get(Fields.TOOLS));
		return (tools == null) ? Vectors.empty() : tools;
	}
}
