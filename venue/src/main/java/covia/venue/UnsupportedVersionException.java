package covia.venue;

/**
 * Stored state at a schema version newer than this build supports. Nothing has
 * been written when it is thrown: a newer format is never executed. The
 * version is either the venue's own {@code :version} or one adapter's state
 * version; {@link #adapter()} says which.
 */
public final class UnsupportedVersionException extends IllegalStateException {
	private static final long serialVersionUID = 1L;

	private final String adapter;
	private final long stored;
	private final long supported;

	UnsupportedVersionException(String message, String adapter, long stored, long supported) {
		super(message);
		this.adapter = adapter;
		this.stored = stored;
		this.supported = supported;
	}

	/** The adapter whose state version this is, or null for the venue's own version. */
	public String adapter() { return adapter; }

	/** The version recorded in the stored state. */
	public long stored() { return stored; }

	/** The newest version this build reads and writes. */
	public long supported() { return supported; }
}
