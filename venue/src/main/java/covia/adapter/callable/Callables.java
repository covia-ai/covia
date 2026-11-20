package covia.adapter.callable;

import java.security.SecureRandom;
import java.util.function.UnaryOperator;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blobs;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;

/**
 * The callables a venue holds, as the callable adapter's state
 * (CALLABLES.md):
 *
 * <pre>
 *   callables: { &lt;owner DID&gt;: { &lt;name&gt;: record } }
 * </pre>
 *
 * <p>Each change is one transaction. It knows the records, nothing of who may
 * change or call them.</p>
 */
public final class Callables extends ALatticeComponent<ACell>
		implements TransactionalComponent<ACell, Callables> {

	static final AString CALLABLES = Strings.intern("callables");

	private static final SecureRandom RANDOM = new SecureRandom();

	Callables(AForkableCursor<ACell> cursor) {
		super(cursor);
	}

	@Override
	public Callables rebind(AForkableCursor<ACell> cursor) {
		return new Callables(cursor);
	}

	/** A new callable id. */
	static AString newId() {
		return Strings.create(Blobs.createRandom(RANDOM, 16).toHexString());
	}

	private AMap<AString, ACell> root() {
		return map(cursor().getOrDefault(null));
	}

	private static AMap<AString, ACell> map(ACell cell) {
		AMap<AString, ACell> map = RT.ensureMap(cell);
		return (map == null) ? Maps.empty() : map;
	}

	/** The callable {@code name} in {@code owner}'s namespace, which need not exist. */
	public Callable callable(AString owner, AString name) {
		return new Callable(cursor().path(CALLABLES, owner, name), owner, name);
	}

	/** Every callable in {@code owner}'s namespace, by name; empty when there are none. */
	public AMap<AString, ACell> list(AString owner) {
		return list(root(), owner);
	}

	/**
	 * Adds a callable's record.
	 *
	 * @return whether it was added: false when the name is taken
	 */
	public boolean create(AString owner, AString name, AMap<AString, ACell> record) {
		return transact(draft -> {
			AMap<AString, ACell> root = draft.root();
			AMap<AString, ACell> all = map(root.get(CALLABLES));
			AMap<AString, ACell> mine = map(all.get(owner));
			if (mine.containsKey(name)) return false;
			draft.cursor().set(root.assoc(CALLABLES, all.assoc(owner, mine.assoc(name, record))));
			return true;
		});
	}

	/**
	 * Changes a callable's record.
	 *
	 * @param change the record as it should be, from the record as it stands
	 * @return the changed record, or null when there is no such callable
	 */
	public AMap<AString, ACell> update(AString owner, AString name, UnaryOperator<AMap<AString, ACell>> change) {
		return transact(draft -> {
			AMap<AString, ACell> root = draft.root();
			AMap<AString, ACell> all = map(root.get(CALLABLES));
			AMap<AString, ACell> mine = map(all.get(owner));
			AMap<AString, ACell> record = RT.ensureMap(mine.get(name));
			if (record == null) return null;
			AMap<AString, ACell> changed = change.apply(record);
			draft.cursor().set(root.assoc(CALLABLES, all.assoc(owner, mine.assoc(name, changed))));
			return changed;
		});
	}

	/**
	 * Removes a callable.
	 *
	 * @return the record removed, or null when there was none
	 */
	public AMap<AString, ACell> delete(AString owner, AString name) {
		return transact(draft -> {
			AMap<AString, ACell> root = draft.root();
			AMap<AString, ACell> record = RT.ensureMap(list(root, owner).get(name));
			if (record == null) return null;
			draft.cursor().set(without(root, owner, name));
			return record;
		});
	}

	/**
	 * Removes every callable in {@code owner}'s namespace, as its account goes.
	 * One cursor update, so it may run on the draft of a larger transaction.
	 */
	public void deleteOwner(AString owner) {
		cursor().updateAndGet(value -> {
			AMap<AString, ACell> root = map(value);
			AMap<AString, ACell> all = map(root.get(CALLABLES));
			return all.containsKey(owner) ? root.assoc(CALLABLES, all.dissoc(owner)) : value;
		});
	}

	/**
	 * Removes every callable {@code issuer} created in {@code owner}'s
	 * namespace, as that agent goes. One cursor update, as {@link #deleteOwner}.
	 */
	public void deleteIssuer(AString owner, AString issuer) {
		cursor().updateAndGet(value -> {
			AMap<AString, ACell> root = map(value);
			AMap<AString, ACell> before = root;
			for (var entry : list(root, owner).entrySet()) {
				AMap<AString, ACell> record = RT.ensureMap(entry.getValue());
				if (record != null && issuer.equals(record.get(Fields.ISSUER))) root = without(root, owner, entry.getKey());
			}
			return (root == before) ? value : root;
		});
	}

	/** The callable a reference names, while that record stands: one of that name and id; otherwise null. */
	public Callable resolve(AMap<AString, ACell> reference) {
		if (reference == null) return null;
		AString owner = RT.ensureString(reference.get(Fields.OWNER));
		AString name = RT.ensureString(reference.get(Fields.NAME));
		AString id = RT.ensureString(reference.get(Fields.ID));
		if (owner == null || name == null || id == null) return null;
		Callable callable = callable(owner, name);
		return id.equals(callable.id()) ? callable : null;
	}

	private static AMap<AString, ACell> list(AMap<AString, ACell> root, AString owner) {
		return map(map(root.get(CALLABLES)).get(owner));
	}

	private static AMap<AString, ACell> without(AMap<AString, ACell> root, AString owner, AString name) {
		AMap<AString, ACell> all = map(root.get(CALLABLES));
		AMap<AString, ACell> mine = map(all.get(owner)).dissoc(name);
		return root.assoc(CALLABLES, mine.isEmpty() ? all.dissoc(owner) : all.assoc(owner, mine));
	}
}
