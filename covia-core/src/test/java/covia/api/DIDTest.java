package covia.api;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

public class DIDTest {
    
    @Test
    void testBasicDIDConstruction() {
        DID did = new DID("web", "example.com");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getId());
        assertEquals("did:web:example.com", did.toString());
    }
    
    @Test
    void testDIDWithPath() {
        DID did = new DID("web", "example.com");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getId());
        assertEquals("did:web:example.com", did.toString());
    }
    
 
    
    @Test
    void testFromStringBasic() {
        DID did = DID.fromString("did:web:example.com");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getId());
    }
    
    @Test
    void testFromStringWithPath() {
        DID did = DID.fromString("did:web:example.com/path");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getId());
    }

    
    @Test
    void testFromStringWithPathAndPort() {
        DID did = DID.fromString("did:web:example.com:8080/api");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com:8080", did.getId());
    }
    
    @Test
    void testFromStringComplexPath() {
        DID did = DID.fromString("did:web:example.com:8080/api/v1/users/123");
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com:8080", did.getId());
    }

    
    @Test
    void testFromStringInvalidFormat() {
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("invalid:format");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("did:web");
        });
    }
    
    @Test
    void testFromStringNull() {
        assertThrows(NullPointerException.class, () -> {
            DID.fromString(null);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromString("   ");
        });
    }
    
    @Test
    void testFromURI() throws URISyntaxException {
        URI uri = new URI("did:web:example.com/api");
        DID did = DID.fromURI(uri);
        
        assertEquals("web", did.getMethod());
        assertEquals("example.com", did.getId());
    }
    
    @Test
    void testFromURINull() {
        assertThrows(IllegalArgumentException.class, () -> {
            DID.fromURI(null);
        });
    }
    
    @Test
    void testConstructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> {
            new DID(null, "example.com");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new DID(null, "example.com");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new DID("web", null);
        });
    }
    
    @Test
    void testEquals() {
        DID did1 = new DID("web", "example.com");
        DID did2 = new DID("web", "example.com");
        DID did3 = new DID("web", "example.com:8080");
        
        assertEquals(did1, did2);
        assertNotEquals(did1, did3);
        assertNotEquals(did1, "not a DID");
        assertNotEquals(did1, null);
    }
    
    @Test
    void testHashCode() {
        DID did1 = new DID("web", "example.com");
        DID did2 = new DID("web", "example.com");
        
        assertEquals(did1.hashCode(), did2.hashCode());
    }
    
    @Test
    void testDifferentMethods() {
        DID webDid = new DID("web", "example.com");
        DID keyDid = new DID("key", "example.com");
        
        assertNotEquals(webDid, keyDid);
        assertEquals("did:web:example.com", webDid.toString());
        assertEquals("did:key:example.com", keyDid.toString());
    }
}
