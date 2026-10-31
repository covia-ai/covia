package covia.exception;

/**
 * A named thing does not exist. The message says what kind of thing was
 * sought; the reference is kept for callers that act on it.
 */
@SuppressWarnings("serial")
public class NotFoundException extends CoviaException {

	private final String ref;

	public NotFoundException(String message, String ref) {
		super(message);
		this.ref = ref;
	}

	/** The reference that named nothing, or null. */
	public String getRef() {
		return ref;
	}
}
