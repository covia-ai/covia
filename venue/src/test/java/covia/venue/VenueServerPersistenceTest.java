package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.server.VenueServer;
import covia.test.DurabilityTest;

/**
 * A venue server's start over a persistent store: a failed bootstrap publishes
 * nothing and releases what it opened, and relaunching an existing store needs
 * its identity. That the venue's state survives a reopen is
 * {@link VenuePersistenceTest}'s.
 */
// Durability guarantee: forks real JVMs / relaunches venues to prove crash
// and restart survival. Slow and unavoidably so — excluded from the default
// `mvn test` inner loop, run on every push via CI (-DexcludedGroups=integration).
@DurabilityTest
public class VenueServerPersistenceTest {

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

	private static boolean failureContains(Throwable failure, String text) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (String.valueOf(current.getMessage()).contains(text)) return true;
		}
		return false;
	}

	@Test
	public void testBootstrapFailureNeverPublishesAndReleasesOwnedResources() throws Exception {
		File etchFile = TestTemp.etchFile("venue-bootstrap-fail");
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		String seed = AKeyPair.createSeeded(424242).getSeed().toHexString();

		int port;
		try (ServerSocket reservation = new ServerSocket(0)) {
			port = reservation.getLocalPort();
		}
		String missingModule = etchFile.toPath().resolveSibling("missing-module.jar")
			.toString().replace('\\', '/');
		AMap<AString, ACell> failingConfig = testConfig(storePath, seed)
			.assoc(Config.PORT, CVMLong.create(port))
			.assoc(Config.MODULES, Vectors.of(Strings.create(missingModule)));

		RuntimeException failure = assertThrows(RuntimeException.class,
			() -> VenueServer.launch(failingConfig));
		assertTrue(failureContains(failure, "missing-module.jar"),
			"launch must preserve the bootstrap diagnostic: " + failure);

		// HTTP publication is the final launch step, so a bootstrap failure must
		// never have bound the configured port (and rollback must release it even
		// if an HTTP implementation began setup internally).
		try (ServerSocket rebound = new ServerSocket(port)) {
			assertTrue(rebound.isBound(), "failed launch must leave no HTTP listener");
		}

		// The same persistent store must be immediately reusable, proving the
		// Engine, NodeServer and Etch store were all released by launch rollback.
		VenueServer recovered = VenueServer.launch(testConfig(storePath, seed));
		try {
			assertTrue(recovered.getEngine().isStarted());
			assertNotNull(recovered.getEngine().resolveAsset(
				Strings.create("v/ops/agent/create"), recovered.getEngine().venueContext()),
				"a returned server must expose a fully bootstrapped adapter catalog");
		} finally {
			recovered.close();
		}
	}

	/**
	 * Venue identity lifecycle (#232): first launch of a new persistent store
	 * may generate and save venue.key; relaunching an EXISTING store without
	 * any identity source (seed, keystore, venue.key) is a CONFIG ERROR —
	 * never a silent fresh DID, which would orphan every client's state.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testRelaunchWithoutIdentityIsConfigError() throws Exception {
		// venue.key is written as a SIBLING of the store, in the store's own directory.
		File etchFile = TestTemp.etchFile("venue-noid");
		String storePath = etchFile.getAbsolutePath().replace('\\', '/');
		java.nio.file.Path keyFile = etchFile.toPath().resolveSibling("venue.key");

		AMap<AString, ACell> config = (AMap<AString, ACell>) (AMap<?,?>) Maps.of(
			Fields.NAME, Strings.create("No-Identity Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath));

		// First launch: allowed — identity generated and saved beside the store.
		VenueServer server = VenueServer.launch(config);
		String did1 = String.valueOf(server.getEngine().getDIDString());
		server.close();
		assertTrue(java.nio.file.Files.exists(keyFile),
			"first launch saves venue.key beside the store");
		boolean posix = Files.getFileStore(keyFile).supportsFileAttributeView("posix");
		if (posix) {
			assertEquals(PosixFilePermissions.fromString("rw-------"),
				Files.getPosixFilePermissions(keyFile),
				"new venue.key must be owner-only");
			// Simulate an existing installation created with permissive defaults.
			Files.setPosixFilePermissions(keyFile,
				PosixFilePermissions.fromString("rw-r--r--"));
		}

		// Relaunch with venue.key intact: same identity.
		VenueServer server2 = VenueServer.launch(config);
		assertEquals(did1, String.valueOf(server2.getEngine().getDIDString()),
			"identity survives relaunch via venue.key");
		server2.close();
		if (posix) {
			assertEquals(PosixFilePermissions.fromString("rw-------"),
				Files.getPosixFilePermissions(keyFile),
				"launch must repair permissions on an existing venue.key");
		}

		// venue.key lost: relaunching the existing store must fail loudly.
		java.nio.file.Files.delete(keyFile);
		Exception e = assertThrows(Exception.class, () -> VenueServer.launch(config));
		String msg = String.valueOf(e.getMessage())
			+ (e.getCause() != null ? " " + e.getCause().getMessage() : "");
		assertFalse(msg.isBlank());
	}
}
