package covia.api;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Shared covia capability abilities — the {@code can} of a UCAN grant.
 *
 * <p>One home for the covia-specific ability strings so they are never
 * inline-interned or duplicated per adapter. The generic CRUD abilities
 * ({@code crud/read}, {@code crud/write}, {@code crud/delete}) and {@code invoke}
 * live on {@link convex.auth.ucan.Capability}; these are the covia-domain
 * additions on top.</p>
 */
public final class Abilities {

	private Abilities() {}

	public static final AString ASSET_READ    = Strings.intern("asset/read");
	public static final AString ASSET_STORE   = Strings.intern("asset/store");
	public static final AString SECRET_WRITE  = Strings.intern("secret/write");
	public static final AString MCP_MANAGE    = Strings.intern("mcp/manage");
	public static final AString HITL_REQUEST  = Strings.intern("hitl/request");
	public static final AString AGENT_CREATE  = Strings.intern("agent/create");
	public static final AString AGENT_REQUEST = Strings.intern("agent/request");
	public static final AString AGENT_MESSAGE = Strings.intern("agent/message");
	public static final AString AGENT_FORK    = Strings.intern("agent/fork");
	public static final AString AGENT_WRITE   = Strings.intern("agent/write");
	public static final AString USER_CREATE   = Strings.intern("user/create");
	public static final AString USER_READ     = Strings.intern("user/read");
	/** Authorise an explicit sudo request into a user's namespace. Scoped to the
	 *  target user DID itself; grants no operation or action authority. */
	public static final AString USER_SUDO     = Strings.intern("user/sudo");
	public static final AString USER_AUTH_MANAGE =
		Strings.intern("user/authentication-manage");
	/** Venue-owned adapter and module lifecycle (enable/disable/configure,
	 *  module load/unload) — guarded on {@code <venue DID>/adapters}. */
	public static final AString ADAPTER_MANAGE = Strings.intern("adapter/manage");
	/** Process-level graceful restart and executable venue-jar handoff — guarded
	 *  on {@code <venue DID>/process}. */
	public static final AString VENUE_RESTART = Strings.intern("venue/restart");
	/** Online garbage collection of the venue's Etch store (covia#452) — guarded
	 *  on {@code <venue DID>/store}. */
	public static final AString VENUE_GC = Strings.intern("venue/gc");

	/** The venue-scoped MCP management resource guarded by {@link #MCP_MANAGE}. */
	public static final AString V_MCP = Strings.intern("v/mcp");
}
