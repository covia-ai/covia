package covia.exception;

/**
 * Thrown when a scope-bound virtual namespace prefix is used in a request
 * context that does not provide its required scope — e.g. {@code n/} (agent
 * workspace) with no agentId, or {@code c/} (session) with no agentId/sessionId.
 *
 * <p>This is a <b>context mismatch</b>, deliberately distinct from its
 * neighbours so a caller (and the resolution code) can react to it precisely:</p>
 * <ul>
 *   <li>{@link AuthException} — a permission/authentication failure. A wrong
 *       scope is <em>not</em> a denial: the caller may be fully authenticated,
 *       the prefix simply has no meaning without agent/session context.</li>
 *   <li>{@link IllegalArgumentException} / format errors — the path is
 *       well-formed; it just names a namespace that does not apply here.</li>
 * </ul>
 *
 * <p><b>Resolution semantics.</b> On a <b>read</b>, this is the one resolver
 * condition that resolves to a genuine <em>absence</em> (there is no such path in
 * this context). On a <b>write</b> it propagates as a clear error. Every other
 * exception during navigation — auth, malformed input, a lower-level store fault,
 * or an abnormal bug — propagates rather than being masked as "not found" (#175).</p>
 */
@SuppressWarnings("serial")
public class WrongScopeException extends CoviaException {

	public WrongScopeException(String message) {
		super(message);
	}
}
