package covia.venue;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import convex.core.data.prim.CVMLong;

/**
 * A clock its holder moves, for an embedder or test running a venue on a time
 * of its own choosing (EMBEDDING.md, Time). It reads the time it was last moved
 * to, and moves either way, so a test can also reproduce a clock stepping back.
 *
 * <p>The clock only holds the time. Moving it tells nothing else: a venue acts
 * on the new time when its application is next updated
 * ({@link CoviaApplication#update()}).</p>
 */
public final class DrivenClock {

	private final AtomicLong time;
	private final VenueContext policy;

	private DrivenClock(long start) {
		this.time = new AtomicLong(start);
		this.policy = VenueContext.create(() -> CVMLong.create(time.get()));
	}

	/**
	 * Creates a clock reading the given instant.
	 *
	 * @param start The time it reads until moved
	 * @return New clock
	 */
	public static DrivenClock at(Instant start) {
		return new DrivenClock(Objects.requireNonNull(start, "start").toEpochMilli());
	}

	/** The time this clock reads, in epoch milliseconds. */
	public long millis() {
		return time.get();
	}

	/**
	 * Moves the clock to the given time, which may be earlier than the time it reads.
	 *
	 * @param millis The new time in epoch milliseconds
	 */
	public void set(long millis) {
		time.set(millis);
	}

	/**
	 * Moves the clock by the given step.
	 *
	 * @param ms Milliseconds to move it by: zero stands still, negative steps back
	 * @return The new time in epoch milliseconds
	 */
	public long advance(long ms) {
		return time.addAndGet(ms);
	}

	/** The application policy whose write clock is this clock. */
	VenueContext policy() {
		return policy;
	}
}
