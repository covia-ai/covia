package covia.venue;

import static org.junit.jupiter.api.Assertions.*;
import static covia.venue.VenueStoreFixture.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.test.DurabilityTest;
import covia.venue.server.VenueServer;

/** Each immutable release-written store, upgraded and exercised through real HTTP. */
@DurabilityTest
class VenueStoreMigrationTest {

	/** Optional retained output for manual testing: a new directory per release; never reopen or overwrite an existing run. */
	private static Path runDirectory(String release) throws IOException {
		String output = System.getProperty("covia.migration.output");
		if (output == null) return TestTemp.dir("store-migration-" + release);
		return Files.createDirectory(Files.createDirectories(Path.of(output).toAbsolutePath()).resolve(release));
	}

	private static AMap<AString, ACell> unpack(String fixture, Path directory) throws Exception {
		try (var input = VenueStoreMigrationTest.class.getResourceAsStream("/migrations/" + fixture)) {
			assertNotNull(input, "Committed release fixture missing; do not regenerate it with current code");
			try (var archive = new ZipInputStream(input)) {
				for (var entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry()) {
					assertTrue(entry.getName().equals("venue.etch") || entry.getName().equals("manifest.json"));
					Files.copy(archive, directory.resolve(entry.getName()));
				}
			}
		}
		String release = VenueUpgradeTest.release(fixture);
		AMap<AString, ACell> manifest = RT.ensureMap(JSON.parse(Files.readString(directory.resolve("manifest.json"))));
		assertEquals(release, text(manifest.get(Strings.create("coviaVersion"))));
		assertEquals(RELEASES.get(release), text(manifest.get(Strings.create("sourceJarSha256"))));
		assertEquals(text(manifest.get(Strings.create("storeSha256"))), sha256(directory.resolve("venue.etch")));
		return manifest;
	}

	@TestFactory Stream<DynamicTest> releasedStoresSurviveUpgradeApiMutationsAndRepeatedRestart() throws Exception {
		return VenueUpgradeTest.fixtures().stream().map(fixture -> DynamicTest.dynamicTest(fixture, () -> rehearse(fixture)));
	}

	private static void rehearse(String fixture) throws Exception {
		String release = VenueUpgradeTest.release(fixture);
		Path directory = runDirectory(release);
		AMap<AString, ACell> manifest = unpack(fixture, directory);
		Path store = directory.resolve("venue.etch");
		ACell alice = RT.getIn(manifest, "users", 0);
		ACell bob = RT.getIn(manifest, "users", 1);
		ACell candidateStatus;

		VenueServer server = VenueServer.launch(config(store));
		try {
			assertEquals(text(manifest.get(Strings.create("venueDID"))), server.getEngine().getDIDString().toString());
			assertEquals(VenueState.CURRENT_VERSION, server.getEngine().getVenueState().version());
			verifyUser(server, alice, false);
			verifyUser(server, bob, false);
			assertCurrentJobs(server, alice);
			assertCurrentJobs(server, bob);
			assertUserIsolation(server, alice, bob);
			// The test adapter's scratch value is at x/, at the test adapter's
			// version: 0.9.11's moved from w/adapters in the start-up transaction.
			assertEquals(1, server.getEngine().getVenueState().adapterVersion("test"));
			assertEquals(Maps.of("origin", release), read(client(server, "11"), "x/test/scratch"));
			assertNull(read(client(server, "22"), "x/test/scratch"));
			write(client(server, "11"), "x/test/scratch/updated", Strings.create("after-upgrade"));
			updateUser(server, alice);
			updateUser(server, bob);
			verifyUser(server, alice, true);
			verifyUser(server, bob, true);
			server.getEngine().flush();
		} finally {
			server.close();
		}

		// Reopen independently: reads alone must not be relying on migrated caches.
		server = VenueServer.launch(config(store));
		try {
			candidateStatus = client(server, "42").getStatus().get(20, TimeUnit.SECONDS);
			verifyUser(server, alice, true);
			verifyUser(server, bob, true);
			assertCurrentJobs(server, alice);
			assertEquals(Strings.create("after-upgrade"), read(client(server, "11"), "x/test/scratch/updated"));
			exerciseUserTransferAndDeletion(server, alice);
			server.getEngine().flush();
		} finally {
			server.close();
		}

		server = VenueServer.launch(config(store));
		try {
			verifyUser(server, alice, true);
			verifyUser(server, bob, true);
			VenueHTTP imported = client(server, "33");
			assertEquals(read(client(server, "11"), "w/profile"), read(imported, "w/profile"));
			assertEquals("test-only-alice-updated", text(RT.getIn(run(imported, "secret/extract", Maps.of("name", SECRET)), "value")));
			assertTrue(server.getEngine().getVenueState().users().isDeleted(Strings.create(VenueAuth.keyPair(key("44")).getDID())));
			assertEquals(VenueState.CURRENT_VERSION, server.getEngine().getVenueState().version());
		} finally {
			server.close();
		}
		Files.writeString(directory.resolve("result.json"), JSON.toString(Maps.of(
			"passed", true, "baseline", release, "venueVersion", VenueState.CURRENT_VERSION,
			"candidateStatus", candidateStatus, "storeSha256", sha256(store), "checks", Vectors.of(
				"upgrade", "HTTP reads/writes", "isolation", "conversations", "secrets", "DLFS", "jobs",
				"adapter migration", "export/import/clone/delete", "two restarts"))), StandardCharsets.UTF_8);
		// Ready for an interactive launch; all keys/data are deliberately public.
		AMap<AString, ACell> live = config(store).assoc(Strings.create("port"), CVMLong.create(18080));
		Files.writeString(directory.resolve("venue.json"), JSON.toString(Maps.of("venues", Vectors.of(live))), StandardCharsets.UTF_8);
	}

	private static void assertCurrentJobs(VenueServer server, ACell user) throws Exception {
		VenueHTTP client = client(server, text(RT.getIn(user, "keyByte")));
		String jobId = text(RT.getIn(user, "jobId"));
		ACell job = client.getJobData(jobId).get(20, TimeUnit.SECONDS);
		assertNull(RT.getIn(job, "prev"), "REST must not return the legacy history chain");
		assertNull(RT.getIn(read(client, "j/" + jobId), "prev"), "Lattice reads must also expose current state only");
	}

	private static void assertUserIsolation(VenueServer server, ACell alice, ACell bob) throws Exception {
		VenueHTTP second = client(server, text(RT.getIn(bob, "keyByte")));
		String path = text(RT.getIn(alice, "did")) + "/w/profile";
		assertThrows(ExecutionException.class, () -> read(second, path));
		assertThrows(ExecutionException.class, () -> write(second, path, Maps.of("intrusion", true)));
		// Repeat the original reads after denial, including same-named agents.
		verifyUser(server, alice, false);
		verifyUser(server, bob, false);
	}

	private static void exerciseUserTransferAndDeletion(VenueServer server, ACell alice) throws Exception {
		VenueHTTP owner = client(server, "11");
		VenueHTTP operator = client(server, "42");
		String importedDID = VenueAuth.keyPair(key("33")).getDID();
		String clonedDID = VenueAuth.keyPair(key("44")).getDID();
		// The portable user export deliberately excludes private adapter state.
		ACell exported = run(owner, "user/export", Maps.of("accountKey", key("55").getAccountKey()));
		ACell value = RT.getIn(exported, "export");
		assertNotNull(value);
		run(operator, "secret/set", Maps.of("name", "FIXTURE_EXPORT_KEY", "value", key("55").getSeed().toHexString()));
		AMap<AString, ACell> input = Maps.of("did", importedDID, "export", value, "seed", "s/FIXTURE_EXPORT_KEY");
		run(operator, "user/import", input);
		assertEquals(read(owner, "w/profile"), read(client(server, "33"), "w/profile"));
		assertEquals("test-only-alice-updated", text(RT.getIn(run(client(server, "33"), "secret/extract", Maps.of("name", SECRET)), "value")));
		assertThrows(ExecutionException.class, () -> run(operator, "user/import", input));
		run(operator, "user/clone", Maps.of("from", RT.getIn(alice, "did"), "to", clonedDID));
		VenueHTTP cloned = client(server, "44");
		assertEquals(read(owner, "w/profile"), read(cloned, "w/profile"));
		assertEquals("test-only-alice-updated", text(RT.getIn(run(cloned, "secret/extract", Maps.of("name", SECRET)), "value")));
		write(cloned, "w/profile", Maps.of("cloneOnly", true));
		assertNotEquals(read(cloned, "w/profile"), read(owner, "w/profile"));
		assertNull(read(cloned, "x/test/scratch"));
		write(cloned, "x/test/scratch", Maps.of("deleteMe", true));
		run(operator, "user/delete", Maps.of("did", clonedDID));
		assertNull(server.getEngine().adapterState("test").read("users/" + clonedDID));
	}
}
