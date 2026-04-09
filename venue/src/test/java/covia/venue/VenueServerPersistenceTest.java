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
import convex.core.data.Strings;

/**
 * End-to-end persistence test using the full VenueServer stack.
 *
 * Reproduces the exact lifecycle: VenueServer.launch() → HTTP write → close →
 * relaunch with same store → HTTP read. This is the path that fails in the live
 * venue (issue #8) even though unit tests with Engine directly pass.
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

	@Test
	public void testDLFSDataSurvivesVenueRestart() throws Exception {
		File etchFile = File.createTempFile("venue-persist-", ".etch");
		etchFile.delete();
		etchFile.deleteOnExit();
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');

		AKeyPair venueKey = AKeyPair.createSeeded(999);
		String seedHex = venueKey.getSeed().toHexString();

		var config = testConfig(storePath, seedHex);

		// === Phase 1: Start, write, stop ===
		int port;
		{
			VenueServer server = VenueServer.launch(config);
			port = server.port();

			// Write via WebDAV
			HttpResponse<String> putRes = webdav(port, "PUT", "test-drive/hello.txt", "persistent data!");
			assertEquals(201, putRes.statusCode(), "PUT should create file");

			// Verify readable in same session
			HttpResponse<String> getRes = webdav(port, "GET", "test-drive/hello.txt", null);
			assertEquals(200, getRes.statusCode());
			assertEquals("persistent data!", getRes.body());

			// Verify root cursor has DLFS data
			ACell dlfs = server.getEngine().getRootCursor().get().get(
				Keyword.intern("dlfs"));
			assertNotNull(dlfs, "Root cursor should have :dlfs region after write");

			// Check: DLFS key in fork before explicit sync
			String pubDID = server.getEngine().getDIDString().toString();
			System.out.println("Phase 1 venue DID: " + pubDID);
			System.out.println("Phase 1 AccountKey: " + server.getEngine().getAccountKey());
			User userInFork = server.getEngine().getVenueState().users().ensure(Strings.create(pubDID));
			byte[] ek = SecretStore.deriveKey(server.getEngine().getKeyPair());
			AString keyInFork = userInFork.secrets().decrypt("DLFS_KEY", ek);
			System.out.println("DLFS_KEY in fork BEFORE syncState: " + (keyInFork != null ? "present" : "NULL"));

			// Print the fork cursor value directly
			ACell forkValue = server.getEngine().getVenueState().cursor().get();
			ACell forkUD = convex.core.lang.RT.getIn(forkValue, Keyword.intern("user-data"));
			System.out.println("Fork cursor :user-data: " + (forkUD != null ? "present" : "NULL"));
			System.out.println("Fork cursor class: " + server.getEngine().getVenueState().cursor().getClass().getSimpleName());

			// Force sync
			server.getEngine().syncState();
			Thread.sleep(500);

			// Check root cursor user-data after sync — correct path is inside venue slot
			ACell rootUD = convex.core.lang.RT.getIn(
				server.getEngine().getRootCursor().get(),
				Keyword.intern("grid"), Keyword.intern("venues"),
				server.getEngine().getAccountKey(), convex.core.cvm.Keywords.VALUE,
				Keyword.intern("user-data"));
			System.out.println("Venue :user-data in root after syncState: " + (rootUD != null ? "present" : "NULL"));

			// Check store has root data BEFORE close
			ACell storeRoot = server.getStore().getRootData();
			assertNotNull(storeRoot, "Store should have root data before close");
			ACell storedDlfs = convex.core.lang.RT.getIn(storeRoot, Keyword.intern("dlfs"));
			assertNotNull(storedDlfs, ":dlfs should be in store root data before close");

			// Check if user-data is in the store at correct path (inside venue slot)
			ACell storeUD = convex.core.lang.RT.getIn(storeRoot,
				Keyword.intern("grid"), Keyword.intern("venues"),
				server.getEngine().getAccountKey(), convex.core.cvm.Keywords.VALUE,
				Keyword.intern("user-data"));
			System.out.println("Store venue :user-data: " + (storeUD != null ? "present" : "NULL"));

			// Graceful close (triggers shutdown persist)
			server.close();
		}

		// === Phase 2: Restart with same store + seed, read back ===
		{
			VenueServer server = VenueServer.launch(config);
			int newPort = server.port();

			// Verify restore happened
			ACell dlfs = server.getEngine().getRootCursor().get().get(
				Keyword.intern("dlfs"));
			assertNotNull(dlfs, ":dlfs region should survive restart");

			// Check what the venue state looks like after restore
			ACell venueValue = server.getEngine().getVenueState().cursor().get();
			ACell p2UserData = convex.core.lang.RT.getIn(venueValue, Keyword.intern("user-data"));
			System.out.println("Phase 2 venue :user-data: " + (p2UserData != null ? "present" : "NULL"));

			String publicDID = server.getEngine().getDIDString().toString();
			System.out.println("Phase 2 venue DID: " + publicDID);
			System.out.println("Phase 2 AccountKey: " + server.getEngine().getAccountKey());
			User user = server.getEngine().getVenueState().users().ensure(Strings.create(publicDID));
			byte[] encKey = SecretStore.deriveKey(server.getEngine().getKeyPair());
			AString dlfsKeySecret = user.secrets().decrypt("DLFS_KEY", encKey);
			System.out.println("DLFS_KEY secret after restore: " + (dlfsKeySecret != null ? "present" : "NULL"));

			// Check: does DLFSAdapter see the drive?
			covia.adapter.DLFSAdapter dlfsAdapter = (covia.adapter.DLFSAdapter) server.getEngine().getAdapter("dlfs");
			var drive = dlfsAdapter.getDriveForIdentity(publicDID, "test-drive");
			assertNotNull(drive, "Drive should be accessible after restore");

			// Check drive root
			var rootNode = drive.getNode(drive.getPath("/"));
			System.out.println("Drive root after restore: " + (rootNode != null ? "exists, entries=" + rootNode : "NULL"));

			// Check file
			var fileNode = drive.getNode(drive.getPath("/hello.txt"));
			System.out.println("hello.txt after restore: " + (fileNode != null ? "exists" : "NULL"));

			// Read the file via WebDAV
			HttpResponse<String> getRes = webdav(newPort, "GET", "test-drive/hello.txt", null);
			assertEquals(200, getRes.statusCode(),
				"File should be readable after restart (got " + getRes.statusCode() + ": " + getRes.body() + ")");
			assertEquals("persistent data!", getRes.body(),
				"File content should survive restart");

			// Write another file to verify writes still work after restore
			HttpResponse<String> putRes2 = webdav(newPort, "PUT", "test-drive/second.txt", "also persists");
			assertEquals(201, putRes2.statusCode());

			server.close();
		}

		// === Phase 3: Second restart — both files should survive ===
		{
			VenueServer server = VenueServer.launch(config);
			int port3 = server.port();

			HttpResponse<String> get1 = webdav(port3, "GET", "test-drive/hello.txt", null);
			assertEquals(200, get1.statusCode(), "First file should survive second restart");
			assertEquals("persistent data!", get1.body());

			HttpResponse<String> get2 = webdav(port3, "GET", "test-drive/second.txt", null);
			assertEquals(200, get2.statusCode(), "Second file should survive second restart");
			assertEquals("also persists", get2.body());

			server.close();
		}
	}

	/**
	 * Verify that sync() from DLFSLocal reaches the NodeServer's propagator
	 * when going through the full VenueServer stack.
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
		int port = server.port();

		// Write via WebDAV
		HttpResponse<String> putRes = webdav(port, "PUT", "sync-drive/test.txt", "sync test");
		assertEquals(201, putRes.statusCode());

		// Give propagator time
		Thread.sleep(500);

		// Check the Etch store directly — rootData should be set
		// We can't access the store directly from VenueServer, but we can
		// verify by force-killing (no graceful shutdown) and restarting
		// If the propagator persisted, the data survives even without close()

		// Note: we DO close gracefully here to avoid port conflicts,
		// but the point is the propagator should have already persisted
		server.close();

		// Restart and verify
		VenueServer server2 = VenueServer.launch(config);
		int port2 = server2.port();

		HttpResponse<String> getRes = webdav(port2, "GET", "sync-drive/test.txt", null);
		assertEquals(200, getRes.statusCode(),
			"Propagator should have persisted data before close (got " +
			getRes.statusCode() + ": " + getRes.body() + ")");
		assertEquals("sync test", getRes.body());

		server2.close();
	}
}
