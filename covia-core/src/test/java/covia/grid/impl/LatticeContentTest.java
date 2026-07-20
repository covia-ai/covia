package covia.grid.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.lattice.cursor.Cursors;

/**
 * {@link LatticeContent} — the {@code AContent} view over content pinned into
 * the lattice {@code :data} region (pinned content-addressable storage:
 * content rides state replication, addressed by hash). Currently library-only,
 * awaiting its storage/client consumer — these tests keep the contract honest
 * in the meantime.
 */
public class LatticeContentTest {

	@Test
	public void testResolvesPinnedContent() throws IOException {
		Blob content = Blob.fromHex("cafebabe");
		Hash hash = content.getHash();
		ACell root = Maps.of(Keywords.DATA, Maps.of(hash, content));

		LatticeContent lc = LatticeContent.of(Cursors.of(root), hash);
		assertEquals(content, lc.getBlob());
		assertEquals(4, lc.getSize());
		assertNotNull(lc.getInputStream());
	}

	@Test
	public void testMissingContentFailsClosed() {
		Hash absent = Strings.create("nope").getHash();
		LatticeContent lc = LatticeContent.of(
			Cursors.of(Maps.of(Keywords.DATA, Maps.empty())), absent);

		assertThrows(IOException.class, lc::getBlob,
			"absent content must surface as IOException, never a null blob");
		assertNull(lc.getInputStream());
		assertEquals(-1, lc.getSize());
	}

	@Test
	public void testNullArgumentsRejected() {
		Hash h = Strings.create("x").getHash();
		assertThrows(IllegalArgumentException.class, () -> LatticeContent.of(null, h));
		assertThrows(IllegalArgumentException.class,
			() -> LatticeContent.of(Cursors.of(Maps.empty()), null));
	}
}
