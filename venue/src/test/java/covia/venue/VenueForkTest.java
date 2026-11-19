package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.lattice.cursor.Cursors;
import covia.exception.ConfigurationException;

/**
 * A mounted venue owns its record (GRID_LATTICE_DESIGN.md A.4). Another writer
 * with the venue's key can change the record behind its copy only by a newer
 * signed snapshot. A copy that changed nothing takes it and the venue runs on;
 * a copy that changed too writes its own version, with its own stamp, over it,
 * the fork is logged once at ERROR, and the venue stops. Isolated because it
 * captures the application's logger.
 */
@Isolated
class VenueForkTest {

	private static final AString FIRST = Strings.create("did:test:first");
	private static final AString OUTSIDE = Strings.create("did:test:outside");
	private static final AString OWN = Strings.create("did:test:own");

	/** A venue mounted on {@code time}, having published one change. */
	private record Mounted(AKeyPair key, DrivenClock time, CoviaApplication application, Engine engine) {
		VenueState copy() {
			return application.venue(key.getAccountKey());
		}
	}

	private static Mounted mounted() {
		AKeyPair key = AKeyPair.generate();
		DrivenClock time = DrivenClock.at(Instant.now());
		CoviaApplication application = TestEngine.application(time);
		Engine engine = application.mount(key, new Config(null));
		application.venue(key.getAccountKey()).users().ensure(FIRST);
		application.sync();
		return new Mounted(key, time, application, engine);
	}

	/** Another writer with the venue's key publishes a version a second newer, which reaches the venue's record. */
	private static void deliverOutsideVersion(Mounted venue) {
		CoviaApplication other = TestEngine.application(DrivenClock.at(Instant.ofEpochMilli(venue.time().millis() + 1000)));
		other.mount(venue.key(), new Config(null));
		other.venue(venue.key().getAccountKey()).users().ensure(OUTSIDE);
		other.sync();
		venue.application().merge(other.cursor().get());
	}

	/** The venue's signed record in its application, read through a copy of its own. */
	private static VenueState record(Mounted venue) {
		return VenueState.fromRoot(Cursors.root(venue.application().cursor().get()), venue.key().getAccountKey(),
			venue.key()::signData, venue.application().venueContext());
	}

	@Test
	void anUnchangedVenueTakesAnotherWritersVersionAndRuns() throws Exception {
		Mounted venue = mounted();
		deliverOutsideVersion(venue);

		List<ILoggingEvent> errors = errorsDuring(venue.application()::sync);
		assertNotNull(venue.copy().users().user(OUTSIDE), "the copy takes the other writer's version");
		assertTrue(errors.isEmpty(), errors.toString());
		assertFalse(venue.engine().isClosing(), "the venue runs on");
		venue.engine().close();
	}

	@Test
	void aChangedVenueWritesItsVersionOverAnotherWritersAndStops() throws Exception {
		Mounted venue = mounted();
		deliverOutsideVersion(venue);
		venue.time().advance(2000);
		venue.copy().users().ensure(OWN);

		List<ILoggingEvent> errors = errorsDuring(venue.application()::sync);
		VenueState record = record(venue);
		assertNotNull(record.users().user(OWN), "the venue's version is in its record");
		assertNull(record.users().user(OUTSIDE), "the other writer's version is replaced, not combined");
		assertEquals(1, errors.size(), errors.toString());
		awaitUnmounted(venue);
		assertTrue(venue.engine().isClosing(), "the venue has stopped");
	}

	/** The application logger's ERROR events while {@code body} runs, none of them printed. */
	private static List<ILoggingEvent> errorsDuring(Runnable body) {
		Logger logger = (Logger) LoggerFactory.getLogger(CoviaApplication.class);
		ListAppender<ILoggingEvent> captured = new ListAppender<>();
		Level level = logger.getLevel();
		boolean additive = logger.isAdditive();
		captured.start();
		logger.setLevel(Level.ERROR);
		logger.setAdditive(false);
		logger.addAppender(captured);
		try {
			body.run();
		} finally {
			logger.detachAppender(captured);
			logger.setLevel(level);
			logger.setAdditive(additive);
			captured.stop();
		}
		return List.copyOf(captured.list);
	}

	/** Waits for the venue's stop, which runs on a thread of its own, to unmount it. */
	private static void awaitUnmounted(Mounted venue) throws InterruptedException {
		AccountKey key = venue.key().getAccountKey();
		long deadline = System.nanoTime() + 10_000_000_000L;
		while (System.nanoTime() < deadline) {
			try {
				venue.application().engine(key);
			} catch (ConfigurationException unmounted) {
				return;
			}
			Thread.sleep(10);
		}
		fail("the venue did not stop within 10 seconds");
	}
}
