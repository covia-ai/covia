package covia.venue.server;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import covia.venue.TrustedProxies;
import io.javalin.http.Context;

/**
 * Request ids and the access log (covia#538, #547).
 *
 * <p>Every request gets an id, returned in {@value #HEADER} so a client can
 * quote it, and held in the SLF4J MDC under {@value #MDC_KEY} for the
 * request's own thread so any line logged while handling it carries it. An
 * inbound id is honoured only from a trusted proxy and only when it is a
 * short, plain token — a client must not be able to write text into logs.
 * Work handed on to a job runs on other threads and does not inherit it.</p>
 *
 * <p>The access log, one line per request on the logger named
 * {@value #LOGGER}, is off unless the venue sets {@code logging.access: true}.
 * It records method, path (never the query string), status, duration, caller,
 * client address and a truncated user agent — never headers, credentials or
 * bodies.</p>
 */
public final class RequestLog {

	public static final String LOGGER = "ACCESS";
	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "requestId";

	private static final String ATTR = "covia.requestId";
	private static final int MAX_USER_AGENT = 120;
	private static final Logger access = LoggerFactory.getLogger(LOGGER);

	private final TrustedProxies proxies;
	private final String venue;

	RequestLog(TrustedProxies proxies, String venue) {
		this.proxies = (proxies != null) ? proxies : TrustedProxies.NONE;
		this.venue = venue;
	}

	/** Before any handler: assign the request its id, echo it, and put it in the MDC. */
	void begin(Context ctx) {
		String id = null;
		String inbound = ctx.header(HEADER);
		if (inbound != null && isPlainId(inbound) && proxies.trusts(ctx.ip())) id = inbound;
		if (id == null) id = UUID.randomUUID().toString();
		ctx.attribute(ATTR, id);
		ctx.header(HEADER, id);
		MDC.put(MDC_KEY, id);
	}

	/** After the response: one access line when enabled, then clear the MDC. */
	void end(Context ctx, Float millis, boolean logAccess) {
		try {
			if (logAccess) {
				String ua = ctx.userAgent();
				if (ua != null && ua.length() > MAX_USER_AGENT) ua = ua.substring(0, MAX_USER_AGENT);
				Object caller = AuthMiddleware.getVenueUserDID(ctx);
				// Typed first: a bare generic ctx.attribute() would bind to the
				// Supplier overload of addKeyValue and fail at runtime.
				String id = ctx.attribute(ATTR);
				access.atInfo()
					.addKeyValue("venue", venue)
					.addKeyValue(MDC_KEY, id)
					.addKeyValue("method", ctx.method().toString())
					.addKeyValue("path", ctx.path())
					.addKeyValue("status", ctx.statusCode())
					.addKeyValue("ms", (millis != null) ? Math.round(millis) : null)
					.addKeyValue("did", caller)
					.addKeyValue("ip", proxies.clientIp(ctx.ip(), ctx.header("X-Forwarded-For")))
					.addKeyValue("ua", ua)
					.log("request");
			}
		} catch (RuntimeException e) {
			// Logging must never change a response: a failure here is reported, not thrown.
			LoggerFactory.getLogger(RequestLog.class).warn("Access log line failed: {}", e.toString());
		} finally {
			MDC.remove(MDC_KEY);
		}
	}

	/** An id worth carrying into logs: 1–64 characters of letters, digits, '.', '_' or '-'. */
	static boolean isPlainId(String id) {
		return id.length() <= 64 && id.matches("[A-Za-z0-9._-]+");
	}
}
