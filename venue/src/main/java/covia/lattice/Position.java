package covia.lattice;

import java.util.Objects;

import convex.core.data.ACell;
import covia.exception.AuthException;

/**
 * Where a path ends: the component that owns that position, and the range of
 * the path's keys that the component reads, {@code keys[from, to)}.
 *
 * @param component The component that owns the position
 * @param keys The whole path, as the caller named it
 * @param from Index of the component's first key
 * @param to Index after the component's last key
 */
public record Position(ACoviaComponent<?> component, ACell[] keys, int from, int to) {

	public Position {
		Objects.checkFromToIndex(from, to, keys.length);
	}

	/** The position at {@code keys[from..]}, to the end of the path. */
	public Position(ACoviaComponent<?> component, ACell[] keys, int from) {
		this(component, keys, from, keys.length);
	}

	/** The value here as callers see it, or null when absent. */
	public ACell read() {
		return component.read(keys, from, to);
	}

	/** Whether a value is here; a stored null counts. */
	public boolean contains() {
		return component.contains(keys, from, to);
	}

	/** The workspace whose data this is, to change it; anything else is read-only. */
	public Workspace workspace() {
		if (component instanceof Workspace area) return area;
		throw new AuthException(ACoviaComponent.READ_ONLY);
	}
}
