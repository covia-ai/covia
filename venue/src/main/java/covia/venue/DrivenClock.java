package covia.venue;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongUnaryOperator;

import convex.core.data.prim.CVMLong;
import covia.exception.InvalidInputException;

/**
 * A clock its holder moves, for an embedder or test running a venue on a time
 * of its own choosing (EMBEDDING.md, Time). It reads the time it was last moved
 * to, and moves forward or by zero, never back.
 *
 * <p>The clock only holds the time. Moving it tells nothing else: a venue acts
 * on the new time when its application is next updated
 * ({@link CoviaApplication#update()}).</p>
 */
public final class DrivenClock {

	/** Refusal of a step backwards, given the time now and the time asked for, in epoch milliseconds. */
	public static final String BACKWARDS = "A driven clock moves forward only: it reads %d and cannot go to %d";

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
	 * Moves the clock to the given time.
	 *
	 * @param millis The new time in epoch milliseconds, no earlier than the time it reads
	 * @throws InvalidInputException If the time is earlier ({@link #BACKWARDS})
	 */
	public void set(long millis) {
		move(now -> millis);
	}

	/**
	 * Moves the clock on.
	 *
	 * @param ms Milliseconds to move it by, which may be zero
	 * @return The new time in epoch milliseconds
	 * @throws InvalidInputException If the step is negative ({@link #BACKWARDS})
	 */
	public long advance(long ms) {
		return move(now -> now + ms);
	}

	/**
	 * The policy to give {@code CoviaApplication.create}, {@code open} or
	 * {@code connect}, whose write clock is this clock.
	 */
	public VenueContext policy() {
		return policy;
	}

	private long move(LongUnaryOperator step) {
		return time.updateAndGet(now -> {
			long next = step.applyAsLong(now);
			if (next < now) throw new InvalidInputException(BACKWARDS.formatted(now, next));
			return next;
		});
	}
}
