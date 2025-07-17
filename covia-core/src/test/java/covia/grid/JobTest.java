package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Strings;

public class JobTest {

	@Test public void testIDParse() {
		assertEquals(Strings.create("1234"),Job.parseID("0x1234"));
		assertEquals(Strings.create("1234"),Job.parseID(Strings.create("0x1234")));
	}
}
