package covia.venue.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ABlob;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Strings;
import covia.lattice.CASLattice;
import covia.venue.storage.LatticeStorage;

/**
 * Tests for LatticeStorage - content-addressed storage backed by CASLattice.
 */
public class LatticeStorageTest {

	private LatticeStorage<Hash, AString> storage;

	@BeforeEach
	public void setup() {
		storage = LatticeStorage.create();
	}

	// ========== Basic Operations ==========

	@Test
	public void testCreateEmpty() {
		LatticeStorage<Hash, AString> s = LatticeStorage.create();
		assertNotNull(s);
		assertTrue(s.isEmpty());
		assertEquals(0, s.count());
	}

	@Test
	public void testCreateWithCursor() {
		Hash hash = Hash.fromHex("1111111111111111111111111111111111111111111111111111111111111111");
		Index<Hash, AString> cursor = Index.create(hash, Strings.create("test"));

		LatticeStorage<Hash, AString> s = LatticeStorage.create(cursor);
		assertFalse(s.isEmpty());
		assertEquals(1, s.count());
		assertEquals(Strings.create("test"), s.get(hash));
	}

	@Test
	public void testCreateWithNullCursor() {
		LatticeStorage<Hash, AString> s = LatticeStorage.create(null);
		assertTrue(s.isEmpty());
	}

	// ========== Put and Get ==========

	@Test
	public void testPutAndGet() {
		Hash key = Hash.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		AString value = Strings.create("hello world");

		Hash returned = storage.put(key, value);
		assertSame(key, returned);
		assertEquals(value, storage.get(key));
		assertEquals(1, storage.count());
	}

	@Test
	public void testGetNonExistent() {
		Hash key = Hash.fromHex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		assertNull(storage.get(key));
	}

	@Test
	public void testContainsKey() {
		Hash key = Hash.fromHex("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
		assertFalse(storage.containsKey(key));

		storage.put(key, Strings.create("value"));
		assertTrue(storage.containsKey(key));
	}

	// ========== Content-Addressed Storage ==========

	@Test
	public void testStore() {
		AString content = Strings.create("content-addressed data");
		Hash hash = storage.store(content);

		assertNotNull(hash);
		assertEquals(content.getHash(), hash);
		assertEquals(content, storage.get(hash));
	}

	@Test
	public void testStoreMultiple() {
		AString content1 = Strings.create("first");
		AString content2 = Strings.create("second");
		AString content3 = Strings.create("third");

		Hash h1 = storage.store(content1);
		Hash h2 = storage.store(content2);
		Hash h3 = storage.store(content3);

		assertEquals(3, storage.count());
		assertEquals(content1, storage.get(h1));
		assertEquals(content2, storage.get(h2));
		assertEquals(content3, storage.get(h3));
	}

	@Test
	public void testStoreDuplicate() {
		AString content = Strings.create("duplicate content");

		Hash h1 = storage.store(content);
		Hash h2 = storage.store(content);

		assertEquals(h1, h2, "Same content should have same hash");
		assertEquals(1, storage.count(), "Duplicate content should not increase count");
	}

	@Test
	public void testStoreBlobContent() {
		LatticeStorage<Hash, ABlob> blobStorage = LatticeStorage.create();

		Blob blob = Blob.fromHex("deadbeefcafe");
		Hash hash = blobStorage.storeBlob(blob);

		assertNotNull(hash);
		assertEquals(blob.getContentHash(), hash);
		assertEquals(blob, blobStorage.get(hash));
	}

	// ========== Cursor Operations ==========

	@Test
	public void testGetCursor() {
		storage.store(Strings.create("data1"));
		storage.store(Strings.create("data2"));

		Index<Hash, AString> cursor = storage.getCursor();
		assertNotNull(cursor);
		assertEquals(2, cursor.count());
	}

	@Test
	public void testSetCursor() {
		Hash hash = Hash.fromHex("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
		Index<Hash, AString> newCursor = Index.create(hash, Strings.create("replaced"));

		storage.store(Strings.create("original"));
		assertEquals(1, storage.count());

		storage.setCursor(newCursor);
		assertEquals(1, storage.count());
		assertEquals(Strings.create("replaced"), storage.get(hash));
	}

	@Test
	public void testSetCursorNull() {
		storage.store(Strings.create("data"));
		storage.setCursor(null);
		assertTrue(storage.isEmpty());
	}

	// ========== Merge Operations (CRDT) ==========

	@Test
	public void testMergeWithIndex() {
		Hash h1 = storage.store(Strings.create("local"));

		Hash h2 = Hash.fromHex("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
		Index<Hash, AString> other = Index.create(h2, Strings.create("remote"));

		storage.merge(other);

		assertEquals(2, storage.count());
		assertEquals(Strings.create("local"), storage.get(h1));
		assertEquals(Strings.create("remote"), storage.get(h2));
	}

	@Test
	public void testMergeWithStorage() {
		LatticeStorage<Hash, AString> storage1 = LatticeStorage.create();
		LatticeStorage<Hash, AString> storage2 = LatticeStorage.create();

		Hash h1 = storage1.store(Strings.create("from storage1"));
		Hash h2 = storage2.store(Strings.create("from storage2"));

		storage1.merge(storage2);

		assertEquals(2, storage1.count());
		assertEquals(Strings.create("from storage1"), storage1.get(h1));
		assertEquals(Strings.create("from storage2"), storage1.get(h2));
	}

	@Test
	public void testMergeChaining() {
		LatticeStorage<Hash, AString> s1 = LatticeStorage.create();
		LatticeStorage<Hash, AString> s2 = LatticeStorage.create();
		LatticeStorage<Hash, AString> s3 = LatticeStorage.create();

		s1.store(Strings.create("one"));
		s2.store(Strings.create("two"));
		s3.store(Strings.create("three"));

		// Chained merge
		LatticeStorage<Hash, AString> result = s1.merge(s2).merge(s3);

		assertSame(s1, result, "Merge should return this for chaining");
		assertEquals(3, s1.count());
	}

	@Test
	public void testMergeIdempotent() {
		Hash h = storage.store(Strings.create("data"));
		Index<Hash, AString> before = storage.getCursor();

		storage.merge(before);
		Index<Hash, AString> after = storage.getCursor();

		assertEquals(before, after, "Merging same state should be idempotent");
	}

	@Test
	public void testMergeCommutative() {
		LatticeStorage<Hash, AString> s1 = LatticeStorage.create();
		LatticeStorage<Hash, AString> s2 = LatticeStorage.create();

		s1.store(Strings.create("alpha"));
		s2.store(Strings.create("beta"));

		Index<Hash, AString> cursor1 = s1.getCursor();
		Index<Hash, AString> cursor2 = s2.getCursor();

		LatticeStorage<Hash, AString> merged12 = LatticeStorage.<Hash, AString>create(cursor1).merge(cursor2);
		LatticeStorage<Hash, AString> merged21 = LatticeStorage.<Hash, AString>create(cursor2).merge(cursor1);

		assertEquals(merged12.getCursor(), merged21.getCursor(),
			"Merge should be commutative");
	}

	@Test
	public void testMergeAssociative() {
		LatticeStorage<Hash, AString> s1 = LatticeStorage.create();
		LatticeStorage<Hash, AString> s2 = LatticeStorage.create();
		LatticeStorage<Hash, AString> s3 = LatticeStorage.create();

		s1.store(Strings.create("x"));
		s2.store(Strings.create("y"));
		s3.store(Strings.create("z"));

		Index<Hash, AString> c1 = s1.getCursor();
		Index<Hash, AString> c2 = s2.getCursor();
		Index<Hash, AString> c3 = s3.getCursor();

		// (s1 merge s2) merge s3
		LatticeStorage<Hash, AString> left = LatticeStorage.<Hash, AString>create(c1).merge(c2);
		left.merge(c3);

		// s1 merge (s2 merge s3)
		LatticeStorage<Hash, AString> right23 = LatticeStorage.<Hash, AString>create(c2).merge(c3);
		LatticeStorage<Hash, AString> right = LatticeStorage.<Hash, AString>create(c1).merge(right23.getCursor());

		assertEquals(left.getCursor(), right.getCursor(),
			"Merge should be associative");
	}

	// ========== Snapshot ==========

	@Test
	public void testSnapshot() {
		storage.store(Strings.create("original"));

		LatticeStorage<Hash, AString> snapshot = storage.snapshot();

		assertNotSame(storage, snapshot);
		assertEquals(storage.getCursor(), snapshot.getCursor());
	}

	@Test
	public void testSnapshotIndependence() {
		storage.store(Strings.create("before"));

		LatticeStorage<Hash, AString> snapshot = storage.snapshot();

		storage.store(Strings.create("after"));

		assertEquals(1, snapshot.count(), "Snapshot should not be affected by later changes");
		assertEquals(2, storage.count());
	}

	// ========== Clear ==========

	@Test
	public void testClear() {
		storage.store(Strings.create("data1"));
		storage.store(Strings.create("data2"));
		assertEquals(2, storage.count());

		storage.clear();

		assertTrue(storage.isEmpty());
		assertEquals(0, storage.count());
	}

	// ========== Misc ==========

	@Test
	public void testGetLattice() {
		assertNotNull(storage.getLattice());
		assertTrue(storage.getLattice() instanceof CASLattice);
	}

	@Test
	public void testToString() {
		String str = storage.toString();
		assertTrue(str.contains("0 entries"));

		storage.store(Strings.create("test"));
		str = storage.toString();
		assertTrue(str.contains("1 entries"));
	}

	// ========== Real-world Scenarios ==========

	@Test
	public void testDistributedStorageScenario() {
		// Simulate two nodes storing data independently
		LatticeStorage<Hash, AString> node1 = LatticeStorage.create();
		LatticeStorage<Hash, AString> node2 = LatticeStorage.create();

		// Node 1 stores some data
		Hash h1 = node1.store(Strings.create("document from node 1"));
		Hash h2 = node1.store(Strings.create("another doc from node 1"));

		// Node 2 stores different data
		Hash h3 = node2.store(Strings.create("node 2's document"));

		// They sync - each merges the other's state
		node1.merge(node2);
		node2.merge(node1);

		// Both should now have all 3 documents
		assertEquals(3, node1.count());
		assertEquals(3, node2.count());
		assertEquals(node1.getCursor(), node2.getCursor());

		// All original hashes should still work
		assertNotNull(node1.get(h1));
		assertNotNull(node1.get(h2));
		assertNotNull(node1.get(h3));
		assertNotNull(node2.get(h1));
		assertNotNull(node2.get(h2));
		assertNotNull(node2.get(h3));
	}

	@Test
	public void testContentDeduplication() {
		LatticeStorage<Hash, AString> s1 = LatticeStorage.create();
		LatticeStorage<Hash, AString> s2 = LatticeStorage.create();

		// Both nodes store the same content
		AString sharedContent = Strings.create("shared document");
		Hash h1 = s1.store(sharedContent);
		Hash h2 = s2.store(sharedContent);

		assertEquals(h1, h2, "Same content should have same hash");

		// Each also stores unique content
		s1.store(Strings.create("unique to s1"));
		s2.store(Strings.create("unique to s2"));

		// After merge, the shared content is not duplicated
		s1.merge(s2);
		assertEquals(3, s1.count(), "Shared content should be deduplicated");
	}

	@Test
	public void testBlobStorage() {
		LatticeStorage<Hash, ABlob> blobStore = LatticeStorage.create();

		// Store some binary blobs
		Blob img1 = Blob.fromHex("89504e470d0a1a0a"); // PNG header
		Blob img2 = Blob.fromHex("ffd8ffe000104a46"); // JPEG header

		Hash h1 = blobStore.storeBlob(img1);
		Hash h2 = blobStore.storeBlob(img2);

		assertEquals(2, blobStore.count());
		assertEquals(img1, blobStore.get(h1));
		assertEquals(img2, blobStore.get(h2));
	}
}
