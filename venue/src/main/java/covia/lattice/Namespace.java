package covia.lattice;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Namespace constants for user-level lattice keys.
 *
 * <p>Each constant is an interned AString matching the single-letter
 * namespace prefix from the Grid addressing scheme (§4.1 of
 * GRID_LATTICE_DESIGN.md). Using AString keys keeps the user lattice
 * JSON-compliant throughout.</p>
 *
 * <p>{@link convex.lattice.generic.KeyedLattice} uses blob-based
 * comparison, so these AString keys resolve to the same child lattice
 * as the corresponding Keyword (e.g. {@code "j"} and {@code :j} are
 * equivalent for path navigation).</p>
 */
public final class Namespace {

	/** Jobs namespace — user's job references (/j/) */
	public static final AString J = Strings.intern("j");

	/** Agents namespace — user's agents (/g/) */
	public static final AString G = Strings.intern("g");

	/** Secrets namespace — user's encrypted credentials (/s/) */
	public static final AString S = Strings.intern("s");

	/** Assets namespace — user-created assets (/a/) — future */
	public static final AString A = Strings.intern("a");

	/** Workspace namespace — user scratch space (/w/) — future */
	public static final AString W = Strings.intern("w");

	/** Operations namespace — user's named operations (/o/) — future */
	public static final AString O = Strings.intern("o");

	private Namespace() {}
}
