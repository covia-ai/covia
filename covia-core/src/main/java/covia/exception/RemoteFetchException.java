package covia.exception;

/**
 * Signals that fetching an asset definition or content from a remote venue
 * failed for an <em>operational</em> reason — the venue was unreachable, it
 * returned an error, the reference was malformed, or the returned metadata
 * failed the content-addressing integrity check.
 *
 * <p>This is deliberately distinct from a genuine <em>absence</em> (the remote
 * venue answered but does not hold the asset / does not bind the name), which
 * the fetch layer reports as {@code null}. Collapsing "could not reach venue X"
 * into "asset not found" hides the real cause — a federated invocation that
 * fails because the upstream venue is down should say so, not claim the
 * operation does not exist (covia#174).</p>
 *
 * <p>The message names the venue and carries the underlying cause; callers on
 * an explicit single-reference path (invoke, {@code asset:get}/{@code pin},
 * agent-definition resolution) let it propagate, while aggregate callers that
 * resolve many references (agent context/tool assembly) may catch it and
 * degrade visibly rather than fail wholesale.</p>
 */
@SuppressWarnings("serial")
public class RemoteFetchException extends CoviaException {

	/** The remote venue reference (DID / connection string), for callers that
	 *  want to build a status without re-parsing the message. May be null. */
	private final String venue;

	/** The asset reference being fetched (hash, name, or DID URL). May be null. */
	private final String ref;

	public RemoteFetchException(String message, String venue, String ref, Throwable cause) {
		super(message, cause);
		this.venue = venue;
		this.ref = ref;
	}

	public String getVenue() { return venue; }
	public String getRef()   { return ref; }

	/** The venue was unreachable or returned an error while fetching {@code ref}. */
	public static RemoteFetchException fetchFailed(String venue, Object ref, Throwable cause) {
		return new RemoteFetchException(
			"Could not fetch " + ref + " from venue " + venue + ": " + causeMessage(cause),
			venue, String.valueOf(ref), cause);
	}

	/** The venue connection string / reference could not be parsed. */
	public static RemoteFetchException malformedVenue(String venue, Throwable cause) {
		return new RemoteFetchException(
			"Malformed venue reference '" + venue + "': " + causeMessage(cause),
			venue, null, cause);
	}

	/** The venue returned metadata that does not hash to the requested id —
	 *  a content-addressing integrity failure (the venue is corrupt or lying). */
	public static RemoteFetchException integrity(String venue, Object ref) {
		return new RemoteFetchException(
			"Venue " + venue + " returned data that does not match the requested id " + ref
			+ " — refusing (content-addressing integrity)",
			venue, String.valueOf(ref), null);
	}

	/** Non-null message even when the cause carries none (e.g. some ConnectExceptions). */
	private static String causeMessage(Throwable cause) {
		if (cause == null) return "unknown error";
		String m = cause.getMessage();
		return (m == null || m.isBlank()) ? cause.getClass().getSimpleName() : m;
	}
}
