package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.util.JSON;

/**
 * Config values are used exactly as written, with nothing tidied on the way
 * in, so the configs this repository ships must already be clean.
 */
public class ShippedConfigTest {

	private static final List<String> CONFIGS =
		List.of("local-dev.json", "venue-config.json", "demo-config.json", "docker-local.json");

	@Test
	public void everyStringIsWrittenExactly() throws IOException {
		for (String name : CONFIGS) {
			assertClean(name, JSON.parseJSON5(Files.readString(Path.of("..", name))));
		}
	}

	private static void assertClean(String where, ACell cell) {
		if (cell instanceof AString s) {
			assertEquals(s.toString().strip(), s.toString(), where);
		} else if (cell instanceof AMap<?, ?> map) {
			for (Map.Entry<?, ?> e : map.entrySet()) {
				assertClean(where, (ACell) e.getKey());
				assertClean(where + "/" + e.getKey(), (ACell) e.getValue());
			}
		} else if (cell instanceof AVector<?> vector) {
			for (long i = 0; i < vector.count(); i++) {
				assertClean(where + "/" + i, vector.get(i));
			}
		}
	}
}
