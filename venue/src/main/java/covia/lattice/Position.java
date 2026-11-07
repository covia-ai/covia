package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.Path;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;

/**
 * Where a Covia path ends: the component that owns that position, and the
 * stretch of the path that the component reads below itself. In a workspace
 * the component is the {@link Entry} the path names, and the stretch is empty.
 *
 * <p>A path that names no place data could be, such as a name a component
 * does not have or a key in the wrong form, ends at an invalid position: it
 * has no component, and reading, testing or changing it is refused with
 * {@link ACoviaComponent#NO_SUCH_PATH}. A valid position may hold nothing; it
 * {@link #exists} only while a value is there, which can change as the data
 * it is bound to does, also within a transaction.</p>
 *
 * <p>Reading or changing a position directly checks nothing: that is for venue
 * code, and a request goes through an {@link Access}, which asks the data's
 * {@link Owner} first.</p>
 *
 * @param component The component that owns the position, or null when the path is invalid
 * @param path The stretch of the caller's path the component reads; it formats as the whole address
 */
public record Position(ACoviaComponent<?> component, Path path) {

	/**
	 * The position for a path that names no place data could be.
	 *
	 * @param path The stretch of the path from the first segment that names
	 *        nothing, so a refusal can say where the path stops naming a place
	 */
	public static Position invalid(Path path) {
		return new Position(null, path);
	}

	/**
	 * Why reading or changing this position is refused as no such path, naming
	 * where the path stops naming a place; null when it is valid. Validity
	 * follows the path's shape, never the data.
	 */
	public String reasonInvalid() {
		if (component != null) return null;
		return String.format(ACoviaComponent.NO_SUCH_PATH, CoviaPath.address(path),
			CoviaPath.address(path.slice(0, Math.min(1, path.count()))));
	}

	/** Whether the path names a place that could hold data, whether or not anything is there. */
	public boolean isValid() {
		return component != null;
	}

	/** The value here as callers see it, or null when absent. */
	public ACell read() {
		return valid().read(path);
	}

	/** Whether a value is here now; a stored null counts. */
	public boolean exists() {
		return valid().contains(path);
	}

	/** The workspace entry here, to change it; anything else is read-only. */
	public Entry entry() {
		if (valid() instanceof Entry entry && path.isEmpty()) return entry;
		throw new AuthException(ACoviaComponent.READ_ONLY);
	}

	private ACoviaComponent<?> valid() {
		if (component == null) throw new InvalidInputException(reasonInvalid());
		return component;
	}
}
