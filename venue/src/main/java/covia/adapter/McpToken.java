package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.lattice.Caps;

/**
 * One MCP token's record (MCP_TOKENS.md): the tools it offers, the caps
 * narrowing its issuer's authority, and how its bearer is recognised. The
 * record never holds the bearer itself unless the venue keeps raw tokens.
 */
public final class McpToken extends ALatticeComponent<AMap<AString, ACell>>
		implements TransactionalComponent<AMap<AString, ACell>, McpToken> {

	/** The namespace it belongs to. */
	private final AString owner;

	/** Its name, unique within that namespace. */
	private final AString name;

	McpToken(AForkableCursor<AMap<AString, ACell>> cursor, AString owner, AString name) {
		super(cursor);
		this.owner = owner;
		this.name = name;
	}

	@Override
	public McpToken rebind(AForkableCursor<AMap<AString, ACell>> cursor) {
		return new McpToken(cursor, owner, name);
	}

	@Override
	public McpToken withCursor(AForkableCursor<AMap<AString, ACell>> cursor) {
		return rebind(cursor);
	}

	/** The namespace it belongs to. */
	public AString owner() {
		return owner;
	}

	/** Its name. */
	public AString name() {
		return name;
	}

	/** The record as stored, or null when there is none. */
	public AMap<AString, ACell> record() {
		return RT.ensureMap(cursor().getOrDefault(null));
	}

	private ACell field(AString key) {
		AMap<AString, ACell> record = record();
		return (record == null) ? null : record.get(key);
	}

	/** Its id, fixed when it was created, so a reused name never revives a reference. */
	public AString id() {
		return RT.ensureString(field(Fields.ID));
	}

	/** The identity that created it, whose authority it narrows. */
	public AString issuer() {
		return RT.ensureString(field(Fields.ISSUER));
	}

	/** The token whose request created it, which narrows it too; null when none did. */
	public AMap<AString, ACell> source() {
		return RT.ensureMap(field(Fields.SOURCE));
	}

	/** The tools it offers, as entries of an agent's {@code config.tools}. */
	public AVector<ACell> tools() {
		AVector<ACell> tools = RT.ensureVector(field(Fields.TOOLS));
		return (tools == null) ? Vectors.empty() : tools;
	}

	/** Its caps: missing is unrestricted, empty none. */
	public Caps caps() {
		return new Caps(cursor().path(Fields.CAPS));
	}

	/** When it expires, in milliseconds since the epoch; null for never. */
	public Long expires() {
		return (field(Fields.EXPIRES) instanceof CVMLong at) ? at.longValue() : null;
	}

	/** Whether it has expired at {@code now}. */
	public boolean expired(long now) {
		Long at = expires();
		return at != null && now >= at;
	}

	/** The hex SHA3-256 hash of its bearer. */
	public AString hash() {
		return RT.ensureString(field(McpTokens.HASH));
	}

	/** Its bearer, only while the venue keeps raw tokens; otherwise null. */
	public AString raw() {
		return RT.ensureString(field(McpTokens.RAW));
	}

	/** How work it narrowed names it: {@code {owner, name, id}}. */
	public AMap<AString, ACell> reference() {
		return reference(owner, name, id());
	}

	/** A token's reference. */
	static AMap<AString, ACell> reference(AString owner, AString name, AString id) {
		return Maps.of(Fields.OWNER, owner, Fields.NAME, name, Fields.ID, id);
	}
}
