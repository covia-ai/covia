package covia.lattice;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Namespace constants for user-level lattice keys.
 *
 * <p>Each constant is an interned AString matching the single-letter
 * namespace prefix from the Grid addressing scheme (§4.1 of
 * GRID_LATTICE_DESIGN.md): the key of that namespace in a user's record. What
 * a namespace holds is up to the component that owns it; workspaces and
 * adapter views take any lattice data.</p>
 */
public final class Namespace {

	/** Jobs namespace — user's job references (/j/) */
	public static final AString J = Strings.intern("j");

	/** Agents namespace — user's agents (/g/) */
	public static final AString G = Strings.intern("g");

	/** Secrets namespace — user's encrypted credentials (/s/) */
	public static final AString S = Strings.intern("s");

	/** Assets namespace — user-created assets (/a/) */
	public static final AString A = Strings.intern("a");

	/** Workspace namespace — general-purpose user data (/w/) */
	public static final AString W = Strings.intern("w");

	/** Operations namespace — user's named operations (/o/) */
	public static final AString O = Strings.intern("o");

	/** HITL namespace — human-in-the-loop requests (/h/) */
	public static final AString H = Strings.intern("h");

	/** Adapter views — {@code x/<adapter>}, each adapter's view of the user's data, held in adapter state */
	public static final AString X = Strings.intern("x");

	private Namespace() {}
}
