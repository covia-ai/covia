package covia.lattice;

import convex.core.data.ACell;
import covia.exception.AuthException;

/**
 * Where a path ends: the component that owns that position, and the path's
 * keys, the component's own starting at {@code from}.
 *
 * @param component The component that owns the position
 * @param keys The whole path, as the caller named it
 * @param from Index of the component's first key
 */
public record Position(ACoviaComponent<?> component, ACell[] keys, int from) {

	/** The value here as callers see it, or null when absent. */
	public ACell read() {
		return component.read(keys, from);
	}

	/** Whether a value is here; a stored null counts. */
	public boolean contains() {
		return component.contains(keys, from);
	}

	/** The workspace whose data this is, to change it; anything else is read-only. */
	public Workspace workspace() {
		if (component instanceof Workspace area) return area;
		throw new AuthException(ACoviaComponent.READ_ONLY);
	}
}
