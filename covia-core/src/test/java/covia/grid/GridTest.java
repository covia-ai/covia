package covia.grid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GridTest {
	@Test
	void bareVenueLabelsAreRejectedWithoutGuessing() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> Grid.connect("venue-3"));
		assertTrue(error.getMessage().contains("venue-3"));
	}

	@Test
	void aPaddedReferenceIsNoReference() {
		assertThrows(IllegalArgumentException.class, () -> Grid.connect(" https://venue.example"));
		assertThrows(IllegalArgumentException.class, () -> Grid.connect(" did:web:venue.example"));
	}

	@Test
	void missingVenueReferenceIsExplicit() {
		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
			() -> Grid.connect((String) null));
		assertFalse(missing.getMessage().isBlank());
	}

	@Test
	void additionalDidMethodsPlugIntoVenueResolution() {
		Grid.registerDIDResolver("exampletest", (did, auth) -> {
			throw new IllegalStateException("example resolver called for " + did);
		});
		IllegalStateException called = assertThrows(IllegalStateException.class,
			() -> Grid.connect("did:exampletest:venue-7"));
		assertTrue(called.getMessage().contains("example resolver called"));
	}
}
