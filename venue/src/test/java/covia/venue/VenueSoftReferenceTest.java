package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.store.AStore;
import convex.etch.EtchStore;

/**
 * What the host has persisted does not stay in memory: a venue's own copy of its
 * state reaches those cells through soft references, so a long-running venue's
 * heap follows what it is working on, not everything it has ever written.
 */
public class VenueSoftReferenceTest {

	/** Too long to be embedded in its parent, so it is a branch of the tree. */
	private static final String LARGE = "x".repeat(2000);

	private static AString valueOf(String name) {
		return Strings.create(name + LARGE);
	}

	private static void update(User user, String name) {
		AString key = Strings.create(name);
		user.cursor().updateAndGet(current -> {
			AMap<AString, ACell> record = RT.ensureMap(current);
			return record.assoc(key, valueOf(name));
		});
	}

	/** Collects the non-embedded cells reachable through strong references alone, loading nothing. */
	private static void collect(ACell cell, Set<ACell> visited, Set<ACell> strong) {
		if (cell == null || !visited.add(cell)) return;
		for (int i = 0; i < Cells.refCount(cell); i++) {
			Ref<ACell> ref = Cells.getRef(cell, i);
			if (!ref.isDirect()) continue;
			ACell child = ref.getValue();
			if (child != null && !ref.isEmbedded()) strong.add(child);
			collect(child, visited, strong);
		}
	}

	private static Set<ACell> strongCells(VenueState venue) {
		Set<ACell> strong = Collections.newSetFromMap(new IdentityHashMap<>());
		collect(venue.cursor().get(), Collections.newSetFromMap(new IdentityHashMap<>()), strong);
		return strong;
	}

	private static boolean holdsStrongly(VenueState venue, ACell value) {
		return strongCells(venue).stream().anyMatch(value::equals);
	}

	/** Counts the cells the copy reaches only softly that the store cannot give back, loading nothing. */
	private static int softCellsMissingFromStore(VenueState venue, AStore store) {
		int missing = 0;
		Set<ACell> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<ACell> pending = new ArrayDeque<>();
		pending.add(venue.cursor().get());
		while (!pending.isEmpty()) {
			ACell cell = pending.pop();
			if (!visited.add(cell)) continue;
			for (int i = 0; i < Cells.refCount(cell); i++) {
				Ref<ACell> ref = Cells.getRef(cell, i);
				if (ref.isDirect()) {
					ACell child = ref.getValue();
					if (child != null) pending.add(child);
				} else if (store.refForHash(ref.getHash()) == null) {
					missing++;
				}
			}
		}
		return missing;
	}

	@Test
	public void aVenueHoldsWhatTheHostPersistedSoftly() throws IOException {
		EtchStore store = TestTemp.etch("venue-soft-references");
		CoviaApplication application = TestEngine.application(store);
		try {
			AKeyPair key = AKeyPair.generate();
			Engine engine = application.mount(key, new Config(null));
			VenueState venue = application.venue(key.getAccountKey());
			User user = venue.users().ensure(Strings.create("did:test:soft-references"));

			update(user, "first");
			Blob upload = Blob.wrap(LARGE.repeat(8).getBytes(StandardCharsets.UTF_8));
			Hash uploadHash = Hashing.sha256(upload);
			venue.contents().store(uploadHash, upload);
			assertTrue(holdsStrongly(venue, valueOf("first")), "a write stays in memory until it is persisted");
			engine.flush();
			// Only writes not yet synced may be strong, and here there are none.
			assertEquals(0, strongCells(venue).size(), "everything persisted is held softly");

			update(user, "second");
			engine.flush();
			assertFalse(holdsStrongly(venue, valueOf("first")), "an atomic update stays soft whatever is written after it");
			assertFalse(holdsStrongly(venue, upload), "as does an upload");

			ACell record = user.cursor().get();
			assertEquals(valueOf("first"), RT.getIn(record, "first"));
			assertEquals(valueOf("second"), RT.getIn(record, "second"));
			assertEquals(upload, venue.contents().getContent(uploadHash).getBlob());
		} finally {
			application.close();
			application.host().close();
			store.close();
		}
	}

	@Test
	public void aWriteDuringTheSaveLeavesWhatWasSavedSoft() throws IOException {
		EtchStore store = TestTemp.etch("venue-soft-references-during-save");
		CoviaApplication application = TestEngine.application(store);
		try {
			AKeyPair key = AKeyPair.generate();
			Engine engine = application.mount(key, new Config(null));
			VenueState venue = application.venue(key.getAccountKey());
			User user = venue.users().ensure(Strings.create("did:test:write-during-save"));

			update(user, "first");
			// The host hands each snapshot it has saved to its publisher, so a write
			// made there lands after the save and before publication ends.
			Function<Index<Keyword, ACell>, Index<Keyword, ACell>> writeDuringSave = snapshot -> {
				update(user, "during");
				return snapshot;
			};
			application.host().attachPublisher(writeDuringSave);
			try {
				engine.flush();
			} finally {
				application.host().detachPublisher(writeDuringSave);
			}
			assertFalse(holdsStrongly(venue, valueOf("first")),
				"what the host saved is held softly, though a write landed during the save");
			assertEquals(0, softCellsMissingFromStore(venue, store), "all the copy holds softly is in the store");

			engine.flush();
			assertEquals(0, strongCells(venue).size(), "the write made during the save is saved by the next");
			assertEquals(0, softCellsMissingFromStore(venue, store), "and stays in the store");
			ACell record = user.cursor().get();
			assertEquals(valueOf("first"), RT.getIn(record, "first"));
			assertEquals(valueOf("during"), RT.getIn(record, "during"));
		} finally {
			application.close();
			application.host().close();
			store.close();
		}
	}
}
