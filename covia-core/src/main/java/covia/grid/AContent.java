package covia.grid;

import java.io.InputStream;

import convex.core.data.ABlob;

/**
 * Abstract base class for content that can be stored and retrieved.
 * Provides methods to access content as both a Blob and an InputStream.
 */
public abstract class AContent {
    
    /**
     * Get the content as a Blob.
     * @return The content as a Blob, or null if not available
     */
    public abstract ABlob getBlob();
    
    /**
     * Get the content as an InputStream.
     * @return The content as an InputStream, or null if not available
     */
    public abstract InputStream getInputStream();
    
    /**
     * Get the size of the content in bytes.
     * @return The size in bytes, or -1 if size is unknown
     */
    public abstract long getSize();

} 