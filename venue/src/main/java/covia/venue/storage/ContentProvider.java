package covia.venue.storage;

import java.io.IOException;
import java.io.InputStream;

import convex.core.data.AString;
import covia.grid.AContent;
import covia.venue.RequestContext;

/**
 * An alternative content storage mechanism addressable by reference — the seam
 * that makes non-CAS storage (e.g. DLFS drives) first-class in the asset
 * content machinery rather than a per-consumer special case.
 *
 * <p>Adapters that store content implement this; {@link covia.venue.Engine}
 * consults registered providers in {@code resolveContent}/{@code putContent}
 * before (get) or instead of (put) the content-addressed store. A provider
 * recognises its own reference shapes (e.g. {@code dlfs/<drive>/<path>}) and
 * returns null / false for anything else, so providers compose by namespace.</p>
 *
 * <p><b>Access control is the provider's responsibility</b> — each enforces the
 * same checks its own operations enforce (grant scope, cross-user proofs), and
 * throws on denial rather than degrading.</p>
 */
public interface ContentProvider {

	/** Resolved content: the data plus its declared content type (may be null
	 *  when the mechanism stores no type — callers may sniff). */
	record Resolved(AContent content, String contentType) {}

	/**
	 * Resolves a reference to content, or returns null when the reference is
	 * not this provider's shape. Throws on denial or a recognised-but-missing
	 * target (never degrades to null for those).
	 */
	Resolved getContent(AString ref, RequestContext ctx) throws IOException;

	/**
	 * Stores content at a reference, or returns false when the reference is not
	 * this provider's shape. Throws on denial.
	 */
	default boolean putContent(AString ref, InputStream data, String contentType,
			RequestContext ctx) throws IOException {
		return false;
	}
}
