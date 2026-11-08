package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Hashing;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.lang.RT;
import convex.lattice.cursor.Root;
import covia.grid.impl.BlobContent;
import covia.lattice.Covia;
import covia.venue.storage.ContentStorage;
import covia.test.Rendezvous;

/**
 * The venue's content store: content kept by its SHA-256, refused unless it
 * matches the hash it is offered under, inside the venue's record.
 */
public class ContentStoreTest {

	private final ContentStore store = new ContentStore(Root.create(Index.<ABlob, ABlob>none()));

	private static ByteArrayInputStream stream(byte[] data) {
		return new ByteArrayInputStream(data);
	}

	@Test
	public void testStoreAndRead() throws IOException {
		byte[] data = "hello world".getBytes();
		Hash hash = Hashing.sha256(data);
		assertFalse(store.exists(hash));

		store.store(hash, stream(data));

		assertTrue(store.exists(hash));
		assertEquals(Blob.wrap(data), store.getContent(hash).getBlob().toFlatBlob());
		assertEquals(data.length, store.getSize(hash));
	}

	@Test
	public void testEveryStoreRefusesContentNotMatchingItsHash() throws IOException {
		byte[] data = "the real content".getBytes();
		Hash hash = Hashing.sha256(data);
		byte[] impostor = "something else".getBytes();

		for (ThrowingStore attempt : new ThrowingStore[] {
				() -> store.store(hash, stream(impostor)),
				() -> store.store(hash, Blob.wrap(impostor)),
				() -> store.store(hash, BlobContent.of(Blob.wrap(impostor))),
				() -> store.storeVerified(hash, stream(impostor))}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, attempt::run);
			assertTrue(e.getMessage().startsWith(ContentStorage.HASH_MISMATCH), e.getMessage());
			assertFalse(store.exists(hash), "refused content is never stored");
		}

		store.storeVerified(hash, stream(data));
		assertEquals(Blob.wrap(data), store.getContent(hash).getBlob().toFlatBlob());
		assertThrows(IllegalArgumentException.class, () -> store.store(hash, stream(impostor)));
		assertEquals(Blob.wrap(data), store.getContent(hash).getBlob().toFlatBlob(),
			"a refused store leaves what was stored under the hash");
	}

	private interface ThrowingStore {
		void run() throws IOException;
	}

	@Test
	public void testMissingContent() {
		Hash hash = Hash.fromHex("1111111111111111111111111111111111111111111111111111111111111111");
		assertNull(store.getContent(hash));
		assertFalse(store.delete(hash));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.getSize(hash));
		assertTrue(e.getMessage().startsWith(ContentStore.NOT_STORED), e.getMessage());
	}

	@Test
	public void testDelete() throws IOException {
		byte[] data = "delete me".getBytes();
		Hash hash = Hashing.sha256(data);
		store.store(hash, stream(data));

		assertTrue(store.delete(hash));
		assertFalse(store.exists(hash));
	}

	@Test
	public void testConcurrentDeleteHasExactlyOneWinner() throws Exception {
		byte[] data = "delete once".getBytes();
		Hash hash = Hashing.sha256(data);
		store.store(hash, stream(data));
		ArrayList<Callable<Boolean>> deletes = new ArrayList<>();
		for (int i = 0; i < 8; i++) deletes.add(() -> store.delete(hash));
		long winners = Rendezvous.all(deletes).stream().filter(Boolean::booleanValue).count();
		assertEquals(1, winners);
		assertFalse(store.exists(hash));
	}

	@Test
	public void testRefusesMissingArguments() {
		Hash hash = Hash.fromHex("5555555555555555555555555555555555555555555555555555555555555555");
		assertThrows(IllegalArgumentException.class, () -> store.store(null, stream(new byte[0])));
		assertThrows(IllegalArgumentException.class, () -> store.store(hash, (InputStream) null));
		assertThrows(IllegalArgumentException.class, () -> store.store(hash, (ABlob) null));
		assertThrows(IllegalArgumentException.class, () -> store.getContent(null));
		assertThrows(IllegalArgumentException.class, () -> store.delete(null));
		assertFalse(store.exists(null));
	}

	@Test
	public void testVenueContentLivesInTheVenueRecord() throws IOException {
		VenueState venue = VenueState.create(AKeyPair.generate());
		byte[] data = "venue content".getBytes();
		Hash hash = Hashing.sha256(data);

		venue.contents().store(hash, stream(data));

		assertEquals(Blob.wrap(data), RT.getIn(venue.get(), Covia.STORAGE, hash));
		assertTrue(venue.contents().exists(hash), "a new view reads the same content");
	}

	@Test
	public void testContentStoredInADraftLandsOnlyWithTheTransaction() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		byte[] data = "drafted".getBytes();
		Hash hash = Hashing.sha256(data);

		assertThrows(IllegalStateException.class, () -> venue.execute(draft -> {
			draft.contents().store(hash, Blob.wrap(data));
			assertTrue(draft.contents().exists(hash), "the draft sees its own change");
			throw new IllegalStateException("abandon the transaction");
		}));
		assertFalse(venue.contents().exists(hash), "an abandoned transaction stores nothing");

		venue.execute(draft -> draft.contents().store(hash, Blob.wrap(data)));
		assertTrue(venue.contents().exists(hash));
	}
}
