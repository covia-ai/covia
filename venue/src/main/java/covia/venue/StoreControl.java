package covia.venue;

import java.io.IOException;

/**
 * Host seam for store maintenance (covia#452). The host —
 * {@link covia.venue.server.VenueServer} — owns the store; the Engine only
 * relays requests from venue-owned operations, exactly as {@link VenueProcess}
 * does for process restart. Embedders that adopt a caller-opened store may
 * install their own implementation.
 *
 * <p>Online collection drives Convex's own cycle ({@code startGC → transferGC
 * → verifyGC → completeGC}; {@code convex-core/docs/ETCH_GC.md}) while the
 * venue keeps serving: writes redirect to the target file from the start of
 * the cycle and reads fall back to the old file. After cutover the old
 * {@code EtchStore} handle stays a fully functional view — reads across both
 * files, writes routed to the successor's file — so the venue keeps using it
 * and no in-memory reference has to be rebound; the successor is retained only
 * to be cleanly closed at shutdown. The superseded file is deleted when the old
 * handle closes and the collected file is adopted under the store's name on the
 * next start.</p>
 */
public interface StoreControl {

	/** Where the store's collection stands. */
	record Status(String file, long bytes, boolean inProgress, boolean sweepComplete,
			boolean completed, String collectedFile, long collectedBytes) {}

	/** Outcome of a completed online cycle. */
	record Result(long bytesBefore, long bytesAfter, long elapsedMillis, String file,
			String collectedFile) {}

	/**
	 * Reports the store's collection state.
	 *
	 * @throws IllegalStateException when the store is not a persistent file
	 */
	Status status() throws IOException;

	/**
	 * Runs one full online cycle on the calling thread and cuts over.
	 *
	 * @throws IllegalStateException when the store is not a persistent file, a
	 *         cycle is already running, a cycle already completed in this
	 *         process (restart to collect again), or free disk is short of the
	 *         current file size
	 * @throws IOException on a storage failure; the cycle is cancelled and the
	 *         original file remains authoritative
	 */
	Result collect() throws IOException;

	/** Cancels a running cycle, rolling the store back to its original file; a no-op when none is running. */
	void cancel() throws IOException;
}
