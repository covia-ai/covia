package covia.venue;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.lang.RT;

/**
 * Ids as the venue stores them. A job, task or session id is a {@link Blob}
 * in every record the venue writes; JSON shows it as {@code "0x…"}. Text is
 * read as an id only where it arrives from outside ({@link Blob#parse}), and
 * the venue migration converted every id it had stored as text, so a stored
 * field is read as a Blob or not at all.
 */
public final class Ids {
	private Ids() {}

	/**
	 * The id a stored field holds.
	 *
	 * @param cell The field's value, may be null
	 * @return The id, or null when the field holds none
	 */
	public static Blob stored(ACell cell) {
		ABlob blob = RT.ensureBlob(cell);
		return (blob == null) ? null : blob.toFlatBlob();
	}
}
