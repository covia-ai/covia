package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.lattice.AccessPolicy;
import covia.lattice.CoviaValue;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.lattice.Covia;
import covia.lattice.Namespace;
import covia.lattice.Entry;
import covia.lattice.Owner;
import covia.lattice.ValuePath;
import convex.lattice.NoSuchPathException;
import covia.lattice.Workspace;
import convex.lattice.cursor.StampedCursor;

/**
 * Per-user domain state, with atomic mutations and compound transactions.
 *
 * <p>Wraps a lattice cursor at {@code :user-data → <did>} within the
 * venue state. Provides typed accessors for per-user data. Created by
 * {@link Users#user(AString)} (returns null if the user doesn't exist),
 * {@link Users#ensure(AString)} (creates if needed) or, for a path,
 * {@link Users#at(AString)} (whether or not the account is live).</p>
 *
 * <p>The per-user lattice uses short AString-compatible keys from
 * {@link Namespace} for JSON compliance:</p>
 * <ul>
 *   <li>{@code "j"} — user's jobs ({@link Jobs})</li>
 *   <li>{@code "g"} — user's agents ({@link Agent})</li>
 *   <li>{@code "s"} — user's encrypted credentials</li>
 *   <li>{@code "w"} — user's workspace data ({@link Workspace}, in a stamped {@code {updated, data}} record)</li>
 *   <li>{@code "o"} — user's operations ({@link Workspace}, in a stamped {@code {updated, data}} record)</li>
 *   <li>{@code "h"} — HITL requests ({@link Inbox}, in a stamped {@code {updated, data}} record)</li>
 *   <li>{@code "a"} — content-addressed assets</li>
 * </ul>
 * Merge is whole-value at the venue {@code :value}; these namespaces are
 * navigation structure only.
 */
public class User extends ALatticeComponent<ACell> implements TransactionalComponent<ACell, User>, Owner {
	@Override
	public User rebind(AForkableCursor<ACell> cursor) {
		return new User(context, cursor, did);
	}

	private final VenueContext context;
	private final AForkableCursor<ACell> edits;

	@Override
	public AForkableCursor<ACell> cursor() { return edits; }

	public VenueContext getContext() { return context; }

	private final AString did;

	/** Who may use this user's data: whoever holds authority over {@code <did>/<path>}. */
	private final AccessPolicy policy;

	/** Framework-internal read of a path in this user's record. */
	ACell readInternalPath(Path path) {
		return resolve(path).cursor().getOrDefault(null);
	}

	/**
	 * Framework-internal atomic write to a workspace path, creating the levels
	 * above it; authority is possession of this user cursor.
	 */
	void writeInternalPath(Path path, ACell value) {
		Entry.of(resolve(path)).execute(draft -> {
			draft.createLevels();
			draft.write(value);
		});
	}

	/** Framework-internal atomic delete of a workspace path; authority is possession of this user cursor. */
	boolean deleteInternalPath(Path path) {
		return Entry.of(resolve(path)).delete();
	}

	User(VenueContext context, AForkableCursor<ACell> cursor, AString did) {
		super(cursor);
		this.context = context;
		this.did = did;
		this.policy = (did == null) ? null : AccessPolicy.principal(did);
		this.edits = StampedCursor.create(cursor, context::currentTimestamp, Covia::stampUserMeta);
	}

	/** This user's workspace ({@code w}). */
	public Workspace workspace() {
		return new Workspace(this.<ACell>wrapped(Namespace.W).withDefault(Maps.empty()));
	}

	/**
	 * Storage size, in bytes, of the data this user and their agents write as
	 * they choose: the workspace ({@code w}) and each agent's own workspace
	 * ({@code n}). It is measured as stored, so a value held in two places
	 * counts twice. Session and job scratch are not counted.
	 *
	 * @return Bytes, zero for a user with no workspace data
	 */
	public long workspaceSize() {
		long size = storedSize(cursor().path(Namespace.W).getOrDefault(null));
		Agents agents = agents();
		for (AString agentId : agents.all().keySet()) {
			Agent agent = agents.agent(agentId);
			if (agent != null) size += storedSize(agent.workspace().cursor().getOrDefault(null));
		}
		return size;
	}

	private static long storedSize(ACell value) {
		return (value == null) ? 0 : Cells.storageSize(value);
	}

	/** This user's operation definitions ({@code o}), held as a workspace. */
	public Workspace operations() {
		return new Workspace(this.<ACell>wrapped(Namespace.O).withDefault(Maps.empty()));
	}

	/** One namespace of this user's record; a change creates it, and the record, when absent. */
	private <T extends ACell> AForkableCursor<T> namespace(AString namespace) {
		return Covia.entry(cursor(), namespace);
	}

	/** A namespace that holds a collection: absent, it reads as {@code empty}, so a transaction on it starts from that. */
	private <T extends ACell> AForkableCursor<T> collection(AString namespace, T empty) {
		return this.<T>namespace(namespace).withDefault(empty);
	}

	/** The data of a namespace held in an {@code {updated, data}} record, which a change stamps. */
	private <T extends ACell> AForkableCursor<T> wrapped(AString namespace) {
		AForkableCursor<ACell> record = StampedCursor.create(namespace(namespace),
				context::currentTimestamp, Covia::stampUpdated);
		return Covia.entry(record, Covia.WORKSPACE_DATA);
	}

	/** This user's agents ({@code g}), a map the first agent creates. */
	public Agents agents() {
		return new Agents(context, collection(Namespace.G, Maps.empty()), did);
	}

	/** Gets the user's DID string. */
	public AString getDID() {
		return did;
	}

	/**
	 * Gets the user's asset store (per-user content-addressed assets).
	 *
	 * @return AssetStore wrapping the user's "a" cursor
	 */
	@SuppressWarnings("unchecked")
	public AssetStore assets() {
		return new AssetStore(collection(Namespace.A, Index.none()));
	}

	/**
	 * Gets the user's secret store (per-user encrypted credentials).
	 *
	 * @return SecretStore wrapping the user's "s" cursor
	 */
	public SecretStore secrets() {
		return new SecretStore(context, collection(Namespace.S, Maps.empty()));
	}

	/** The agent {@code agentId}, deleted or not, or null when it has no record ({@link Agents#agent}). */
	public Agent agent(String agentId) {
		return agent(Strings.create(agentId));
	}

	/** The agent {@code agentId}, deleted or not, or null when it has no record ({@link Agents#agent}). */
	public Agent agent(AString agentId) {
		return agents().agent(agentId);
	}

	/**
	 * Gets the raw per-user state value.
	 *
	 * @return User state, or null if uninitialised
	 */
	public ACell get() {
		return cursor().getOrDefault(null);
	}

	/** This user's HITL inbox ({@code h/}). */
	public Inbox inbox() {
		return new Inbox(this.<Index<Blob, AMap<AString, ACell>>>wrapped(Namespace.H).withDefault(Index.none()));
	}

	/** This user's jobs ({@code j/}), bound to this user or transaction draft. */
	public Jobs jobs() {
		return new Jobs(context, collection(Namespace.J, Index.none()), did);
	}

	/** Whoever holds authority over {@code <did>/<path>}; none outside any user. */
	@Override
	public AccessPolicy policy() {
		return policy;
	}

	/** The user's jobs ({@code j}), and an agent ({@code g/<id>}) whether or not it exists. */
	@Override
	public Route route(AString[] segments, int start, int end) {
		if (start == end) return null;
		AString segment = segments[start];
		if (Namespace.J.equals(segment)) return new Route(jobs(), 1);
		if (Namespace.G.equals(segment) && end - start > 1) return new Route(agents().at(segments[start + 1]), 2);
		return null;
	}

	/**
	 * A user's namespaces ({@code w}, {@code o}, {@code h}, {@code j},
	 * {@code s}, {@code a}, {@code g}) are each resolved by their component, and
	 * {@code g/<id>} by that agent whether or not it exists. Any other name,
	 * the venue's own bookkeeping in the record among them, names no data.
	 * The user's policy covers whatever no owner below claims.
	 */
	@Override
	public ALatticeComponent<?> resolve(AString[] segments, int start, int end) {
		if (start == end) return this;
		Route route = route(segments, start, end);
		if (route != null) return route.child().resolve(segments, start + route.taken(), end);
		AString segment = segments[start];
		if (Namespace.W.equals(segment)) return workspace().resolve(segments, start + 1, end);
		if (Namespace.O.equals(segment)) return operations().resolve(segments, start + 1, end);
		if (Namespace.H.equals(segment)) return inbox().resolve(segments, start + 1, end);
		if (Namespace.S.equals(segment)) return secrets().resolve(segments, start + 1, end);
		if (Namespace.A.equals(segment)) return assets().resolve(segments, start + 1, end);
		if (Namespace.G.equals(segment)) return CoviaValue.within(this, segments, start, end);
		throw new NoSuchPathException(segments, start, end);
	}

}
