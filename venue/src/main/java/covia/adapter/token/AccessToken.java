package covia.adapter.token;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.TransactionalComponent;
import convex.lattice.cursor.AForkableCursor;
import covia.api.Fields;
import covia.lattice.Caps;
import covia.lattice.Covia;

/**
 * One access token's record (ACCESS_TOKENS.md). Its core is read by the
 * venue's token handling: the issuer whose authority it acts with, the caps
 * narrowing that authority, its expiry and how its bearer is recognised. Its
 * {@code access} holds one entry per access point that accepts it, each read
 * only by that access point, through a component of its own
 * ({@link #access(AString, Function)}).
 *
 * <pre>
 *   id, issuer, source?, caps?, expires?, created, hash, sealed?,
 *   access: { &lt;access point&gt;: entry }
 * </pre>
 */
public final class AccessToken extends ALatticeComponent<AMap<AString, ACell>>
		implements TransactionalComponent<AMap<AString, ACell>, AccessToken> {

	/** The namespace it belongs to. */
	private final AString owner;

	/** Its name, unique within that namespace. */
	private final AString name;

	AccessToken(AForkableCursor<AMap<AString, ACell>> cursor, AString owner, AString name) {
		super(cursor);
		this.owner = owner;
		this.name = name;
	}

	@Override
	public AccessToken rebind(AForkableCursor<AMap<AString, ACell>> cursor) {
		return new AccessToken(cursor, owner, name);
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

	/** The identity that created it, whose authority it narrows. */
	public AString issuer() {
		return RT.ensureString(field(Fields.ISSUER));
	}

	/** The token whose request created it, which narrows it too; null when none did. */
	public AMap<AString, ACell> source() {
		return RT.ensureMap(field(Fields.SOURCE));
	}

	/** Its caps: missing is unrestricted, empty none. */
	public Caps caps() {
		return new Caps(Covia.entry(cursor(), Fields.CAPS));
	}

	/** When it expires, in milliseconds since the epoch; null for never. */
	public Long expires() {
		return (field(Fields.EXPIRES) instanceof CVMLong at) ? at.longValue() : null;
	}

	/** Whether it has expired at {@code now}. */
	public boolean expired(long now) {
		Long at = expires();
		return at != null && now >= at;
	}

	/** The hex SHA3-256 hash of its bearer. */
	public AString hash() {
		return RT.ensureString(field(AccessTokens.HASH));
	}

	/** Its bearer sealed ({@link covia.venue.SecretStore#seal}), only while the venue keeps bearers; otherwise null. */
	public ACell sealedBearer() {
		return field(AccessTokens.SEALED);
	}

	/** Its entries by access point, as stored; empty when it has none. */
	public AMap<AString, ACell> access() {
		AMap<AString, ACell> access = RT.ensureMap(field(Fields.ACCESS));
		return (access == null) ? Maps.empty() : access;
	}

	/** The access points that accept it, in stored order. */
	public Set<AString> accessPoints() {
		return accessPoints(access());
	}

	/** The access points an {@code access} map names. */
	public static Set<AString> accessPoints(AMap<AString, ACell> access) {
		Set<AString> points = new LinkedHashSet<>();
		if (access != null) for (var entry : access.entrySet()) points.add(entry.getKey());
		return points;
	}

	/** Whether access point {@code point} accepts it. */
	public boolean accepts(AString point) {
		return RT.ensureMap(access().get(point)) != null;
	}

	/**
	 * Its entry for access point {@code point}, bound to the component that
	 * access point reads it with; null when that access point does not accept it.
	 */
	public <C> C access(AString point, Function<AForkableCursor<AMap<AString, ACell>>, C> component) {
		return accepts(point) ? component.apply(cursor().path(Fields.ACCESS, point)) : null;
	}

	/** How work it narrowed names it: {@code {owner, name, id}}. */
	public AMap<AString, ACell> reference() {
		return reference(owner, name, id());
	}

	/** A token's reference. */
	static AMap<AString, ACell> reference(AString owner, AString name, AString id) {
		return Maps.of(Fields.OWNER, owner, Fields.NAME, name, Fields.ID, id);
	}
}
