package covia.api;

import java.net.URI;

/**
 * Represents a Decentralized Identifier (DID) with method, id, and path components.
 * 
 * A DID follows the format: did:method:method_specific_id/path where path is optional.
 * Examples:
 * - did:web:example.com
 * - did:web:example.com:8080
 */
public class DID {

	private static final String DID_SCHEME = "did";
	private static final String DID_START = "did:";
    
    private final String method;
    private final String id;
    
    /**
     * Constructs a DID with the specified components.
     * 
     * @param method The DID method (e.g., "web", "key", "peer")
     * @param id The DID identifier
     */
    public DID(String method, String id) {
        if (method == null) {
            throw new IllegalArgumentException("DID method cannot be null");
        }
        if (id == null) {
            throw new IllegalArgumentException("DID id cannot be null");
        }
        
        this.method = method;
        this.id = id;
    }
    

    
    /**
     * Constructs a DID from a URI.
     * 
     * @param uri The URI to parse
     * @return A new DID instance
     * @throws IllegalArgumentException if the URI is not a valid DID
     */
    public static DID fromURI(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI cannot be null");
        }
        
        String scheme=uri.getScheme();
        if (!DID_SCHEME.equals(scheme)) {
        	throw new IllegalArgumentException("DID must start with 'did:'");
        }
        
        // URI path contains DID method, ID and DID path
        String msp=uri.getSchemeSpecificPart();
        int pathColon=msp.indexOf(':');
        if (pathColon<0) {
        	throw new IllegalArgumentException("DID must have a method and id");
        }
        String method=msp.substring(0,pathColon);
        String idAndPath=msp.substring(pathColon+1);
        int slashPos=idAndPath.indexOf('/');
        if (slashPos<0) {
        	return new DID(method,idAndPath);
        } else {
        	// A slash in the URI path is the end of the ID
        	String id=idAndPath.substring(0, slashPos);
        	
        	return new DID(method, id);
        }
    }
    
    /**
     * Constructs a DID from a string representation.
     * 
     * @param didString The DID string to parse
     * @return A new DID instance
     * @throws IllegalArgumentException if the string is not a valid DID
     */
    public static DID fromString(String didString) {
        return fromURI(URI.create(didString));
    }
    
    /**
     * Gets the DID method.
     * 
     * @return The DID method
     */
    public String getMethod() {
        return method;
    }
    
    /**
     * Gets the DID identifier.
     * 
     * @return The DID identifier
     */
    public String getId() {
        return id;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof DID other ) {
        	return method.equals(other.method) &&
               id.equals(other.id);
        } else {
        	return false;
        }
    }
    
    @Override
    public int hashCode() {
        int result = method.hashCode();
        result = 31 * result + id.hashCode();
        return result;
    }
    
    @Override
    public String toString() {
        return DID_START+method+":"+id;
    }
}
