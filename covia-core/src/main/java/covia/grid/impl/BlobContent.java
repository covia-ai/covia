package covia.grid.impl;

import java.io.IOException;
import java.io.InputStream;

import convex.core.data.ABlob;
import convex.core.data.Blobs;
import covia.grid.AContent;

/**
 * Simple implementation of AContent that wraps a Blob.
 */
public class BlobContent extends AContent {
    
    private final ABlob blob;
    
    /**
     * Create a BlobContent with the given blob 
     * @param blob The blob containing the content
     */
    private BlobContent(ABlob blob) {
        this.blob = blob;
    }
    
    /**
     * Create a BlobContent from an InputStream.
     * @param inputStream The input stream to read content from
     * @return A new BlobContent instance
     * @throws IOException if reading from the stream fails
     */
    public static BlobContent from(InputStream inputStream) throws IOException {
        ABlob blob = Blobs.fromStream(inputStream);
        return new BlobContent(blob);
    }
    
    @Override
    public ABlob getBlob() {
        return blob;
    }
    
    @Override
    public InputStream getInputStream() {
        return blob.getInputStream();
    }
    
    @Override
    public long getSize() {
        return blob != null ? blob.count() : -1;
    }

	public static BlobContent of(ABlob data) {
		return new BlobContent(data);
	}
} 