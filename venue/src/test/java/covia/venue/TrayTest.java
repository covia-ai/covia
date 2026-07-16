package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tray is best-effort chrome around the venue: these pin the guarantees that
 * matter — a headless JVM gets a clean zero (never an exception), and the icon
 * always paints. The interactive path (a real tray icon) is not exercised in
 * tests: the suite must never add icons to the developer's actual system tray.
 */
class TrayTest {

	@Test
	void headlessInstallIsAQuietNo() {
		// The guard seam takes headless explicitly — ambient AWT state is global and
		// another (parallel) test class may already have initialised it non-headless.
		assertEquals(0, Tray.install(List.of(), true), "headless → no tray, no exception");
	}

	@Test
	void iconAlwaysPaints() {
		var icon = Tray.drawIcon();   // offscreen drawing is headless-safe
		assertNotNull(icon, "painted mark — never null");
		assertTrue(icon.getWidth(null) > 0);
	}
}
