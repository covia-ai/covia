package covia.grid.auth;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * The rules by which a connection fixes the DID its credentials are audienced
 * to ({@link VenueDID#refusal}): the DID it was opened for, only when the venue
 * presents that DID; otherwise the presented DID, only where the address
 * proves it or is loopback. Never another DID.
 */
public class VenueDIDTest {

	private static final String KEY_DID = "did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
	private static final String WEB_DID = "did:web:venue.example.com";

	private static void assertRefused(String reason, URI base, String expected, String presented) {
		String refusal = VenueDID.refusal(base, expected, presented);
		assertTrue(refusal != null && refusal.startsWith(reason),
			"expected refusal " + reason + ", got " + refusal);
	}

	@Test
	public void openedForADIDKeepsItOnlyIfTheVenuePresentsIt() {
		URI remote = URI.create("https://venue.example.com/api/v1/");
		assertNull(VenueDID.refusal(remote, WEB_DID, WEB_DID));
		assertNull(VenueDID.refusal(remote, KEY_DID, KEY_DID), "a named did:key is the client's to vouch for");
		assertRefused(VenueDID.PRESENTS_OTHER, remote, WEB_DID, KEY_DID);
		// alsoKnownAs is no rebinding: a venue presenting its did:key is not its did:web.
		assertRefused(VenueDID.PRESENTS_OTHER, remote, KEY_DID, WEB_DID);
		assertRefused(VenueDID.PRESENTS_OTHER, URI.create("http://localhost:8080/"), WEB_DID, KEY_DID);
		// The same DID however its percent-encodings are written (CAD043)...
		URI local = URI.create("http://127.0.0.1:8080/");
		assertNull(VenueDID.refusal(local, "did:web:127.0.0.1%3a8080", "did:web:127.0.0.1%3A8080"));
		assertNull(VenueDID.refusal(local, "did:web:venue%2Dexample.com", "did:web:venue-example.com"));
		// ...but an encoded ":" (a port) is not a raw one (a path).
		assertRefused(VenueDID.PRESENTS_OTHER, local, "did:web:127.0.0.1:8080", "did:web:127.0.0.1%3A8080");
	}

	@Test
	public void reachedByURLAloneTakesOnlyAProvenDID() {
		assertNull(VenueDID.refusal(URI.create("https://venue.example.com/api/v1/"), null, WEB_DID));
		assertRefused(VenueDID.UNPROVEN, URI.create("https://other.example.com/"), null, WEB_DID);
		assertRefused(VenueDID.UNPROVEN, URI.create("http://venue.example.com/"), null, WEB_DID);
		assertRefused(VenueDID.UNPROVEN, URI.create("https://venue.example.com/"), null, KEY_DID);
	}

	@Test
	public void loopbackTakesThePresentedDID() {
		for (String base : new String[] {"http://localhost:8080/", "http://127.0.0.1:8080/", "http://[::1]:8080/"}) {
			assertNull(VenueDID.refusal(URI.create(base), null, KEY_DID), base);
			assertNull(VenueDID.refusal(URI.create(base), null, WEB_DID), base);
		}
	}
}
