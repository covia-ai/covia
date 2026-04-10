package covia.venue;

import convex.core.data.ACell;

/**
 * Synchronous persistence callback handed to {@link Engine} at construction.
 *
 * <p>The handler is invoked by {@link Engine#flush()} (and by the close-time
 * final flush) to make the venue's current lattice value durable on the
 * caller's thread, bypassing any background propagator queue. It is the
 * primitive that lets {@code engine.flush()} guarantee "the write set is on
 * disk before this returns".</p>
 *
 * <p>Engine is intentionally agnostic about WHERE persistence happens —
 * the callback is wired by whoever constructs the Engine (typically
 * {@code VenueServer}, which calls {@code NodeServer.persistSnapshot}).
 * Engine has no compile-time dependency on convex-peer, NodeServer, or
 * EtchStore. Tests and in-memory venues pass a no-op handler.</p>
 *
 * <p>See {@code venue/docs/PERSISTENCE.md} for the architectural rationale.</p>
 */
@FunctionalInterface
public interface PersistenceHandler {

	/**
	 * Synchronously persist the given lattice value to durable storage.
	 * Returns when the value is on disk.
	 *
	 * @param value the lattice root value to persist (typically {@code engine.getRootCursor().get()})
	 */
	void persist(ACell value);

	/** No-op handler — used by in-memory engines and tests that don't care about persistence. */
	PersistenceHandler NOOP = value -> { /* no-op */ };
}
