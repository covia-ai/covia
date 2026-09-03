package covia.adapter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Asset;
import covia.venue.RequestContext;
import covia.venue.User;

/**
 * Catalog of service connections available to agents.
 *
 * <p>A connection is an ordinary immutable skill asset with a small
 * {@code connection} facet describing its endpoint and credential references.
 * The skill owns the instructions and tool refs; this adapter owns those skills
 * and derives its read-only discovery/status results from the materialised
 * lattice entries. There is deliberately no second provider registry and no
 * connection state store.</p>
 *
 * <p>Transport and authority stay with their existing owners: HTTP executes
 * requests, OAuth owns grants, and the user's {@code s/} namespace owns
 * credentials. Status checks only whether named encrypted records exist; it
 * never resolves or decrypts a secret.</p>
 */
public class ConnectionsAdapter extends AAdapter {

	private static final AString K_CONNECTION = Strings.intern("connection");
	private static final AString K_CONNECTIONS = Strings.intern("connections");
	private static final AString K_PROVIDERS = Strings.intern("providers");
	private static final AString K_SKILL = Strings.intern("skill");
	private static final AString K_AVAILABLE = Strings.intern("available");
	private static final AString K_CONFIGURED = Strings.intern("configured");
	private static final AString K_CREDENTIALS = Strings.intern("credentials");
	private static final AString K_AUTH = Strings.intern("auth");
	private static final AString K_ALTERNATIVES = Strings.intern("alternatives");
	private static final AString K_REQUIRED = Strings.intern("required");

	/** Stable presentation order. Provider metadata itself lives in each skill asset. */
	private static final List<Provider> PROVIDERS = List.of(
		new Provider("airtable"),
		new Provider("asana"),
		new Provider("calendly"),
		new Provider("clickup"),
		new Provider("confluence"),
		new Provider("datadog"),
		new Provider("discord"),
		new Provider("github"),
		new Provider("gitlab"),
		new Provider("hubspot"),
		new Provider("intercom"),
		new Provider("jira"),
		new Provider("linear"),
		new Provider("monday"),
		new Provider("notion"),
		new Provider("pagerduty"),
		new Provider("sendgrid"),
		new Provider("sentry"),
		new Provider("shopify"),
		new Provider("slack"),
		new Provider("stripe"),
		new Provider("telegram"),
		new Provider("trello"),
		new Provider("twilio"),
		new Provider("zendesk"));

	private record Provider(String name) {
		String skillRef() {
			return "v/skills/connections/" + name;
		}
	}

	private record ToolState(boolean available, AVector<ACell> unavailable) {}

	private record CredentialPresence(boolean configured, String source) {
		static CredentialPresence missing() {
			return new CredentialPresence(false, null);
		}
	}

	@Override
	public String getName() {
		return "connections";
	}

	@Override
	public String getDescription() {
		return "Service connection catalog for agents: owns provider skills and reports their "
			+ "live transport availability and caller-scoped credential presence without exposing "
			+ "secret values. HTTP remains the transport, OAuth owns grants, and s/ owns credentials.";
	}

	@Override
	protected void installAssets() {
		installAsset("connections/list", "/adapters/connections/list.json");
		installAsset("connections/status", "/adapters/connections/status.json");

		// One entry point is visible directly; the mirror also makes connections
		// discoverable after loading the broader auth family. Both are the same asset.
		installSkill("root/connections", "/skills/connections.json");
		installSkill("auth/connections", "/skills/connections.json");
		for (Provider provider : PROVIDERS) {
			installSkill("connections/" + provider.name(), "/skills/" + provider.name() + ".json");
		}
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		try {
			requireInvoke(ctx);
			return switch (getSubOperation(meta)) {
				case "list" -> CompletableFuture.completedFuture(handleList(ctx));
				case "status" -> CompletableFuture.completedFuture(handleStatus(ctx, input));
				default -> CompletableFuture.failedFuture(new IllegalArgumentException(
					"Unknown connections operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Immutable provider descriptions plus current transport liveness. */
	private ACell handleList(RequestContext ctx) {
		AVector<ACell> out = Vectors.empty();
		for (Provider provider : PROVIDERS) {
			AMap<AString, ACell> skill = providerSkill(provider, ctx);
			out = out.conj(describe(provider, skill, ctx));
		}
		return Maps.of(K_PROVIDERS, out);
	}

	/** Caller-scoped credential presence; no secret value is read or decrypted. */
	private ACell handleStatus(RequestContext ctx, ACell input) {
		AString userDID = requireUser(ctx);
		ACell rawProvider = RT.getIn(input, Fields.PROVIDER);
		AString only = RT.ensureString(rawProvider);
		if (rawProvider != null && (only == null || only.isEmpty())) {
			throw new IllegalArgumentException("provider must be a non-empty string");
		}

		Provider selected = (only != null) ? provider(only.toString()) : null;
		AVector<ACell> out = Vectors.empty();
		for (Provider provider : PROVIDERS) {
			if (selected != null && provider != selected) continue;
			AMap<AString, ACell> skill = providerSkill(provider, ctx);
			out = out.conj(status(provider, skill, userDID, ctx));
		}
		return Maps.of(K_CONNECTIONS, out);
	}

	private Provider provider(String name) {
		for (Provider provider : PROVIDERS) {
			if (provider.name().equals(name)) return provider;
		}
		throw new IllegalArgumentException("Unknown connection provider '" + name
			+ "'; available: " + PROVIDERS.stream().map(Provider::name).toList());
	}

	private static AString requireUser(RequestContext ctx) {
		if (ctx == null || ctx.isAnonymous() || ctx.getUserDID() == null) {
			throw new IllegalArgumentException(
				"connections:status needs an authenticated caller — connection status belongs to a venue user");
		}
		return ctx.getUserDID();
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> providerSkill(Provider provider, RequestContext ctx) {
		ACell value = engine.resolvePath(Strings.create(provider.skillRef()), ctx);
		if (!(value instanceof AMap<?, ?>)) {
			throw new IllegalStateException("Connection skill is not materialised: " + provider.skillRef());
		}
		AMap<AString, ACell> skill = (AMap<AString, ACell>) value;
		if (!(skill.get(K_CONNECTION) instanceof AMap<?, ?>)) {
			throw new IllegalStateException("Connection skill has no connection facet: " + provider.skillRef());
		}
		return skill;
	}

	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> describe(Provider provider,
			AMap<AString, ACell> skill, RequestContext ctx) {
		AString description = RT.ensureString(skill.get(Fields.DESCRIPTION));
		AMap<AString, ACell> connection = (AMap<AString, ACell>) skill.get(K_CONNECTION);
		AVector<ACell> tools = requireTools(provider, skill);
		ToolState state = toolState(tools, ctx);
		return Maps.of(
			Fields.PROVIDER, Strings.create(provider.name()),
			Fields.DESCRIPTION, description,
			K_SKILL, Strings.create(provider.skillRef()),
			K_CONNECTION, connection,
			Fields.TOOLS, tools,
			K_AVAILABLE, CVMBool.create(state.available()),
			Fields.UNAVAILABLE_TOOLS, state.unavailable());
	}

	@SuppressWarnings("unchecked")
	private ACell status(Provider provider, AMap<AString, ACell> skill,
			AString userDID, RequestContext ctx) {
		AMap<AString, ACell> connection = (AMap<AString, ACell>) skill.get(K_CONNECTION);
		// A single-value connection lists auth ALTERNATIVES — any one configures it (OR).
		// A multi-value connection lists REQUIRED refs — a site subdomain plus a token,
		// or two keys — and every one must be present (AND). `required` wins when set.
		AVector<ACell> required = RT.ensureVector(RT.getIn(connection, K_REQUIRED));
		boolean andMode = required != null && !required.isEmpty();
		AVector<ACell> refs = andMode
			? required
			: RT.ensureVector(RT.getIn(connection, K_AUTH, K_ALTERNATIVES));
		if (refs == null || refs.isEmpty()) {
			throw new IllegalStateException(
				"Connection skill has no auth alternatives or required refs: " + provider.skillRef());
		}

		boolean configured = andMode; // AND starts true and is narrowed; OR starts false and is widened
		AVector<ACell> credentials = Vectors.empty();
		for (long i = 0; i < refs.count(); i++) {
			AString ref = RT.ensureString(refs.get(i));
			if (ref == null || ref.isEmpty()) {
				throw new IllegalStateException("Connection credential refs must be non-empty: "
					+ provider.skillRef());
			}
			CredentialPresence presence = credentialPresence(userDID, ref.toString());
			configured = andMode
				? (configured && presence.configured())
				: (configured || presence.configured());
			AMap<AString, ACell> credential = Maps.of(
				Fields.REF, ref,
				K_CONFIGURED, CVMBool.create(presence.configured()));
			if (presence.source() != null) {
				credential = credential.assoc(Fields.SOURCE, Strings.create(presence.source()));
			}
			credentials = credentials.conj(credential);
		}

		AVector<ACell> tools = requireTools(provider, skill);
		ToolState toolState = toolState(tools, ctx);
		return Maps.of(
			Fields.PROVIDER, Strings.create(provider.name()),
			K_SKILL, Strings.create(provider.skillRef()),
			K_CONFIGURED, CVMBool.create(configured),
			K_CREDENTIALS, credentials,
			K_AVAILABLE, CVMBool.create(toolState.available()),
			Fields.UNAVAILABLE_TOOLS, toolState.unavailable());
	}

	private AVector<ACell> requireTools(Provider provider, AMap<AString, ACell> skill) {
		AVector<ACell> tools = RT.ensureVector(RT.getIn(skill, K_SKILL, Fields.TOOLS));
		if (tools == null || tools.isEmpty()) {
			throw new IllegalStateException("Connection skill has no tools: " + provider.skillRef());
		}
		return tools;
	}

	/** Liveness is a venue fact, distinct from the caller's capability to invoke the tool. */
	private ToolState toolState(AVector<ACell> tools, RequestContext ctx) {
		AVector<ACell> unavailable = Vectors.empty();
		for (long i = 0; i < tools.count(); i++) {
			AString op = RT.ensureString(tools.get(i));
			String reason = null;
			if (op == null) {
				reason = "tool ref is not a string";
			} else {
				Asset asset = engine.resolveAsset(op, ctx);
				if (asset == null) {
					reason = "operation does not resolve";
				} else {
					String adapter = AAdapter.getAdapterName(asset.meta());
					if (adapter == null) reason = "operation has no dispatch adapter";
					else if (!engine.hasAdapter(adapter)) reason = "adapter '" + adapter + "' is disabled or unavailable";
				}
			}
			if (reason != null) {
				unavailable = unavailable.conj(Maps.of(
					Fields.OPERATION, op != null ? op : tools.get(i),
					Fields.REASON, Strings.create(reason)));
			}
		}
		return new ToolState(unavailable.isEmpty(), unavailable);
	}

	private CredentialPresence credentialPresence(AString userDID, String ref) {
		String name = secretName(ref);
		if (name == null) return CredentialPresence.missing();

		User user = engine.getVenueState().users().get(userDID);
		if (user != null && user.secrets().exists(name)) {
			return new CredentialPresence(true, "user");
		}
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		if (!publicDID.equals(userDID)) {
			User publicUser = engine.getVenueState().users().get(publicDID);
			if (publicUser != null && publicUser.secrets().exists(name)) {
				return new CredentialPresence(true, "public");
			}
		}
		return CredentialPresence.missing();
	}

	/** The secret-record name corresponding to an HTTP/OAuth credential ref. */
	private static String secretName(String ref) {
		if (ref.startsWith("/s/")) return nonEmpty(ref.substring(3));
		if (ref.startsWith("s/")) return nonEmpty(ref.substring(2));
		if (ref.startsWith(TokenSource.PREFIX)) return nonEmpty(ref);
		return null;
	}

	private static String nonEmpty(String value) {
		return value.isEmpty() ? null : value;
	}
}
