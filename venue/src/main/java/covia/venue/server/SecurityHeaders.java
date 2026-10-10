package covia.venue.server;

import covia.venue.Config;
import io.javalin.http.Context;

/**
 * The browser-hardening headers the venue puts on its own responses, and only
 * on those that need them (#537, #569).
 *
 * <p>Every header here is a default: a handler that has already set it keeps
 * its value, so a page that states a stricter policy, or one that must be
 * framed, is never overridden. Nothing is applied globally. An embedder's
 * routes are never touched; an embedder that wants these defaults on a page
 * of its own calls {@link #page} or {@link #content} itself. HSTS is
 * deliberately absent, since only the TLS terminator knows the origin is
 * https. {@code securityHeaders: false} turns them all off.</p>
 */
public final class SecurityHeaders {
	private SecurityHeaders() {}

	/**
	 * The venue's sign-in page ({@code /login}): the one page the venue renders
	 * where a click starts something on the user's behalf, and whose URL may
	 * carry a return path.
	 */
	public static void page(Context ctx, Config config) {
		if (config == null || !config.isSecurityHeaders()) return;
		// No framing by another site: an invisible frame could steer a sign-in
		// click (UI redress). X-Frame-Options for older browsers, frame-ancestors
		// for current ones; a page that must be framed sets its own and keeps it.
		setIfAbsent(ctx, "X-Frame-Options", "DENY");
		setIfAbsent(ctx, "Content-Security-Policy", "frame-ancestors 'none'");
		// No Referer on the way out: the page's URL may carry the return path
		// the login should end at, which no linked site needs to see.
		setIfAbsent(ctx, "Referrer-Policy", "no-referrer");
	}

	/**
	 * Stored bytes served from the venue's own origin: the content and asset
	 * endpoints.
	 */
	public static void content(Context ctx, Config config) {
		if (config == null || !config.isSecurityHeaders()) return;
		// The bytes are someone else's, served as this origin. Without nosniff a
		// browser may decide a blob that looks like HTML or script is one and run
		// it with the venue's origin: stored XSS through the content endpoint.
		setIfAbsent(ctx, "X-Content-Type-Options", "nosniff");
	}

	/**
	 * The floor an embedder may want on every one of its own responses, from an
	 * after-handler of its own: the venue never applies this globally.
	 */
	public static void response(Context ctx, Config config) {
		if (config == null || !config.isSecurityHeaders()) return;
		// No sniffing anywhere and no Referer anywhere: cheap on an API, and a
		// product that serves pages and files from many routes may prefer one
		// line over naming each route.
		setIfAbsent(ctx, "X-Content-Type-Options", "nosniff");
		setIfAbsent(ctx, "Referrer-Policy", "no-referrer");
	}

	static void setIfAbsent(Context ctx, String name, String value) {
		if (ctx.res().getHeader(name) == null) ctx.header(name, value);
	}
}
