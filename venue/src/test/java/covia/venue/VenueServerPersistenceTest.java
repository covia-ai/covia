package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.venue.server.VenueServer;

/**
 * End-to-end persistence test using the full VenueServer stack.
 *
 * <p>Tests the exact lifecycle that previously failed: VenueServer.launch() →
 * HTTP WebDAV write → close → relaunch with same store → HTTP read.
 *
 * <p>The root cause was NodeServer.launch() (restore from Etch) happening after
 * Engine.initialiseFromCursor() (which read an empty cursor). Fixed by moving
 * launch() into the VenueServer constructor.
 */
public class VenueServerPersistenceTest {

	private HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5)).build();

	private HttpResponse<String> webdav(int port, String method, String path, String body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/dlfs/" + path))
			.timeout(Duration.ofSeconds(5));
		if (body != null) {
			builder.method(method, HttpRequest.BodyPublishers.ofString(body));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> testConfig(String storePath, String seedHex) {
		return (AMap<AString, ACell>) (AMap<?,?>) Maps.of(
			Fields.NAME, Strings.create("Persistence Test Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(seedHex),
			Config.WEBDAV, Maps.of(Config.ENABLED, true),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	/**
	 * Three-phase persistence test:
	 * 1. Start venue, write file via WebDAV, close
	 * 2. Restart with same store + seed, verify file survives, write second file
	 * 3. Restart again, verify both files survive
	 */
	@Test
	public void testDLFSDataSurvivesVenueRestart() throws Exception {
		File etchFile = File.createTempFile("venue-persist-", ".etch");
		etchFile.delete();
		etchFile.deleteOnExit();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');

		AKeyPair venueKey = AKeyPair.createSeeded(999);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		// === Phase 1: Start, write, close ===
		{
			VenueServer server = VenueServer.launch(config);

			HttpResponse<String> putRes = webdav(server.port(), "PUT", "test-drive/hello.txt", "persistent data!");
			assertEquals(201, putRes.statusCode(), "PUT should create file");

			HttpResponse<String> getRes = webdav(server.port(), "GET", "test-drive/hello.txt", null);
			assertEquals(200, getRes.statusCode());
			assertEquals("persistent data!", getRes.body());

			// Verify DLFS data reached root cursor
			ACell dlfs = server.getEngine().getRootCursor().get().get(Keyword.intern("dlfs"));
			assertNotNull(dlfs, "Root cursor should have :dlfs region after write");

			// Sync and verify store has the data
			server.getEngine().syncState();
			Thread.sleep(300);

			ACell storeRoot = server.getStore().getRootData();
			assertNotNull(storeRoot, "Store should have root data after sync");

			server.close();
		}

		// === Phase 2: Restart, read back, write more ===
		{
			VenueServer server = VenueServer.launch(config);

			// DLFS region should survive restart
			ACell dlfs = server.getEngine().getRootCursor().get().get(Keyword.intern("dlfs"));
			assertNotNull(dlfs, ":dlfs region should survive restart");

			// Venue user data (including DLFS key secret) should survive
			ACell venueUD = convex.core.lang.RT.getIn(
				server.getEngine().getVenueState().cursor().get(),
				Keyword.intern("user-data"));
			assertNotNull(venueUD, "Venue :user-data should survive restart");

			// File should be readable
			HttpResponse<String> getRes = webdav(server.port(), "GET", "test-drive/hello.txt", null);
			assertEquals(200, getRes.statusCode(),
				"File should be readable after restart (got " + getRes.statusCode() + ": " + getRes.body() + ")");
			assertEquals("persistent data!", getRes.body());

			// Write a second file
			HttpResponse<String> putRes = webdav(server.port(), "PUT", "test-drive/second.txt", "also persists");
			assertEquals(201, putRes.statusCode());

			server.close();
		}

		// === Phase 3: Second restart, both files survive ===
		{
			VenueServer server = VenueServer.launch(config);

			HttpResponse<String> get1 = webdav(server.port(), "GET", "test-drive/hello.txt", null);
			assertEquals(200, get1.statusCode(), "First file should survive second restart");
			assertEquals("persistent data!", get1.body());

			HttpResponse<String> get2 = webdav(server.port(), "GET", "test-drive/second.txt", null);
			assertEquals(200, get2.statusCode(), "Second file should survive second restart");
			assertEquals("also persists", get2.body());

			server.close();
		}
	}

	/**
	 * Verify the propagator persists data before graceful close.
	 * Write → wait for propagator → close → restart → read.
	 */
	@Test
	public void testSyncReachesPropagatorThroughVenueServer() throws Exception {
		File etchFile = File.createTempFile("venue-sync-", ".etch");
		etchFile.delete();
		etchFile.deleteOnExit();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');

		AKeyPair venueKey = AKeyPair.createSeeded(888);
		var config = testConfig(storePath, venueKey.getSeed().toHexString());

		VenueServer server = VenueServer.launch(config);

		HttpResponse<String> putRes = webdav(server.port(), "PUT", "sync-drive/test.txt", "sync test");
		assertEquals(201, putRes.statusCode());

		// Give propagator time to persist
		Thread.sleep(500);

		server.close();

		// Restart and verify data survived
		VenueServer server2 = VenueServer.launch(config);

		HttpResponse<String> getRes = webdav(server2.port(), "GET", "sync-drive/test.txt", null);
		assertEquals(200, getRes.statusCode(),
			"Data should survive restart (propagator should have persisted)");
		assertEquals("sync test", getRes.body());

		server2.close();
	}
}
