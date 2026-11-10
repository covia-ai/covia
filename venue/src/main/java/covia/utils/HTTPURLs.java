package covia.utils;

import java.net.URI;
import java.util.Locale;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.URIUtil;

import covia.exception.InvalidInputException;

/** One representation of an outbound HTTP destination, for policy and transport. */
public final class HTTPURLs {

	public static final String INVALID_URL = "URL must be an absolute HTTP(S) URL with a valid host and port";
	public static final String AMBIGUOUS_PATH = "URL path has ambiguous separators, encoding or dot parameters";
	public static final String ABOVE_ROOT = "URL path traverses above its root";
	private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

	private HTTPURLs() { }

	/**
	 * Normalise before any destination check, and send this same URI. Decode
	 * unreserved path characters before removing dot segments; retain reserved
	 * escapes, path parameters, repeated slashes, query and fragment verbatim.
	 * Ambiguous paths are refused rather than interpreted differently by a
	 * downstream HTTP implementation. Diagnostics never include URL credentials.
	 */
	public static URI normalize(String url) {
		try {
			return normalize(URI.create(url));
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new InvalidInputException(INVALID_URL);
		}
	}

	public static URI normalize(URI uri) {
		String scheme = uri.getScheme();
		if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
				|| uri.getHost() == null || uri.getPort() > 65535) {
			throw new InvalidInputException(INVALID_URL);
		}
		String path = uri.getRawPath();
		if (path == null || path.isEmpty()) path = "/";
		try {
			HttpURI parsed = HttpURI.from(uri);
			if (parsed.hasAmbiguousSeparator() || parsed.hasAmbiguousEncoding()
					|| parsed.hasAmbiguousParameter() || uri.getPath().indexOf('\\') >= 0
					|| path.toLowerCase(Locale.ROOT).contains("%3b")) {
				throw new InvalidInputException(AMBIGUOUS_PATH);
			}
			path = URIUtil.normalizePath(URIUtil.decodeSpecific(path, UNRESERVED));
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException(AMBIGUOUS_PATH);
		}
		if (path == null) throw new InvalidInputException(ABOVE_ROOT);
		// Components are already escaped. URI's multi-argument constructor would
		// quote '%' again, changing both the request and the caveat it matches.
		return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + path
			+ (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
			+ (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment()));
	}
}
