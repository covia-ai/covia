package covia.lattice;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import covia.exception.InvalidInputException;

/**
 * A set of capabilities bounding what its holder may do: an agent's
 * {@code config.caps}, an MCP token's caps, the public scope. Each entry is a
 * {@link Cap}.
 *
 * <p>Storage convention: no value means unrestricted, no limits at all; an
 * empty vector means no capabilities. Outside the store, unrestricted is said
 * {@code "unrestricted"} ({@link #parse}). A stored value that is not a vector
 * grants nothing.</p>
 *
 * <p>It knows what a set of capabilities is and nothing else. Naming a bare
 * resource in a namespace, running gates and composing authority belong to
 * {@link CapabilityChecker} and the venue; domain components such as an agent
 * or a token wrap this one. It may sit over a stored value or wrap one that
 * is not stored ({@link #of}).</p>
 */
public final class Caps extends ALatticeComponent<AVector<ACell>>
		implements TransactionalComponent<AVector<ACell>, Caps> {

	/** How unrestricted is said outside the store. */
	public static final AString UNRESTRICTED = Strings.intern("unrestricted");

	/** Why caps from outside are refused: they are neither {@code "unrestricted"} nor a list. */
	public static final String NOT_A_LIST = "caps must be \"unrestricted\" or an array of capabilities";

	/** Why caps from outside are refused: an entry is malformed; its index and why follow. */
	public static final String MALFORMED_ENTRY = "caps[%d] is malformed: %s";

	Caps(AForkableCursor<AVector<ACell>> cursor) {
		super(cursor);
	}

	/** Caps that are not stored, such as a scope computed for one check; null is unrestricted. */
	public static Caps of(AVector<ACell> value) {
		return new Caps(Cursors.create(value));
	}

	@Override
	public Caps rebind(AForkableCursor<AVector<ACell>> cursor) {
		return new Caps(cursor);
	}

	@Override
	public Caps withCursor(AForkableCursor<AVector<ACell>> cursor) {
		return rebind(cursor);
	}

	/**
	 * Caps as they arrive from outside (an operation's input, the venue's
	 * config), in their stored form: {@code "unrestricted"} is null, and a list
	 * is returned as it is once every entry is well formed ({@link Cap#invalid}).
	 *
	 * @throws InvalidInputException naming the first malformed entry and why,
	 *         or when {@code input} is neither
	 */
	public static AVector<ACell> parse(ACell input) {
		if (UNRESTRICTED.equals(input)) return null;
		AVector<ACell> caps = RT.ensureVector(input);
		if (caps == null) throw new InvalidInputException(NOT_A_LIST);
		for (long i = 0; i < caps.count(); i++) {
			String why = Cap.of(RT.ensureMap(caps.get(i))).invalid();
			if (why != null) throw new InvalidInputException(MALFORMED_ENTRY.formatted(i, why));
		}
		return caps;
	}

	/**
	 * The caps as a scope: null for unrestricted, otherwise the vector of
	 * capabilities, empty for none. A stored value that is not a vector is
	 * read as none.
	 */
	public AVector<ACell> scope() {
		if (!exists()) return null;
		ACell raw = ((AForkableCursor<?>) cursor()).get();
		if (raw == null) return null;
		AVector<ACell> caps = RT.ensureVector(raw);
		return (caps == null) ? Vectors.empty() : caps;
	}

	/** Whether there are no limits at all. */
	public boolean isUnrestricted() {
		return scope() == null;
	}

	/** How many capabilities are listed: none when unrestricted or empty. */
	public long count() {
		AVector<ACell> caps = scope();
		return (caps == null) ? 0 : caps.count();
	}

	/** The capability at {@code index}. */
	public Cap cap(long index) {
		return new Cap(cursor().path(CVMLong.create(index)));
	}

	/** Replaces the caps with {@code caps} in their stored form: null for unrestricted. */
	public void set(AVector<ACell> caps) {
		cursor().set(caps);
	}

	/** Leaves no capabilities: nothing is allowed. */
	public void clear() {
		cursor().set(Vectors.empty());
	}

	/**
	 * Whether a well-formed capability with no caveats covers {@code ability} on
	 * {@code resource}, given in the form the capabilities are in. Unrestricted
	 * caps cover everything; empty caps cover nothing.
	 */
	public boolean covers(AString resource, AString ability) {
		AVector<ACell> caps = scope();
		if (caps == null) return true;
		for (long i = 0; i < caps.count(); i++) {
			Cap cap = Cap.of(RT.ensureMap(caps.get(i)));
			if (cap.valid() && !cap.conditional() && cap.covers(resource, ability)) return true;
		}
		return false;
	}

	/**
	 * The well-formed capabilities with caveats that cover {@code ability} on
	 * {@code resource}, in their order, for a use to satisfy one of. Empty for
	 * unrestricted caps, which need no caveat.
	 */
	public List<Cap> conditionallyCovering(AString resource, AString ability) {
		List<Cap> covering = new ArrayList<>();
		AVector<ACell> caps = scope();
		if (caps == null) return covering;
		for (long i = 0; i < caps.count(); i++) {
			Cap cap = Cap.of(RT.ensureMap(caps.get(i)));
			if (cap.valid() && cap.conditional() && cap.covers(resource, ability)) covering.add(cap);
		}
		return covering;
	}
}
