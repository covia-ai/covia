package covia.grid.impl;

import java.io.IOException;
import java.io.InputStream;

import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.lattice.cursor.ACursor;
import covia.grid.AContent;

/**
 * Content implementation that wraps a lattice data cursor.
 * Content is looked up under the cursor's {@code :data} entry, keyed by the content hash.
 */
public class LatticeContent extends AContent {

	private final ACursor<ACell> cursor;
	private final Hash hash;

	private LatticeContent(ACursor<ACell> cursor, Hash hash) {
		this.cursor = cursor;
		this.hash = hash;
	}

	/**
	 * Create a LatticeContent for the given hash, resolved via the given cursor.
	 * @param cursor Cursor rooted on a lattice node carrying a {@code :data} entry
	 * @param hash   Content hash to look up
	 * @return A new LatticeContent instance
	 */
	public static LatticeContent of(ACursor<ACell> cursor, Hash hash) {
		if (cursor == null) throw new IllegalArgumentException("cursor must not be null");
		if (hash == null) throw new IllegalArgumentException("hash must not be null");
		return new LatticeContent(cursor, hash);
	}

	@Override
	public ABlob getBlob() throws IOException {
		ACell data=cursor.get(Keywords.DATA, hash);
		if (data instanceof ABlob b) {
			return b;
		} else {
			throw new IOException("Content not found in lattice: "+hash);
		}
	}

	@Override
	public InputStream getInputStream() {
		try {
			return getBlob().getInputStream();
		} catch (IOException e) {
			// In this context, not being able to get the Blob implies content not available
			return null;
		}
	}

	@Override
	public long getSize() {
		try {
			return getBlob().count();
		} catch (IOException e) {
			// Can't determine size since not available
			return -1;
		}
	}

}
