package covia.adapter;

import java.security.SecureRandom;
import java.util.function.UnaryOperator;

import convex.core.crypto.Hashing;
import convex.core.crypto.util.Base58;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blobs;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;

/**
 * The MCP tokens a venue holds, as the mcp adapter's state (MCP_TOKENS.md):
 *
 * <pre>
 *   tokens:  { &lt;owner DID&gt;: { &lt;name&gt;: record } }
 *   bearers: { &lt;hex hash of a bearer&gt;: [&lt;owner DID&gt;, &lt;name&gt;] }
 * </pre>
 *
 * <p>A record and its bearer entry change together, each change in one
 * transaction. The venue stores a bearer's hash, and the bearer itself only
 * sealed, while it keeps raw tokens.</p>
 */
public final class McpTokens extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, McpTokens> {

	static final AString TOKENS = Strings.intern("tokens");
	static final AString BEARERS = Strings.intern("bearers");
	static final AString HASH = Strings.intern("hash");
	/** A record's bearer, sealed ({@link covia.venue.SecretStore#seal}), while the venue keeps raw tokens. */
	static final AString SEALED = Strings.intern("sealed");

	/** What every bearer starts with, so it is told from a JWT and secret scanners know it. */
	public static final String BEARER_PREFIX = "cvmcp_";

	private static final SecureRandom RANDOM = new SecureRandom();

	McpTokens(AForkableCursor<ACell> cursor) {
		super(cursor);
	}

	@Override
	public McpTokens rebind(AForkableCursor<ACell> cursor) {
		return new McpTokens(cursor);
	}

	@Override
	public McpTokens withCursor(AForkableCursor<ACell> cursor) {
		return rebind(cursor);
	}

	/** A new bearer: the prefix and 32 random bytes in base58. */
	public static String newBearer() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return BEARER_PREFIX + Base58.encode(bytes);
	}

	/** A new token id. */
	static AString newId() {
		return Strings.create(Blobs.createRandom(RANDOM, 16).toHexString());
	}

	/** The hex SHA3-256 hash a bearer is recognised by. */
	public static AString hashOf(String bearer) {
		return Strings.create(Hashing.sha3(bearer).toHexString());
	}

	/** Whether a presented bearer has an MCP token's form, so is looked up here and nowhere else. */
	public static boolean isBearer(String bearer) {
		return bearer != null && bearer.startsWith(BEARER_PREFIX);
	}

	private AMap<AString, ACell> root() {
		AMap<AString, ACell> root = RT.ensureMap(cursor().getOrDefault(null));
		return (root == null) ? Maps.empty() : root;
	}

	private static AMap<AString, ACell> map(ACell cell) {
		AMap<AString, ACell> map = RT.ensureMap(cell);
		return (map == null) ? Maps.empty() : map;
	}

	/** The token {@code name} in {@code owner}'s namespace, which need not exist. */
	public McpToken token(AString owner, AString name) {
		return new McpToken(cursor().path(TOKENS, owner, name), owner, name);
	}

	/** Every token in {@code owner}'s namespace, by name; empty when there are none. */
	public AMap<AString, ACell> list(AString owner) {
		return map(map(root().get(TOKENS)).get(owner));
	}

	/**
	 * Adds a token's record, and its bearer entry.
	 *
	 * @return whether it was added: false when the name is taken
	 */
	public boolean create(AString owner, AString name, AMap<AString, ACell> record) {
		return transact(draft -> {
			AMap<AString, ACell> root = draft.root();
			AMap<AString, ACell> tokens = map(root.get(TOKENS));
			AMap<AString, ACell> mine = map(tokens.get(owner));
			if (mine.containsKey(name)) return false;
			root = root.assoc(TOKENS, tokens.assoc(owner, mine.assoc(name, record)));
			root = withBearer(root, RT.ensureString(record.get(HASH)), owner, name);
			draft.cursor().set(root);
			return true;
		});
	}

	/**
	 * Changes a token's record, moving its bearer entry when its hash changes.
	 *
	 * @param change the record as it should be, from the record as it stands
	 * @return the changed record, or null when there is no such token
	 */
	public AMap<AString, ACell> update(AString owner, AString name, UnaryOperator<AMap<AString, ACell>> change) {
		return transact(draft -> {
			AMap<AString, ACell> root = draft.root();
			AMap<AString, ACell> tokens = map(root.get(TOKENS));
			AMap<AString, ACell> mine = map(tokens.get(owner));
			AMap<AString, ACell> record = RT.ensureMap(mine.get(name));
			if (record == null) return null;
			AMap<AString, ACell> changed = change.apply(record);
			AString oldHash = RT.ensureString(record.get(HASH));
			AString newHash = RT.ensureString(changed.get(HASH));
			root = root.assoc(TOKENS, tokens.assoc(owner, mine.assoc(name, changed)));
			if (!java.util.Objects.equals(oldHash, newHash)) {
				root = withBearer(withoutBearer(root, oldHash), newHash, owner, name);
			}
			draft.cursor().set(root);
			return changed;
		});
	}

	/**
	 * Removes a token and its bearer entry.
	 *
	 * @return the record removed, or null when there was none
	 */
	public AMap<AString, ACell> delete(AString owner, AString name) {
		return transact(draft -> {
			AMap<AString, ACell> root = draft.root();
			AMap<AString, ACell> record = RT.ensureMap(map(map(root.get(TOKENS)).get(owner)).get(name));
			if (record == null) return null;
			draft.cursor().set(without(root, owner, name, record));
			return record;
		});
	}

	/**
	 * Removes every token in {@code owner}'s namespace, as its account goes.
	 * One cursor update, so it may run on the draft of a larger transaction.
	 */
	public void deleteOwner(AString owner) {
		cursor().updateAndGet(value -> {
			AMap<AString, ACell> root = map(value);
			AMap<AString, ACell> mine = list(root, owner);
			if (mine.isEmpty()) return value;
			for (var entry : mine.entrySet()) {
				root = without(root, owner, entry.getKey(), RT.ensureMap(entry.getValue()));
			}
			return root;
		});
	}

	/**
	 * Removes every token {@code issuer} created in {@code owner}'s namespace,
	 * as that agent goes. One cursor update, as {@link #deleteOwner}.
	 */
	public void deleteIssuer(AString owner, AString issuer) {
		cursor().updateAndGet(value -> {
			AMap<AString, ACell> root = map(value);
			AMap<AString, ACell> before = root;
			for (var entry : list(root, owner).entrySet()) {
				AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
				if (record != null && issuer.equals(record.get(Fields.ISSUER))) {
					root = without(root, owner, entry.getKey(), record);
				}
			}
			return (root == before) ? value : root;
		});
	}

	/** Removes every kept bearer, when the venue stops keeping raw tokens. */
	public void dropKept() {
		cursor().updateAndGet(value -> {
			AMap<AString, ACell> root = map(value);
			AMap<AString, ACell> tokens = map(root.get(TOKENS));
			AMap<AString, ACell> kept = tokens;
			for (var owners : tokens.entrySet()) {
				AMap<AString, ACell> mine = map(owners.getValue());
				AMap<AString, ACell> cleared = mine;
				for (var entry : mine.entrySet()) {
					AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
					if (record != null && record.containsKey(SEALED)) cleared = cleared.assoc(entry.getKey(), record.dissoc(SEALED));
				}
				if (cleared != mine) kept = kept.assoc(owners.getKey(), cleared);
			}
			return (kept == tokens) ? value : root.assoc(TOKENS, kept);
		});
	}

	/**
	 * The reference of the token whose bearer this is, or null when none is:
	 * {@code {owner, name, id}}. Whether it has expired is for whoever uses it.
	 */
	public AMap<AString, ACell> lookup(String bearer) {
		if (!isBearer(bearer)) return null;
		AMap<AString, ACell> root = root();
		AVector<ACell> at = RT.ensureVector(map(root.get(BEARERS)).get(hashOf(bearer)));
		if (at == null || at.count() != 2) return null;
		AString owner = RT.ensureString(at.get(0));
		AString name = RT.ensureString(at.get(1));
		AMap<AString, ACell> record = RT.ensureMap(list(root, owner).get(name));
		if (record == null) return null;
		return McpToken.reference(owner, name, RT.ensureString(record.get(Fields.ID)));
	}

	/**
	 * The token a reference names, while it stands: a record of that name and
	 * id that has not expired at {@code now}; otherwise null.
	 */
	public McpToken resolve(AMap<AString, ACell> reference, long now) {
		if (reference == null) return null;
		AString owner = RT.ensureString(reference.get(Fields.OWNER));
		AString name = RT.ensureString(reference.get(Fields.NAME));
		AString id = RT.ensureString(reference.get(Fields.ID));
		if (owner == null || name == null || id == null) return null;
		McpToken token = token(owner, name);
		if (!id.equals(token.id()) || token.expired(now)) return null;
		return token;
	}

	private static AMap<AString, ACell> list(AMap<AString, ACell> root, AString owner) {
		return map(map(root.get(TOKENS)).get(owner));
	}

	private static AMap<AString, ACell> without(AMap<AString, ACell> root, AString owner, AString name, AMap<AString, ACell> record) {
		AMap<AString, ACell> tokens = map(root.get(TOKENS));
		AMap<AString, ACell> mine = map(tokens.get(owner)).dissoc(name);
		root = root.assoc(TOKENS, mine.isEmpty() ? tokens.dissoc(owner) : tokens.assoc(owner, mine));
		return withoutBearer(root, (record == null) ? null : RT.ensureString(record.get(HASH)));
	}

	private static AMap<AString, ACell> withBearer(AMap<AString, ACell> root, AString hash, AString owner, AString name) {
		if (hash == null) return root;
		return root.assoc(BEARERS, map(root.get(BEARERS)).assoc(hash, Vectors.of(owner, name)));
	}

	private static AMap<AString, ACell> withoutBearer(AMap<AString, ACell> root, AString hash) {
		if (hash == null) return root;
		return root.assoc(BEARERS, map(root.get(BEARERS)).dissoc(hash));
	}
}
