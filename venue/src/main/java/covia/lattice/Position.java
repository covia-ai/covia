package covia.lattice;

import convex.core.data.ACell;
import covia.exception.AuthException;

/**
 * Where a Covia path ends: the component that owns that position, and the
 * stretch of the path that the component reads below itself.
 *
 * @param component The component that owns the position
 * @param path The stretch of the caller's path the component reads; it formats as the whole address
 */
public record Position(ACoviaComponent<?> component, CoviaPath path) {

	/** The value here as callers see it, or null when absent. */
	public ACell read() {
		return component.read(path);
	}

	/** Whether a value is here; a stored null counts. */
	public boolean contains() {
		return component.contains(path);
	}

	/** The workspace whose data this is, to change it; anything else is read-only. */
	public Workspace workspace() {
		if (component instanceof Workspace area) return area;
		throw new AuthException(ACoviaComponent.READ_ONLY);
	}
}
