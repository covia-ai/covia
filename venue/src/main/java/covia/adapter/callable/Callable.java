package covia.adapter.callable;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.lattice.Caps;
import covia.lattice.Covia;

/**
 * One callable's record (CALLABLES.md): a function its owner publishes for
 * others to call, which runs its operation with its issuer's authority
 * narrowed by its caps, whoever calls it.
 *
 * <pre>
 *   id, issuer, operation, caps?, gate?, description?, created
 * </pre>
 */
public final class Callable extends ALatticeComponent<AMap<AString, ACell>>
		implements TransactionalComponent<AMap<AString, ACell>, Callable> {

	/** The namespace it belongs to. */
	private final AString owner;

	/** Its name, unique within that namespace. */
	private final AString name;

	Callable(AForkableCursor<AMap<AString, ACell>> cursor, AString owner, AString name) {
		super(cursor);
		this.owner = owner;
		this.name = name;
	}

	@Override
	public Callable rebind(AForkableCursor<AMap<AString, ACell>> cursor) {
		return new Callable(cursor, owner, name);
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

	/** The principal that created it, whose authority it acts with. */
	public AString issuer() {
		return RT.ensureString(field(Fields.ISSUER));
	}


	/** The operation it runs, as its provider named it. */
	public AString operation() {
		return RT.ensureString(field(Fields.OPERATION));
	}

	/** The operation that decides who else may call it, as its provider named it; null when only holders of {@code callable/call} may. */
	public AString gate() {
		return RT.ensureString(field(Fields.GATE));
	}

	/** What it is for, or null. */
	public AString description() {
		return RT.ensureString(field(Fields.DESCRIPTION));
	}

	/** Its caps: missing is unrestricted, empty none. */
	public Caps caps() {
		return new Caps(Covia.entry(cursor(), Fields.CAPS));
	}

	/** How a call and the work it leaves running name it: {@code {owner, name, id}}. */
	public AMap<AString, ACell> reference() {
		return reference(owner, name, id());
	}

	/** A callable's reference. */
	static AMap<AString, ACell> reference(AString owner, AString name, AString id) {
		return Maps.of(Fields.OWNER, owner, Fields.NAME, name, Fields.ID, id);
	}
}
