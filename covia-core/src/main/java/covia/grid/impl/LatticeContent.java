package covia.grid.impl;

import java.io.IOException;
import java.io.InputStream;

import convex.core.cvm.Keywords;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.lattice.ACursor;
import covia.grid.AContent;

/**
 * Content implementation that wraps a lattice data cursor
 */
public class LatticeContent extends AContent {

	private ACursor<ACell> cursor;
	private Hash hash;
	
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
