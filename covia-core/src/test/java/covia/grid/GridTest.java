package covia.grid;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GridTest {
	@Test
	void bareVenueLabelsAreRejectedWithoutGuessing() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> Grid.connect(" venue-3 "));
		assertTrue(error.getMessage().contains("Unqualified venue reference 'venue-3'"));
		assertTrue(error.getMessage().contains("registered venue resolver"));
	}

	@Test
	void missingVenueReferenceIsExplicit() {
		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
			() -> Grid.connect((String) null));
		assertTrue(missing.getMessage().contains("Venue reference is required"));
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
