package covia.venue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The venue's security audit trail (covia#538): one INFO line per
 * security-relevant event on the logger named {@value #LOGGER}, so an operator
 * can route and retain it apart from operational logs.
 *
 * <p>Off unless the venue's config sets {@code logging.audit: true}. With it
 * off nothing is emitted and the call sites cost a boolean check. Events are
 * key-value pairs ({@code event}, {@code venue}, then the event's own keys):
 * the text pattern renders them through {@code %kvp}, the JSON profile as
 * fields.</p>
 *
 * <p>What an event may carry is deliberately minimal, because audit lines are
 * kept far longer than operational ones: DIDs, a client address, names,
 * outcomes and refusal reasons. Never a token, a secret value, an email or
 * any content. Refusal reasons are safe by construction — they describe the
 * presented credential only, never its bytes (covia#548).</p>
 */
public final class Audit {

	/** The logger name operators route on. */
	public static final String LOGGER = "AUDIT";

	// Event names: the audit vocabulary. Tests assert against these.
	public static final String AUTH_SUCCESS = "auth.success";
	public static final String AUTH_FAILURE = "auth.failure";
	public static final String AUTH_THROTTLED = "auth.throttled";
	public static final String LOGIN = "login";
	public static final String TOKEN_ISSUED = "token.issued";
	public static final String USER_CREATE = "user.create";
	public static final String USER_DELETE = "user.delete";
	public static final String USER_SUDO = "user.sudo";
	public static final String KEY_ADD = "auth.key.add";
	public static final String KEY_REVOKE = "auth.key.revoke";
	public static final String SECRET_WRITE = "secret.write";
	public static final String VENUE_GC = "venue.gc";
	public static final String VENUE_RESTART = "venue.restart";
	public static final String VENUE_ADMIN = "venue.admin";

	// Keys events carry.
	public static final String K_EVENT = "event";
	public static final String K_VENUE = "venue";
	public static final String K_DID = "did";
	public static final String K_IDENTITY = "identity";
	public static final String K_IP = "ip";
	public static final String K_REASON = "reason";
	public static final String K_OUTCOME = "outcome";
	public static final String K_TYPE = "type";
	public static final String K_SUBJECT = "subject";
	public static final String K_EXP = "exp";
	public static final String K_TARGET = "target";
	public static final String K_NAME = "name";
	public static final String K_OPERATION = "operation";
	/** Same key the request log puts in the MDC. */
	public static final String K_REQUEST_ID = "requestId";

	public static final String OK = "ok";
	public static final String FAILED = "failed";

	private static final Logger log = LoggerFactory.getLogger(LOGGER);

	/** An audit trail that records nothing. */
	public static final Audit OFF = new Audit(false, null);

	private final boolean enabled;
	private final String venue;

	Audit(boolean enabled, String venue) {
		this.enabled = enabled;
		this.venue = venue;
	}

	/** Whether this venue records audit events. */
	public boolean enabled() {
		return enabled;
	}

	/**
	 * Record one event. {@code keyValues} alternate key and value; a null value
	 * is omitted, so call sites need not branch on optional fields.
	 */
	public void event(String event, Object... keyValues) {
		if (!enabled) return;
		LoggingEventBuilder b = log.atInfo()
			.addKeyValue(K_EVENT, event)
			.addKeyValue(K_VENUE, venue);
		// On a request thread, the id the client was given (covia#547).
		String requestId = MDC.get(K_REQUEST_ID);
		if (requestId != null) b = b.addKeyValue(K_REQUEST_ID, requestId);
		for (int i = 0; i + 1 < keyValues.length; i += 2) {
			Object value = keyValues[i + 1];
			if (value != null) b = b.addKeyValue(String.valueOf(keyValues[i]), String.valueOf(value));
		}
		b.log(event);
	}
}
