package covia.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AString;
import covia.exception.AuthException;

/**
 * Where a Covia path ends: the component that owns that position, and the
 * range of the path's segments that the component reads, {@code path[from, to)}.
 *
 * @param component The component that owns the position
 * @param path The whole path, as the caller named it
 * @param from Index of the component's first segment
 * @param to Index after the component's last segment
 */
public record Position(ACoviaComponent<?> component, AString[] path, int from, int to) {

	public Position {
		Objects.checkFromToIndex(from, to, path.length);
	}

	/** The position at {@code path[from..]}, to the end of the path. */
	public Position(ACoviaComponent<?> component, AString[] path, int from) {
		this(component, path, from, path.length);
	}

	/** The value here as callers see it, or null when absent. */
	public ACell read() {
		return component.read(path, from, to);
	}

	/** Whether a value is here; a stored null counts. */
	public boolean contains() {
		return component.contains(path, from, to);
	}

	/** The workspace whose data this is, to change it; anything else is read-only. */
	public Workspace workspace() {
		if (component instanceof Workspace area) return area;
		throw new AuthException(ACoviaComponent.READ_ONLY);
	}
}
