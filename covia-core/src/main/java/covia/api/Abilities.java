package covia.api;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Shared covia capability abilities — the {@code can} of a UCAN grant.
 *
 * <p>One home for the covia-specific ability strings so they are never
 * inline-interned or duplicated per adapter. The generic CRUD abilities
 * ({@code crud/read}, {@code crud/write}, {@code crud/delete}) live on
 * {@link convex.auth.ucan.Capability}; these are the covia-domain additions on
 * top.</p>
 */
public final class Abilities {

	private Abilities() {}

	/** Look up an operation by reference and start its Job. {@code with}
	 *  names the permitted location or operation tree, whoever publishes it.
	 *  This grants no authority over resources the operation uses. A directly
	 *  bound tool needs its action authority, not arbitrary lookup permission. */
	public static final AString INVOKE        = Strings.intern("invoke");

	/** Read a Job's public record, result and updates. */
	public static final AString JOB_READ      = Strings.intern("job/read");
	/** Control a Job's lifecycle, including deleting its retained record. */
	public static final AString JOB_MANAGE    = Strings.intern("job/manage");
	/** Deliver input to an existing Job; grants no execution authority. */
	public static final AString JOB_MESSAGE   = Strings.intern("job/message");

	public static final AString ASSET_READ    = Strings.intern("asset/read");
	public static final AString ASSET_STORE   = Strings.intern("asset/store");
	public static final AString SECRET_WRITE  = Strings.intern("secret/write");
	/** Use a secret's value in an operation without seeing it. A grant of it
	 *  may be bound to a destination url prefix ({@code nb.url}). */
	public static final AString SECRET_USE    = Strings.intern("secret/use");
	/** Reveal plaintext to its owner through a direct, non-recorded call. */
	public static final AString SECRET_EXTRACT = Strings.intern("secret/extract");
	/** Send a connected account's access token in a request without seeing it
	 *  ({@code <did>/x/oauth/<provider>}). A grant of it may be bound to a
	 *  destination url prefix ({@code nb.url}). */
	public static final AString OAUTH_USE     = Strings.intern("oauth/use");
	/** Connect an account at a provider: whoever approves the returned URL
	 *  becomes the connection ({@code <did>/x/oauth/<provider>}). */
	public static final AString OAUTH_CONNECT = Strings.intern("oauth/connect");
	/** Disconnect an account, revoking its grant at the provider
	 *  ({@code <did>/x/oauth/<provider>}). */
	public static final AString OAUTH_DISCONNECT = Strings.intern("oauth/disconnect");
	/** Every use of a held key or mnemonic ({@code <did>/x/crypto/keys/<key>},
	 *  {@code <did>/x/crypto/mnemonics/<id>}); covers each {@code key/…} ability. */
	public static final AString KEY           = Strings.intern("key");
	/** Put a key or mnemonic into a keyring, or generate or derive one there. */
	public static final AString KEY_ADD       = Strings.intern("key/add");
	/** Remove a key or mnemonic from a keyring. */
	public static final AString KEY_REMOVE    = Strings.intern("key/remove");
	/** Sign anything with a held key; covers every {@code key/sign/…}. */
	public static final AString KEY_SIGN      = Strings.intern("key/sign");
	/** Sign UCAN grants with a held key ({@code ucan:issue}). */
	public static final AString KEY_SIGN_GRANT  = Strings.intern("key/sign/grant");
	/** Sign Convex transactions with a held key ({@code convex:transact}). */
	public static final AString KEY_SIGN_CONVEX = Strings.intern("key/sign/convex");
	/** Sign content the caller chooses with a held key ({@code crypto:sign},
	 *  {@code crypto:jwt-sign}), which can produce any other kind of signature. */
	public static final AString KEY_SIGN_RAW  = Strings.intern("key/sign/raw");
	/** Decrypt with a held key ({@code crypto:decrypt}). */
	public static final AString KEY_DECRYPT   = Strings.intern("key/decrypt");
	/** Derive keys from a held mnemonic ({@code crypto:derive-key}). */
	public static final AString KEY_DERIVE    = Strings.intern("key/derive");
	public static final AString MCP_MANAGE    = Strings.intern("mcp/manage");
	/** Create, change or delete an MCP token ({@code <did>/x/mcp/tokens/<name>}). */
	public static final AString MCP_TOKEN     = Strings.intern("mcp/token");
	/** Fetch an MCP token's bearer again, where the venue keeps it. */
	public static final AString ADMIN_TOKEN   = Strings.intern("admin/token");
	public static final AString HITL_REQUEST  = Strings.intern("hitl/request");
	public static final AString AGENT_CREATE  = Strings.intern("agent/create");
	public static final AString AGENT_REQUEST = Strings.intern("agent/request");
	public static final AString AGENT_MESSAGE = Strings.intern("agent/message");
	public static final AString AGENT_FORK    = Strings.intern("agent/fork");
	public static final AString AGENT_WRITE   = Strings.intern("agent/write");
	/** Run an agent's tool calls directly, with no model between ({@code agent:step}). */
	public static final AString ADMIN_AGENT   = Strings.intern("admin/agent");
	/** Trust a skill outside an agent's effective advertised skill surface. */
	public static final AString SKILL_LOAD    = Strings.intern("skill/load");
	/** Add an operation outside an agent's already declared tool surface. */
	public static final AString TOOL_LOAD     = Strings.intern("tool/load");
	public static final AString USER_CREATE   = Strings.intern("user/create");
	public static final AString USER_DELETE   = Strings.intern("user/delete");
	public static final AString USER_READ     = Strings.intern("user/read");
	/** Authorise an explicit sudo request into a user's namespace. Scoped to the
	 *  target user DID itself; grants no operation or action authority. */
	public static final AString USER_SUDO     = Strings.intern("user/sudo");
	/** Read out another user's whole stored state ({@code user:export}, the
	 *  source of {@code user:clone}). */
	public static final AString USER_EXPORT   = Strings.intern("user/export");
	/** Fill an empty account from stored user state ({@code user:import}, the
	 *  target of {@code user:clone}). */
	public static final AString USER_IMPORT   = Strings.intern("user/import");
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
	/** See the venue's P2P node and the peers it knows (covia#587) — guarded
	 *  on {@code <venue DID>/p2p}. */
	public static final AString P2P_READ = Strings.intern("p2p/read");
	/** Connect the venue's P2P node to a peer (covia#587) — guarded on
	 *  {@code <venue DID>/p2p}. */
	public static final AString P2P_MANAGE = Strings.intern("p2p/manage");
	/** Have the venue dial any outbound target, unchecked by its outbound
	 *  guard — held by the venue itself, delegable on {@code <venue DID>/outbound}. */
	public static final AString OUTBOUND_ANY = Strings.intern("outbound/any");

	/** The venue-relative MCP management resource guarded by {@link #MCP_MANAGE}:
	 *  a delegation names it as {@code <venue DID>/v/mcp}. */
	public static final AString V_MCP = Strings.intern("v/mcp");
}
