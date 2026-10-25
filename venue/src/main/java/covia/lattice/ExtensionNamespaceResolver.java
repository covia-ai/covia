package covia.lattice;

import java.util.Arrays;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ATransformCursor;
import convex.lattice.cursor.Cursors;
import covia.adapter.AAdapter;
import covia.adapter.CoviaAdapter;
import covia.exception.AuthException;
import covia.venue.RequestContext;

/** Routes x/adapterName through the active adapter's explicit, user-scoped view. */
public final class ExtensionNamespaceResolver implements NamespaceResolver {
	public static final String AUTHENTICATION_REQUIRED = "Extension paths require an authenticated user";
	public static final String INVALID_PATH = "Invalid extension path segment";
	public static final String READ_ONLY = "Extension path is unavailable or read-only";
	public static final String UNAVAILABLE = "Extension adapter is no longer active";

	@Override public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		return resolve(ctx, adapter, keys, Capability.CRUD_READ);
	}

	@Override public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys, AString ability) {
		return resolveForUser(ctx, adapter, ctx == null ? null : ctx.getUserDID(), keys, ability);
	}

	/** Original authority is checked against the addressed owner; hooks receive that user's context. */
	public static ResolvedNamespace resolveForUser(RequestContext ctx, CoviaAdapter covia,
			AString owner, ACell[] keys, AString ability) {
		if (ctx == null || ctx.getCallerDID() == null || owner == null) throw new AuthException(AUTHENTICATION_REQUIRED);
		for (ACell key : keys) {
			if (key instanceof CVMLong number && number.longValue() >= 0) continue;
			if (!(key instanceof AString s) || s.isEmpty() || s.toString().contains("/")
					|| s.toString().contains("\\") || s.toString().equals(".") || s.toString().equals("..")
					|| s.toString().chars().anyMatch(Character::isISOControl)) {
				throw new IllegalArgumentException(INVALID_PATH);
			}
		}
		AString resource = Strings.create(owner + WorkspaceRoot.buildSubPath(keys));
		covia.engine.requireLocalAccess(ctx, resource, ability);
		String name = keys.length < 2 ? null : keys[1].toString();
		AAdapter adapter = name == null ? null : covia.engine.getAdapter(name);
		RequestContext scoped = owner.equals(ctx.getUserDID()) ? ctx : ctx.onBehalfOf(owner);
		ACell[] path = keys.length < 2 ? new ACell[0] : Arrays.copyOfRange(keys, 2, keys.length);
		boolean mutation = Capability.CRUD_WRITE.equals(ability) || Capability.CRUD_DELETE.equals(ability);
		if (mutation) requireWritable(adapter, scoped, path);
		AForkableCursor<ACell> exposed = adapter == null ? null : adapter.latticeView(scoped);
		if (mutation && exposed == null) throw new AuthException(READ_ONLY);
		// An absent route is still resolved: never fall through to a physical user x slot.
		AForkableCursor<ACell> base = exposed == null ? Cursors.root(null) : exposed;
		AForkableCursor<ACell> guarded = new ATransformCursor<ACell, ACell>(base) {
			private void check() {
				if (name != null && covia.engine.getAdapter(name) != adapter) throw new AuthException(UNAVAILABLE);
				covia.engine.requireLocalAccess(ctx, resource, ability);
				if (mutation) requireWritable(adapter, scoped, path);
			}
			@Override protected ACell view(ACell value) { check(); return value; }
			@Override protected ACell write(ACell current, ACell value) {
				if (!mutation) throw new AuthException(READ_ONLY);
				check();
				return value;
			}
		};
		return new ResolvedNamespace(new WorkspaceRoot(guarded, covia.engine.getLatticeContext(), false), path, false);
	}

	private static void requireWritable(AAdapter adapter, RequestContext ctx, ACell[] path) {
		String denial = adapter == null ? READ_ONLY : adapter.latticeWriteDenial(ctx, path.clone());
		if (denial != null) throw new AuthException(denial);
	}

	/** Individual adapters decide which paths accept mutations during resolution. */
	@Override public boolean isWritable() { return true; }
}
