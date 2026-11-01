package covia.lattice;

import static covia.lattice.DataComponent.containsLeaf;
import static covia.lattice.DataComponent.deepDelete;
import static covia.lattice.DataComponent.deepGet;
import static covia.lattice.DataComponent.deepSet;
import static covia.lattice.DataComponent.leafExisted;
import static covia.lattice.DataComponent.navigateInto;

import java.util.Arrays;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.LatticeOps;
import convex.lattice.TransactionalComponent;
import convex.lattice.generic.JSONLattice;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import covia.grid.Job;
import covia.venue.VenueContext;
import covia.lattice.DataComponent.PathUpdate;

/**
 * Path operations and transactions over an already-authorised workspace.
 * The owning component supplies its clock and namespace layout. Callers use the
 * same reads and mutations on live and transaction views; this object preserves
 * record envelopes, stored nulls, and map/vector navigation semantics.
 * External requests must pass authority checks before obtaining this view.
 */
public record WorkspaceRoot(AForkableCursor<ACell> cursor, VenueContext context,
		boolean userNamespaces) implements TransactionalComponent<ACell, WorkspaceRoot> {

	/** Refusal for a change to scoped data whose record is missing; names the region. */
	public static final String NO_PARENT_RECORD = "Scoped path is not available: %s has no parent record";

	@Override
	public AForkableCursor<ACell> storage() { return cursor; }

	@Override
	public WorkspaceRoot withCursor(AForkableCursor<ACell> draft) {
		return new WorkspaceRoot(draft, context, userNamespaces);
	}

	public AForkableCursor<ACell> child(ACell key) {
		AForkableCursor<ACell> child = cursor.withDefault(convex.core.data.Maps.empty()).path(key);
		if (!userNamespaces || !(Namespace.W.equals(key) || Namespace.O.equals(key) || Namespace.H.equals(key))) {
			return child;
		}
		return StampedCursor.create(child, context::currentTimestamp, Covia::stampUpdated)
				.withDefault(convex.core.data.Maps.empty()).path(Covia.WORKSPACE_DATA);
	}
	/**
	 * The namespace container a path addresses: a cursor at its caller-visible
	 * value, plus the index of the first path key below it.
	 *
	 * <p>Physical user paths cross the namespace boundary via {@link #child},
	 * preserving workspace envelopes. Unwrapped virtual views navigate directly
	 * from their exposed value using the same type-aware path operations.</p>
	 */
	private record Target(AForkableCursor<ACell> root, int from) {
		static Target of(WorkspaceRoot cursor, ACell[] keys) {
			// Unwrapped views may themselves be vectors or Indexes. Navigate their
			// first key by value type too, rather than assuming a namespace map.
			return (keys.length == 0 || !cursor.userNamespaces())
				? new Target(cursor.cursor(), 0)
				: new Target(cursor.child(keys[0]), 1);
		}
	}

	/**
	 * The data at a path, for a caller's operations on it. Its edits apply
	 * through {@link #update}; scoped data (agent, session and job scratch)
	 * also requires its record to exist.
	 *
	 * @param address The address the caller named the data by
	 * @param keys Path keys within this workspace
	 * @param requireExistingRecord Whether this workspace's record must exist
	 * @return The data component at that path
	 */
	public DataComponent data(AString address, ACell[] keys, boolean requireExistingRecord) {
		Target target = Target.of(this, keys);
		DataComponent.Edits edits = requireExistingRecord
			? new DataComponent.Edits() {
				@Override public void apply(PathUpdate change) { update(keys, true, change); }
				@Override public void requireAvailable() {
					if (cursor.get() == null) throw noParentRecord(keys);
				}
			}
			: change -> update(keys, false, change);
		return new DataComponent(context, address, target.root(), keys, target.from(), edits);
	}

	private static IllegalArgumentException noParentRecord(ACell[] keys) {
		String container = (keys.length == 0) ? "root" : keys[0].toString();
		return new IllegalArgumentException(String.format(NO_PARENT_RECORD, container));
	}

	/**
	 * Atomically changes a path within this already-authorised workspace.
	 * The callback may retry; it receives the namespace value and the first
	 * remaining path index. Scoped scratch must require its parent record.
	 */
	public void update(ACell[] keys,
			boolean requireExistingRecord, PathUpdate update) {
		if (requireExistingRecord) {
			// The precondition and mutation must observe the same base value. A
			// separate get() followed by a descendant update permits a concurrent
			// delete to win between them, after which structural JSON navigation
			// would auto-vivify the missing record again.
			cursor().updateAndGet(base -> {
				if (base == null) throw noParentRecord(keys);
				if (keys.length == 0) return update.apply(base, 0);

				ACell current = navigateInto(base, keys[0]);
				ACell replacement = update.apply(current, 1);
				if (Utils.equals(current, replacement)) return base;
				return LatticeOps.assocIn(base, replacement, JSONLattice.INSTANCE, keys[0]);
			});
			return;
		}

		Target target = Target.of(this, keys);
		target.root().updateAndGet(current -> update.apply(current, target.from()));
	}


	/** Writes a path atomically, preserving namespace stamps and value shapes. */
	public void write(ACell[] keys, ACell value) {
		if (keys == null || keys.length == 0) {
			throw new IllegalArgumentException("A non-empty cursor-relative path is required");
		}
		update(keys, false,
			(current, from) -> deepSet(current, keys, from, value));
	}

	/** Deletes a path atomically; stored null counts as present. */
	public boolean delete(ACell... keys) {
		if (keys == null || keys.length == 0) {
			throw new IllegalArgumentException("A non-empty cursor-relative path is required");
		}
		if (!contains(keys)) return false;
		final boolean[] deleted = {false};
		update(keys, false, (current, from) -> {
			deleted[0] = leafExisted(current, keys, from);
			return deepDelete(current, keys, from);
		});
		return deleted[0];
	}

	/**
	 * Reads a value at a path by navigating to its {@link Target} container and
	 * then into that value with type-aware deep navigation — handles maps, vector
	 * indices (e.g. {@code "w/events/0"}), and Index keys.
	 */
	public ACell read(ACell... jsonKeys) {
		Target target = Target.of(this, jsonKeys);
		if (userNamespaces && jsonKeys.length == 0) {
			AMap<AString, ACell> user = RT.ensureMap(target.root().get());
			if (user == null || !user.containsKey(Namespace.J)) return user;
			return user.assoc(Namespace.J, currentJobs(user.get(Namespace.J)));
		}
		if (userNamespaces && jsonKeys.length > 0 && Namespace.J.equals(jsonKeys[0])) {
			ACell jobs = target.root().get();
			// A single-job read must not scan the user's job index, nor follow prev.
			if (jsonKeys.length > 1) {
				AMap<AString, ACell> record = Job.currentState(RT.ensureMap(navigateInto(jobs, jsonKeys[1])));
				return deepGet(record, jsonKeys, 2);
			}
			return currentJobs(jobs);
		}
		return deepGet(target.root().get(), jsonKeys, target.from());
	}

	@SuppressWarnings("unchecked")
	private static ACell currentJobs(ACell value) {
		return value instanceof Index<?, ?> ? Job.currentStates((Index<Blob, ACell>) value) : value;
	}

	/**
	 * True if the leaf key of {@code keys} is present in its parent under the
	 * cursor's (namespace-unwrapped) value — so a stored null value (key present,
	 * value nil) is distinguished from an absent path. {@code keys.length == 0}
	 * means the whole namespace, present iff its value is non-null.
	 */
	public boolean contains(ACell... keys) {
		Target t = Target.of(this, keys);
		ACell root = t.root().get();
		if (keys.length <= t.from()) return root != null;
		ACell parent = read(Arrays.copyOf(keys, keys.length - 1));
		return containsLeaf(parent, keys[keys.length - 1]);
	}

	/**
	 * Splits a slash-delimited path string into AString segments.
	 *
	 * <p>All segments are kept as AString — no attempt to parse numbers or
	 * guess types. Navigation interprets each segment by the value it meets
	 * ({@link DataComponent}).</p>
	 */
	public static ACell[] parseStringPath(String path) {
		if (path == null || path.isEmpty()) return new ACell[0];
		if (path.startsWith("/")) path = path.substring(1);
		if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
		if (path.isEmpty()) return new ACell[0];

		String[] segments = path.split("/");
		ACell[] keys = new ACell[segments.length];
		for (int i = 0; i < segments.length; i++) {
			keys[i] = Strings.create(segments[i]);
		}
		return keys;
	}

	/**
	 * Builds a sub-path string from parsed path keys (e.g. ["w", "notes"] → "/w/notes").
	 */
	public static String buildSubPath(ACell[] pathKeys) {
		if (pathKeys.length == 0) return "/";
		StringBuilder sb = new StringBuilder();
		for (ACell key : pathKeys) {
			sb.append('/').append(key);
		}
		return sb.toString();
	}

}
