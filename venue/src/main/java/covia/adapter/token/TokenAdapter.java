package covia.adapter.token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import covia.adapter.AAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConfigurationException;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.lattice.Caps;
import covia.venue.AdapterState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.SecretStore;

/**
 * Access tokens (ACCESS_TOKENS.md): scoped bearers that act with their
 * issuer's authority within fixed caps, accepted by the access points they
 * carry entries for. This adapter is their infrastructure: it keeps them, as
 * its state ({@link AccessTokens}), creates and rotates their bearers, and
 * offers the {@code token:} operations, which work on tokens directly and need
 * {@code admin/token/<access point>} for every access point a token carries.
 * An access point's own operations, such as the MCP adapter's, reach the same
 * tokens through {@link #create} and its siblings with a {@link Gate} of their
 * own.
 */
public class TokenAdapter extends AAdapter {

	/** The adapter's name. */
	public static final String NAME = "token";

	public static final String TOKEN_NAME_INVALID = "name is required and must match [a-z0-9-]{1,64}";
	public static final String TOKEN_NOT_FOUND = "No token named %s";
	public static final String TOKEN_EXISTS = "A token named %s already exists: update or delete it";
	public static final String TOKEN_CHANGED = "Token %s changed while this request was checking it: try again";
	public static final String ACCESS_INVALID = "access must be an object naming at least one access point, each with an object entry";
	public static final String ACCESS_POINT_UNKNOWN = "access.%s names no access point this venue runs";
	public static final String NOT_ONLY_AT = "Token %s is accepted beyond %s: change it with the token operations";
	public static final String EXPIRES_INVALID = "expires must be a time still to come, in milliseconds since the epoch";
	public static final String RAW_NOT_KEPT = "This venue keeps only the hashes of tokens, not the tokens (adapters.token.keepRawTokens)";
	public static final String RAW_NOT_HELD = "Token %s was created or last rotated while the venue kept no tokens: rotate it";
	public static final String TOKEN_NOT_COVERED = "Token %s can only be changed or read by a request acting with its issuer's authority, covering its caps";
	public static final String ISSUER_MAY_NOT_CREATE = "%s may not create tokens";
	public static final String KEEP_RAW_NOT_BOOLEAN = "adapters.token.keepRawTokens must be a boolean";

	/** Adapter config: whether the venue keeps each token's bearer, sealed, beside its hash. */
	static final AString KEEP_RAW_TOKENS = Strings.intern("keepRawTokens");

	/** The resource a namespace's tokens are under, {@code x/token}; one token is {@link #resource}. */
	static final AString TOKENS_RESOURCE = Strings.intern("x/token");

	private static final AString K_TOKENS = Strings.intern("tokens");

	private static final Pattern TOKEN_NAME = Pattern.compile("[a-z0-9-]{1,64}");

	/**
	 * Who may act on a token: checked with the access points the token carries
	 * before the change and after it, each empty when there is no token then.
	 * It throws when the request may not.
	 */
	@FunctionalInterface
	public interface Gate {
		void check(RequestContext ctx, AString name, Set<AString> before, Set<AString> after);
	}

	/** A token as an operation leaves it, and its bearer when that operation handed out a new one. */
	public record Issued(AccessToken token, String bearer) {}

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Keeps the venue's access tokens: scoped bearers that act with their issuer's authority within fixed caps, "
			+ "accepted by the access points they name, such as the MCP endpoint. Administers them directly.";
	}

	@Override
	protected void installAssets() {
		installAsset("token/create", "/adapters/token/create.json");
		installAsset("token/update", "/adapters/token/update.json");
		installAsset("token/delete", "/adapters/token/delete.json");
		installAsset("token/list",   "/adapters/token/list.json");
		installAsset("token/get",    "/adapters/token/get.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		return CompletableFuture.supplyAsync(() -> switch (op) {
			case "create" -> result(create(ctx, requireName(input), RT.getIn(input, Fields.ACCESS),
				RT.getIn(input, Fields.CAPS), RT.getIn(input, Fields.EXPIRES), administration()));
			case "update" -> result(update(ctx, requireName(input), RT.getIn(input, Fields.ACCESS),
				RT.getIn(input, Fields.CAPS), RT.getIn(input, Fields.EXPIRES),
				CVMBool.TRUE.equals(RT.getIn(input, Fields.ROTATE)), administration()));
			case "delete" -> deleted(delete(ctx, requireName(input), administration()));
			case "list" -> listed(list(ctx, token -> true), TokenAdapter::summary);
			case "get" -> Maps.of(Fields.NAME, requireName(input), Fields.TOKEN, bearer(ctx, requireName(input), administration()));
			default -> throw new NotSupportedException("Unsupported token operation: " + op);
		}, VIRTUAL_EXECUTOR);
	}

	// ========== The store and its configuration ==========

	/** The venue's access tokens. */
	public AccessTokens tokens() {
		return new AccessTokens(adapterState().cursor());
	}

	@Override
	public void configure(AMap<AString, ACell> config, boolean strict) {
		ACell keep = (config == null) ? null : config.get(KEEP_RAW_TOKENS);
		if (keep != null && !(keep instanceof CVMBool)) throw new ConfigurationException(KEEP_RAW_NOT_BOOLEAN);
		// Reconfigured to stop keeping tokens: the kept ones go now (at boot, start does it)
		if (engine != null && !CVMBool.TRUE.equals(keep)) tokens().dropKept();
	}

	@Override
	public void start() {
		if (!keepRawTokens()) tokens().dropKept();
	}

	/** Whether the venue keeps each token's bearer, sealed, beside its hash. */
	boolean keepRawTokens() {
		return CVMBool.TRUE.equals(engine.adapterConfig(getName()).get(KEEP_RAW_TOKENS));
	}

	/**
	 * What a user sees of its tokens at {@code x/token}: each by name, with what
	 * {@link #summary} shows (issuer, access, caps, creation and expiry), never
	 * a bearer, its hash or the token's id. A snapshot, so read-only.
	 */
	@Override
	public AForkableCursor<ACell> latticeView(RequestContext ctx) {
		AString owner = ctx.getUserDID();
		if (owner == null) return null;
		AMap<AString, ACell> shown = Maps.empty();
		for (var entry : tokens().list(owner).entrySet()) {
			AccessToken token = tokens().token(owner, entry.getKey());
			if (token.record() != null) shown = shown.assoc(entry.getKey(), summary(token).dissoc(Fields.NAME));
		}
		return Cursors.create((ACell) shown);
	}

	@Override
	public void deleteUserState(AString did, AdapterState state) {
		new AccessTokens(state.cursor()).deleteOwner(did);
	}

	@Override
	public void deleteAgentState(AString agentDID, AdapterState state) {
		new AccessTokens(state.cursor()).deleteIssuer(covia.grid.Principals.userOf(agentDID), agentDID);
	}

	// ========== Gates ==========

	/** The capability resource a token is: {@code x/token/<name>} in its namespace. */
	public static AString resource(AString name) {
		return Strings.create(TOKENS_RESOURCE + "/" + name);
	}

	/** The ability that administers tokens carrying access point {@code point}: {@code admin/token/<point>}. */
	public static AString administers(AString point) {
		return Strings.create(Abilities.ADMIN_TOKEN + "/" + point);
	}

	/**
	 * Working on tokens directly: {@code admin/token/<p>} on the token for every
	 * access point {@code p} it carries before or after, which the parent
	 * {@code admin/token} covers. With none either side, {@code admin/token} itself.
	 */
	public Gate administration() {
		return (ctx, name, before, after) -> {
			Set<AString> points = new LinkedHashSet<>(before);
			points.addAll(after);
			if (points.isEmpty()) engine.requireAuthority(ctx, resource(name), Abilities.ADMIN_TOKEN);
			for (AString point : points) engine.requireAuthority(ctx, resource(name), administers(point));
		};
	}

	/**
	 * An access point's own operations: {@code ability} on the token, which
	 * carries access point {@code point} alone before and after, so they never
	 * reach a token they could not have made.
	 */
	public Gate onlyAt(AString point, AString ability) {
		return (ctx, name, before, after) -> {
			engine.requireAuthority(ctx, resource(name), ability);
			if (!carriesOnly(before, point) || !carriesOnly(after, point)) throw new AuthException(NOT_ONLY_AT.formatted(name, point));
		};
	}

	/** Whether {@code points} is none, or {@code point} alone. */
	private static boolean carriesOnly(Set<AString> points, AString point) {
		return points.isEmpty() || (points.size() == 1 && points.contains(point));
	}

	// ========== Working on tokens ==========

	/**
	 * Creates a token in the caller's namespace, issued by the principal whose
	 * authority the request acts with ({@link Engine#actingPrincipal}), with a
	 * new bearer, handed out this once. Its caps lie within what the request
	 * may do itself ({@link Engine#capsDenial}).
	 *
	 * @param access what the caller asks of each access point, by name
	 * @param caps {@code "unrestricted"}, a list of capabilities, or null for none
	 * @param expires milliseconds since the epoch, or null for never
	 */
	public Issued create(RequestContext ctx, AString name, ACell access, ACell caps, ACell expires, Gate gate) {
		AString owner = owner(ctx);
		AString principal = Engine.actingPrincipal(ctx);
		if (principal == null || engine.isPublicPrincipal(principal) || engine.getDIDString().equals(principal)) {
			throw new AuthException(ISSUER_MAY_NOT_CREATE.formatted(principal));
		}
		AMap<AString, ACell> asked = askedAccess(access);
		gate.check(ctx, name, Set.of(), AccessToken.accessPoints(asked));
		// A delegate acting for another user holds none of that user's own authority to hand on.
		if (!engine.actsWithAuthorityOf(ctx, principal)) throw new AuthException(ISSUER_MAY_NOT_CREATE.formatted(principal));
		AMap<AString, ACell> entries = entries(ctx, asked);
		AVector<ACell> stored = (caps == null) ? Vectors.empty() : Caps.parse(caps);
		requireGivable(ctx, stored);
		Long until = expiry(expires);

		String bearer = AccessTokens.newBearer();
		AMap<AString, ACell> record = Maps.of(
			Fields.ID, AccessTokens.newId(),
			Fields.ISSUER, principal,
			Fields.ACCESS, entries,
			Fields.CREATED, engine.currentTimestamp());
		if (stored != null) record = record.assoc(Fields.CAPS, stored);
		if (until != null) record = record.assoc(Fields.EXPIRES, CVMLong.create(until));
		record = withBearer(record, bearer);
		if (!tokens().create(owner, name, record)) throw new ConflictException(TOKEN_EXISTS.formatted(name));
		return new Issued(tokens().token(owner, name), bearer);
	}

	/**
	 * Changes a token's access, caps or expiry, or gives it a new bearer
	 * ({@code rotate}); what is null stays as it is, and {@code access} replaces
	 * all of its entries. Each needs a request acting with its issuer's
	 * authority that covers its caps as they are and as they become, so no
	 * request widens it beyond what it may do itself.
	 */
	public Issued update(RequestContext ctx, AString name, ACell access, ACell caps, ACell expires, boolean rotate, Gate gate) {
		AString owner = owner(ctx);
		AccessToken token = tokens().token(owner, name);
		Set<AString> before = token.accessPoints();
		AMap<AString, ACell> asked = (access == null) ? null : askedAccess(access);
		gate.check(ctx, name, before, (asked == null) ? before : AccessToken.accessPoints(asked));
		AString id = token.id();
		if (id == null) throw new NotFoundException(TOKEN_NOT_FOUND.formatted(name), name.toString());
		requireCovers(ctx, token);
		AMap<AString, ACell> entries = (asked == null) ? null : entries(ctx, asked);
		AVector<ACell> stored = (caps == null) ? null : Caps.parse(caps);
		if (caps != null) requireGivable(ctx, stored);
		Long until = expiry(expires);
		String bearer = rotate ? AccessTokens.newBearer() : null;

		AtomicBoolean same = new AtomicBoolean();
		tokens().update(owner, name, record -> {
			// Changed only if it is still the token the checks above were made on
			same.set(isStill(record, id, before));
			if (!same.get()) return record;
			AMap<AString, ACell> next = record;
			if (entries != null) next = next.assoc(Fields.ACCESS, entries);
			if (caps != null) next = (stored == null) ? next.dissoc(Fields.CAPS) : next.assoc(Fields.CAPS, stored);
			if (until != null) next = next.assoc(Fields.EXPIRES, CVMLong.create(until));
			return (bearer == null) ? next : withBearer(next, bearer);
		});
		if (!same.get()) throw new ConflictException(TOKEN_CHANGED.formatted(name));
		return new Issued(tokens().token(owner, name), bearer);
	}

	/** Refuses caps beyond what {@code ctx} may do itself ({@link Engine#capsDenial}); null is unrestricted. */
	private void requireGivable(RequestContext ctx, AVector<ACell> caps) {
		String denial = engine.capsDenial(ctx, caps);
		if (denial != null) throw new AuthException(denial);
	}

	/**
	 * Deletes a token: its bearer stops working, and what it narrowed holds nothing.
	 *
	 * @return the token's name
	 */
	public AString delete(RequestContext ctx, AString name, Gate gate) {
		AString owner = owner(ctx);
		AccessToken token = tokens().token(owner, name);
		Set<AString> before = token.accessPoints();
		gate.check(ctx, name, before, Set.of());
		AString id = token.id();
		if (id == null || tokens().delete(owner, name, record -> isStill(record, id, before)) == null) {
			throw new NotFoundException(TOKEN_NOT_FOUND.formatted(name), name.toString());
		}
		return name;
	}

	/** The tokens in the caller's namespace that {@code which} takes, needing {@code crud/read} on {@code x/token}. */
	public List<AccessToken> list(RequestContext ctx, Predicate<AccessToken> which) {
		AString owner = owner(ctx);
		engine.requireAuthority(ctx, TOKENS_RESOURCE, Capability.CRUD_READ);
		List<AccessToken> listed = new ArrayList<>();
		for (var entry : tokens().list(owner).entrySet()) {
			AccessToken token = tokens().token(owner, entry.getKey());
			if (which.test(token)) listed.add(token);
		}
		return listed;
	}

	/** A token's bearer again, where the venue keeps it, to whoever {@code gate} admits and covers the token ({@link #requireCovers}). */
	public AString bearer(RequestContext ctx, AString name, Gate gate) {
		AString owner = owner(ctx);
		AccessToken token = tokens().token(owner, name);
		Set<AString> points = token.accessPoints();
		gate.check(ctx, name, points, points);
		if (!keepRawTokens()) throw new NotSupportedException(RAW_NOT_KEPT);
		if (token.record() == null) throw new NotFoundException(TOKEN_NOT_FOUND.formatted(name), name.toString());
		requireCovers(ctx, token);
		ACell sealed = token.sealedBearer();
		if (sealed == null) throw new NotSupportedException(RAW_NOT_HELD.formatted(name));
		return SecretStore.unseal(sealed, sealingKeys());
	}

	/** A token's name from an operation's input. */
	public static AString requireName(ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || !TOKEN_NAME.matcher(name.toString()).matches()) throw new InvalidInputException(TOKEN_NAME_INVALID);
		return name;
	}

	/** Caps as a token shows them: {@code "unrestricted"} for none stored. */
	public static ACell shownCaps(AccessToken token) {
		AVector<ACell> caps = token.caps().scope();
		return (caps == null) ? Caps.UNRESTRICTED : caps;
	}

	// ========== What the operations return ==========

	/** What the token operations show of a token, never its bearer. */
	static AMap<AString, ACell> summary(AccessToken token) {
		AMap<AString, ACell> shown = Maps.of(
			Fields.NAME, token.name(),
			Fields.ISSUER, token.issuer(),
			Fields.ACCESS, token.access(),
			Fields.CAPS, shownCaps(token),
			Fields.CREATED, token.record().get(Fields.CREATED));
		Long expires = token.expires();
		return (expires == null) ? shown : shown.assoc(Fields.EXPIRES, CVMLong.create(expires));
	}

	private static AMap<AString, ACell> result(Issued issued) {
		AMap<AString, ACell> shown = summary(issued.token());
		return (issued.bearer() == null) ? shown : shown.assoc(Fields.TOKEN, Strings.create(issued.bearer()));
	}

	/** What a delete returns. */
	public static AMap<AString, ACell> deleted(AString name) {
		return Maps.of(Fields.NAME, name, Fields.DELETED, CVMBool.TRUE);
	}

	/** What a list returns: each token as {@code shown} shows it. */
	public static AMap<AString, ACell> listed(List<AccessToken> tokens, java.util.function.Function<AccessToken, AMap<AString, ACell>> shown) {
		AVector<ACell> items = Vectors.empty();
		for (AccessToken token : tokens) items = items.conj(shown.apply(token));
		return Maps.of(K_TOKENS, items, Fields.TOTAL, AInteger.create(items.count()));
	}

	// ========== Plumbing ==========

	/** The namespace a token request acts in, which its tokens belong to. */
	private static AString owner(RequestContext ctx) {
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException("Authentication required");
		return owner;
	}

	/** The access a caller asks for: a non-empty map naming only access points this venue runs. */
	private AMap<AString, ACell> askedAccess(ACell access) {
		AMap<AString, ACell> asked = RT.ensureMap(access);
		if (asked == null || asked.isEmpty()) throw new InvalidInputException(ACCESS_INVALID);
		for (var entry : asked.entrySet()) {
			if (accessPoint(engine, entry.getKey()) == null) throw new InvalidInputException(ACCESS_POINT_UNKNOWN.formatted(entry.getKey()));
		}
		return asked;
	}

	/** Each asked access point's entry, as that access point keeps it. */
	private AMap<AString, ACell> entries(RequestContext ctx, AMap<AString, ACell> asked) {
		AMap<AString, ACell> entries = Maps.empty();
		for (var entry : asked.entrySet()) {
			TokenAccessPoint point = accessPoint(engine, entry.getKey());
			if (point == null) throw new InvalidInputException(ACCESS_POINT_UNKNOWN.formatted(entry.getKey()));
			AMap<AString, ACell> kept = point.tokenEntry(ctx, entry.getValue());
			if (kept == null) throw new InvalidInputException(ACCESS_INVALID);
			entries = entries.assoc(entry.getKey(), kept);
		}
		return entries;
	}

	/**
	 * The access point named {@code point} while it runs: an enabled adapter
	 * that accepts tokens and has not failed ({@link Engine#adapterFailure}).
	 *
	 * @return the access point, or null when {@code engine} runs none of that name
	 */
	public static TokenAccessPoint accessPoint(Engine engine, AString point) {
		if (point == null) return null;
		String name = point.toString();
		return (engine.getAdapter(name) instanceof TokenAccessPoint ap && engine.adapterFailure(name) == null) ? ap : null;
	}

	/** Whether {@code record} is still the token of id {@code id} that carried exactly {@code points}. */
	private static boolean isStill(AMap<AString, ACell> record, AString id, Set<AString> points) {
		return Objects.equals(id, record.get(Fields.ID))
			&& AccessToken.accessPoints(RT.ensureMap(record.get(Fields.ACCESS))).equals(points);
	}

	/** The expiry asked for, or null for never; it must be still to come. */
	private Long expiry(ACell expires) {
		if (expires == null) return null;
		if (!(expires instanceof CVMLong at) || at.longValue() <= engine.currentTimestamp().longValue()) {
			throw new InvalidInputException(EXPIRES_INVALID);
		}
		return at.longValue();
	}

	/**
	 * Refuses {@code ctx} unless it acts with the authority of {@code token}'s
	 * issuer ({@link Engine#actsWithAuthorityOf}) and may itself do all that
	 * the token's caps allow ({@link Engine#capsDenial}).
	 */
	private void requireCovers(RequestContext ctx, AccessToken token) {
		if (!engine.actsWithAuthorityOf(ctx, token.issuer()) || engine.capsDenial(ctx, token.caps().scope()) != null) {
			throw new AuthException(TOKEN_NOT_COVERED.formatted(token.name()));
		}
	}

	/** The fields a new bearer sets on a record: its hash, and itself sealed while the venue keeps tokens. */
	private AMap<AString, ACell> withBearer(AMap<AString, ACell> record, String bearer) {
		record = record.assoc(AccessTokens.HASH, AccessTokens.hashOf(bearer));
		if (!keepRawTokens()) return record.dissoc(AccessTokens.SEALED);
		return record.assoc(AccessTokens.SEALED, SecretStore.seal(Strings.create(bearer), sealingKeys()));
	}

	/** The keys kept bearers are sealed under: the venue's secret store keys, as for other credentials an adapter holds. */
	private SecretStore.Keys sealingKeys() {
		return SecretStore.keys(engine.getKeyPair());
	}
}
