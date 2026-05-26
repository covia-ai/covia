package covia.venue;

import java.io.IOException;

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
 * <p>Two operations:</p>
 * <ul>
 *   <li>{@link #persist(ACell)} pushes the supplied lattice value through the
 *       propagator's synchronous persist path (typically
 *       {@code NodeServer.persistSnapshot}). The store sees the new root data;
 *       the OS-level write may still be in the page cache.</li>
 *   <li>{@link #flush()} forces the underlying store to fsync, so the bytes
 *       already published via {@code persist} are durable on disk independent
 *       of OS writeback timing. Engine calls this periodically via the
 *       persistence sweep and on every {@link Engine#flush()}.</li>
 * </ul>
 *
 * <p>Engine is intentionally agnostic about WHERE persistence happens —
 * the callback is wired by whoever constructs the Engine (typically
 * {@code VenueServer}, which calls {@code NodeServer.persistSnapshot} and
 * {@code EtchStore.flush()}). Engine has no compile-time dependency on
 * convex-peer, NodeServer, or EtchStore. Tests and in-memory venues pass a
 * no-op handler.</p>
 *
 * <p>See {@code venue/docs/PERSISTENCE.md} for the architectural rationale.</p>
 */
public interface PersistenceHandler {

	/**
	 * Synchronously persist the given lattice value through the propagator's
	 * persist path. Returns when the store has accepted the new root data;
	 * the bytes may still be in the OS page cache. Pair with {@link #flush()}
	 * if a durability barrier on disk is required.
	 *
	 * @param value the lattice root value to persist (typically {@code engine.getRootCursor().get()})
	 */
	void persist(ACell value);

	/**
	 * Forces the underlying store to fsync. After {@code flush()} returns,
	 * everything previously {@link #persist(ACell)}-ed is durable on disk —
	 * a hard process kill or power loss will not lose those writes (subject
	 * to the OS / hardware honouring fsync).
	 *
	 * <p>Default no-op for handlers that have no fsync notion (in-memory
	 * stores, tests).</p>
	 *
	 * @throws IOException if the underlying fsync fails
	 */
	default void flush() throws IOException { /* no-op */ }

	/** No-op handler — used by in-memory engines and tests that don't care about persistence. */
	PersistenceHandler NOOP = new PersistenceHandler() {
		@Override public void persist(ACell value) { /* no-op */ }
	};
}
