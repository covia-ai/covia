package covia.lattice;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;

/**
 * One capability, {@code {with, can, nb?}}: the shape a UCAN attenuation, an
 * agent's or a token's caps and the public scope all share (UCAN.md §3).
 *
 * <p>It knows what a capability is and nothing else. {@code with} names a
 * resource, {@code can} an ability, and {@code nb} holds caveats Covia
 * enforces: {@code gate}, an operation that must succeed for the use, and
 * {@code url}, a prefix the use's destination must fall under. How a bare
 * {@code with} or gate is named in a namespace is {@link CapabilityChecker}'s
 * concern: this component compares a capability with a resource in the form
 * both are already in.</p>
 *
 * <p>It may sit over a stored value or wrap one that is not stored
 * ({@link #of}).</p>
 */
public final class Cap extends ALatticeComponent<AMap<AString, ACell>>
		implements TransactionalComponent<AMap<AString, ACell>, Cap> {

	/** Caveat naming an operation that must succeed for the use. */
	public static final AString GATE = Strings.intern("gate");

	/** Caveat naming a URL prefix the use's destination must fall under. */
	public static final AString URL = Strings.intern("url");

	/** Why a capability is malformed: it is not an object. */
	public static final String NOT_AN_OBJECT = "A capability must be an object with with and can";

	/** Why a capability is malformed: its {@code with} is not a string. */
	public static final String WITH_NOT_STRING = "A capability's with must be a string naming a resource";

	/** Why a capability is malformed: it names no ability. */
	public static final String NO_ABILITY = "A capability's can must name an ability";

	/** Why a capability is malformed: its {@code nb} is not an object. */
	public static final String NB_NOT_AN_OBJECT = "A capability's nb must be an object of caveats";

	/** Why a capability is malformed: a caveat is empty or not a string; the caveat follows. */
	public static final String CAVEAT_NOT_STRING = "A capability's caveat must be a non-empty string: ";

	/** Why a capability is malformed: it carries a caveat Covia does not enforce; the caveat follows. */
	public static final String UNKNOWN_CAVEAT = "Unknown caveat (only gate and url are enforced): ";

	Cap(AForkableCursor<AMap<AString, ACell>> cursor) {
		super(cursor);
	}

	/** A capability value that is not stored, such as one a caller supplied. */
	public static Cap of(AMap<AString, ACell> value) {
		return new Cap(Cursors.create(value));
	}

	@Override
	public Cap rebind(AForkableCursor<AMap<AString, ACell>> cursor) {
		return new Cap(cursor);
	}

	@Override
	public Cap withCursor(AForkableCursor<AMap<AString, ACell>> cursor) {
		return rebind(cursor);
	}

	/** A capability over {@code with} for {@code can}, with no caveats. */
	public static AMap<AString, ACell> create(AString with, AString can) {
		return Capability.create(with, can);
	}

	/** A capability over {@code with} for {@code can}, under caveats {@code nb}. */
	public static AMap<AString, ACell> create(AString with, AString can, AMap<AString, ACell> nb) {
		return (nb == null || nb.isEmpty()) ? Capability.create(with, can) : Capability.create(with, can, nb);
	}

	/** The capability as stored, or null when there is none or it is not an object. */
	public AMap<AString, ACell> map() {
		if (!exists()) return null;
		return RT.ensureMap(value());
	}

	/** The resource it names; null or empty names any resource. */
	public AString with() {
		AMap<AString, ACell> m = map();
		return (m == null) ? null : RT.ensureString(m.get(Capability.WITH));
	}

	/** The ability it grants, or null when it names none. */
	public AString can() {
		AMap<AString, ACell> m = map();
		return (m == null) ? null : RT.ensureString(m.get(Capability.CAN));
	}

	/** Its caveats, empty when it has none. */
	public AMap<AString, ACell> nb() {
		AMap<AString, ACell> m = map();
		AMap<AString, ACell> nb = (m == null) ? null : RT.castMap(m.get(Capability.NB));
		return (nb == null) ? Maps.empty() : nb;
	}

	/** The operation that must succeed for a use, or null when it has no gate caveat. */
	public AString gate() {
		return RT.ensureString(nb().get(GATE));
	}

	/** The URL prefix a use's destination must fall under, or null when it has no url caveat. */
	public AString url() {
		return RT.ensureString(nb().get(URL));
	}

	/**
	 * Null when it is well formed: an object whose {@code can} names an ability,
	 * whose {@code with}, if present, is a string, and whose caveats are ones
	 * Covia enforces, each a non-empty string. Otherwise why not. A malformed
	 * capability grants nothing, so an unknown caveat never becomes an
	 * unconditional grant.
	 */
	public String invalid() {
		AMap<AString, ACell> m = map();
		if (m == null) return NOT_AN_OBJECT;
		ACell with = m.get(Capability.WITH);
		if (with != null && !(with instanceof AString)) return WITH_NOT_STRING;
		AString can = RT.ensureString(m.get(Capability.CAN));
		if (can == null || can.count() == 0) return NO_ABILITY;
		if (!m.containsKey(Capability.NB)) return null;
		AMap<AString, ACell> nb = RT.castMap(m.get(Capability.NB));
		if (nb == null) return NB_NOT_AN_OBJECT;
		for (var entry : nb.entrySet()) {
			AString value = RT.ensureString(entry.getValue());
			if (value == null || value.count() == 0) return CAVEAT_NOT_STRING + entry.getKey();
			if (!GATE.equals(entry.getKey()) && !URL.equals(entry.getKey())) return UNKNOWN_CAVEAT + entry.getKey();
		}
		return null;
	}

	/** Whether it is well formed ({@link #invalid}). */
	public boolean valid() {
		return invalid() == null;
	}

	/** Whether a use must satisfy a caveat for it to grant anything. */
	public boolean conditional() {
		return gate() != null || url() != null;
	}

	/**
	 * Whether it covers {@code ability} on {@code resource}, both in the form
	 * its {@code with} is in, caveats aside. An empty {@code with} covers any
	 * resource, as the venue's own {@code asset/read} grant relies on; this
	 * wildcard holds only in a held scope, never in a UCAN proof. A resource and
	 * an ability match on whole segments (upstream {@link Capability}).
	 */
	public boolean covers(AString resource, AString ability) {
		AString with = with();
		boolean resourceCovered = (with == null || with.count() == 0)
			|| Capability.resourceCovers(with, resource);
		return resourceCovered && Capability.abilityCovers(can(), ability);
	}

	/** Null when its url caveat, if any, covers {@code destination}; otherwise why not. */
	public String urlDenial(AString destination) {
		AString url = url();
		if (url == null) return null;
		if (destination != null && urlCovers(url.toString(), destination.toString())) return null;
		return "url caveat " + url + " does not cover "
			+ (destination != null ? destination : "a use with no destination");
	}

	/**
	 * Whether a {@code nb.url} caveat covers a destination: the same scheme,
	 * host and effective port, and a path equal to the caveat's or below it at
	 * a {@code /} boundary. A caveat path ending in {@code /} covers everything
	 * under it. Both URLs use the outbound normal form, defensively even for
	 * non-HTTP adapters. Query and fragment never matter; ambiguous or invalid
	 * input is not covered.
	 */
	public static boolean urlCovers(String prefix, String destination) {
		try {
			java.net.URI p = covia.utils.HTTPURLs.normalize(prefix);
			java.net.URI d = covia.utils.HTTPURLs.normalize(destination);
			if (p.getScheme() == null || p.getHost() == null || d.getScheme() == null || d.getHost() == null) return false;
			if (!p.getScheme().equalsIgnoreCase(d.getScheme()) || !p.getHost().equalsIgnoreCase(d.getHost())) return false;
			if (effectivePort(p) != effectivePort(d)) return false;
			String pp = (p.getRawPath() == null || p.getRawPath().isEmpty()) ? "/" : p.getRawPath();
			String dp = (d.getRawPath() == null || d.getRawPath().isEmpty()) ? "/" : d.getRawPath();
			if (pp.endsWith("/")) return dp.startsWith(pp) || (dp + "/").equals(pp);
			return dp.equals(pp) || dp.startsWith(pp + "/");
		} catch (covia.exception.InvalidInputException e) {
			return false;
		}
	}

	private static int effectivePort(java.net.URI uri) {
		if (uri.getPort() >= 0) return uri.getPort();
		String scheme = uri.getScheme();
		return "https".equalsIgnoreCase(scheme) ? 443 : "http".equalsIgnoreCase(scheme) ? 80 : -1;
	}

	/**
	 * Both caveat maps together, or null when they ask different things of the
	 * same caveat, so no one capability can carry them: a use satisfying the
	 * result satisfies both.
	 */
	public static AMap<AString, ACell> combinedCaveats(AMap<AString, ACell> a, AMap<AString, ACell> b) {
		AMap<AString, ACell> carried = a;
		for (var entry : b.entrySet()) {
			ACell held = carried.get(entry.getKey());
			if (held != null && !held.equals(entry.getValue())) return null;
			carried = carried.assoc(entry.getKey(), entry.getValue());
		}
		return carried;
	}

	/**
	 * The narrower of two abilities, or null when neither covers the other. Two
	 * abilities covering one use are nested, since both are segment-boundary
	 * prefixes of it, so the narrower covers just what both do.
	 */
	public static AString narrowerAbility(AString a, AString b) {
		if (a == null || b == null) return null;
		if (Capability.abilityCovers(a, b)) return b;
		if (Capability.abilityCovers(b, a)) return a;
		return null;
	}
}
