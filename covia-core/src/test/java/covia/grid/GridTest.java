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
		assertTrue(error.getMessage().contains("must be resolved before grid invocation"));
	}

	@Test
	void missingVenueReferenceIsExplicit() {
		IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
			() -> Grid.connect((String) null));
		assertTrue(missing.getMessage().contains("Venue reference is required"));
	}
}
