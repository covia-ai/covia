package covia.venue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.DLFSAdapter;
import covia.api.Fields;
import covia.venue.server.VenueServer;

/**
 * Venue creation on a configured Etch store (covia `etch` config block,
 * Convex 0.8.11): the operator's Etch creation policy — version, cipher,
 * encryption key source — passes through to the store the venue opens.
 * Covers the headline case: an <b>encrypted Etch v3</b> venue that persists
 * across restarts, with fail-closed behaviour for a wrong or missing key.
 */
public class EtchV3VenueTest {

	private static final String KEY_HEX =
		"0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
	private static final String WRONG_KEY_HEX =
		"20ff030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f01";
	private static final String SEED_HEX =
		"5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f5f";

	private static AMap<AString, ACell> config(String storePath, String keyHex) {
		AMap<AString, ACell> etch = Maps.of(
			Strings.intern("version"), CVMLong.create(3),
			Strings.intern("cipher"), Strings.create("aes-256-ctr"),
			Strings.intern("encryptIndex"), true);
		if (keyHex != null) etch = etch.assoc(Strings.intern("key"), Strings.create(keyHex));
		return Maps.of(
			Config.PORT, 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(SEED_HEX),
			Config.ETCH, etch,
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
	}

	@Test
	public void encryptedEtchV3VenuePersistsAcrossRestart() throws Exception {
		File dir = TestTemp.dir("etch-v3-venue").toFile();
		String storePath = new File(dir, "venue.etch").getAbsolutePath().replace('\\', '/');
		AString value = Strings.create("survives encrypted restart");
		AString userDID = Strings.create("did:key:z6Mk-test-etch-v3");

		VenueServer first = VenueServer.launch(config(storePath, KEY_HEX));
		try {
			first.getEngine().jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/etch-v3-proof", Fields.VALUE, value),
				RequestContext.of(userDID)).get(5, TimeUnit.SECONDS);
			first.getEngine().flush();
		} finally {
			first.close();
		}
		assertTrue(new File(storePath).exists(), "the store file must exist on disk");

		// Same config, fresh venue: state must be readable through the
		// encrypted store — proving the Etch policy applied on create AND open.
		VenueServer second = VenueServer.launch(config(storePath, KEY_HEX));
		try {
			ACell read = second.getEngine().jobs().invokeInternal("v/ops/covia/read",
				Maps.of(Fields.PATH, "w/etch-v3-proof"),
				RequestContext.of(userDID)).get(5, TimeUnit.SECONDS);
			assertEquals(value, RT.getIn(read, Fields.VALUE),
				"workspace state must survive an encrypted Etch v3 restart");
		} finally {
			second.close();
		}
	}

	@Test
	public void wrongKeyAndPlainOpenFailClosed() throws Exception {
		File dir = TestTemp.dir("etch-v3-keys").toFile();
		String storePath = new File(dir, "venue.etch").getAbsolutePath().replace('\\', '/');

		VenueServer server = VenueServer.launch(config(storePath, KEY_HEX));
		try {
			server.getEngine().flush();
		} finally {
			server.close();
		}

		// Wrong key must never open the store as if empty or corrupt it — and
		// the failure is at key IDENTIFICATION (the file's public-key hint vs
		// the configured key's derived identity), not a decrypt failure.
		Exception wrongKey = assertThrows(Exception.class,
			() -> VenueServer.launch(config(storePath, WRONG_KEY_HEX)).close(),
			"a wrong Etch encryption key must fail the launch");
		assertTrue(failureContains(wrongKey, "encrypted under key"),
			"wrong key must fail at hint identification with a diagnostic, got: " + wrongKey);

		// Opening without any etch policy must not succeed either — the file
		// is encrypted, and a silent plain open would be a downgrade.
		assertThrows(Exception.class,
			() -> VenueServer.launch(Maps.of(
				Config.PORT, 0,
				Config.STORE, Strings.create(storePath),
				Config.SEED, Strings.create(SEED_HEX))).close(),
			"an encrypted store must not open without its key");

		// The right key still works after the failed attempts.
		VenueServer again = VenueServer.launch(config(storePath, KEY_HEX));
		again.close();
	}

	private static boolean failureContains(Throwable failure, String text) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (String.valueOf(current.getMessage()).contains(text)) return true;
		}
		return false;
	}

	@Test
	public void operatorPinnedHintOverridesTheDerivedIdentity() throws Exception {
		// An explicit publicKeyHint passes through as-is: the file is stamped
		// with the operator's label and the key function verifies against it,
		// so create → reopen round-trips under the pinned hint.
		File dir = TestTemp.dir("etch-v3-hint").toFile();
		String storePath = new File(dir, "venue.etch").getAbsolutePath().replace('\\', '/');
		String pinnedHint =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
		java.util.function.Function<String, AMap<AString, ACell>> cfg = path -> Maps.of(
			Config.PORT, 0,
			Config.STORE, Strings.create(path),
			Config.SEED, Strings.create(SEED_HEX),
			Config.ETCH, Maps.of(
				Strings.intern("version"), CVMLong.create(3),
				Strings.intern("cipher"), Strings.create("aes-256-ctr"),
				Strings.intern("publicKeyHint"), Strings.create(pinnedHint),
				Strings.intern("key"), Strings.create(KEY_HEX)));
		VenueServer.launch(cfg.apply(storePath)).close();
		VenueServer reopened = VenueServer.launch(cfg.apply(storePath));
		reopened.close();
	}

	@Test
	public void encryptedTempStoreVenueLaunches() throws Exception {
		// The etch policy applies to "temp" stores too — useful for ephemeral
		// venues handling sensitive data.
		AMap<AString, ACell> cfg = config("temp", KEY_HEX);
		VenueServer server = VenueServer.launch(cfg);
		try {
			ACell echo = server.getEngine().jobs().invokeInternal("v/test/ops/echo",
				Maps.of(Fields.VALUE, "encrypted temp"),
				RequestContext.of(Strings.create("did:key:z6Mk-test-etch-temp")))
				.get(5, TimeUnit.SECONDS);
			assertEquals(Strings.create("encrypted temp"), RT.getIn(echo, Fields.VALUE));
		} finally {
			server.close();
		}
	}

	@Test
	public void etchConfigIsRejectedForMemoryStores() {
		assertThrows(Exception.class, () -> VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.STORE, Strings.create("memory"),
			Config.ETCH, Maps.of(Strings.intern("version"), CVMLong.create(3)))).close(),
			"an etch policy on a non-Etch store must fail closed");
	}

	@Test
	public void malformedEtchConfigFailsAtConstruction() {
		// Unknown cipher
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.ETCH, Maps.of(Strings.intern("cipher"), Strings.create("rot13")))));
		// Key of the wrong size
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.ETCH, Maps.of(
				Strings.intern("cipher"), Strings.create("aes-256-ctr"),
				Strings.intern("key"), Strings.create("abcd")))));
		// Malformed key source shape
		assertThrows(RuntimeException.class, () -> new Config(Maps.of(
			Config.ETCH, Maps.of(
				Strings.intern("cipher"), Strings.create("aes-256-ctr"),
				Strings.intern("key"), Maps.of(Strings.intern("wat"), Strings.create("x"))))));
	}

	@Test
	public void encryptionWithoutAnyKeySourceConstructsButFailsOperatorLaunch() {
		// An embedder may supply the key function at runtime, so construction
		// tolerates a keyless encrypted policy — but an operator launch (no
		// adopted store, no function) still fails closed at store creation.
		AMap<AString, ACell> keyless = Maps.of(
			Config.PORT, 0,
			Config.STORE, Strings.create("temp"),
			Config.ETCH, Maps.of(
				Strings.intern("version"), CVMLong.create(3),
				Strings.intern("cipher"), Strings.create("aes-256-ctr")));
		new Config(keyless); // constructs — embedder path stays open
		assertThrows(Exception.class, () -> VenueServer.launch(keyless).close(),
			"an encrypted policy with no key source must fail an operator launch");
	}

	@Test
	public void configKeyAndEmbedderFunctionAreMutuallyExclusive() {
		Config cfg = new Config(Maps.of(Config.ETCH, Maps.of(
			Strings.intern("version"), CVMLong.create(3),
			Strings.intern("cipher"), Strings.create("aes-256-ctr"),
			Strings.intern("key"), Strings.create(KEY_HEX))));
		assertThrows(RuntimeException.class,
			() -> cfg.getEtchConfig(hint -> new byte[32]),
			"two key sources is an ambiguity, not a fallback chain");
	}

	@Test
	public void embedderSuppliedKeyFunctionAndAdoptedStoreWork() throws Exception {
		// The embedder vault shape (e.g. GetMine): key material lives in the
		// embedder's code (KMS / passphrase-derived), the etch policy compiles
		// with a key FUNCTION, the embedder opens the store itself and adopts
		// it into the venue. No key ever touches config, env, or disk.
		File dir = TestTemp.dir("etch-v3-embedder").toFile();
		File storeFile = new File(dir, "vault.etch");
		byte[] vaultKey = convex.core.data.Blob.fromHex(KEY_HEX).getBytes();

		AMap<AString, ACell> etchPolicy = Maps.of(
			Strings.intern("version"), CVMLong.create(3),
			Strings.intern("cipher"), Strings.create("chacha20"),
			Strings.intern("encryptIndex"), true);
		AMap<AString, ACell> venueConfig = Maps.of(
			Config.PORT, 0,
			Config.SEED, Strings.create(SEED_HEX),
			Config.USERS, Maps.of(Config.AUTO_CREATE, true));
		AString userDID = Strings.create("did:key:z6Mk-test-embedder-vault");
		AString value = Strings.create("vault contents");

		convex.etch.EtchConfig policy = new Config(Maps.of(Config.ETCH, etchPolicy))
			.getEtchConfig(hint -> vaultKey);
		VenueServer first = VenueServer.launch(venueConfig,
			convex.etch.EtchStore.create(storeFile, policy));
		try {
			first.getEngine().jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, "w/vault-entry", Fields.VALUE, value),
				RequestContext.of(userDID)).get(5, TimeUnit.SECONDS);
			first.getEngine().flush();
		} finally {
			first.close();
		}

		convex.etch.EtchConfig reopenPolicy = new Config(Maps.of(Config.ETCH, etchPolicy))
			.getEtchConfig(hint -> vaultKey);
		VenueServer second = VenueServer.launch(venueConfig,
			convex.etch.EtchStore.create(storeFile, reopenPolicy));
		try {
			ACell read = second.getEngine().jobs().invokeInternal("v/ops/covia/read",
				Maps.of(Fields.PATH, "w/vault-entry"),
				RequestContext.of(userDID)).get(5, TimeUnit.SECONDS);
			assertEquals(value, RT.getIn(read, Fields.VALUE),
				"the embedder-keyed encrypted vault must persist across venue restarts");
		} finally {
			second.close();
		}
	}

	/**
	 * Release regression for GetMine #303. The product writes staged files via
	 * short-lived DLFS views while unrelated venue traffic synchronises the root
	 * lattice and the UI lists the same directory. The field report was a fresh
	 * encrypted-v3 store whose directory nodes became unreadable without any
	 * reported write failure.
	 *
	 * <p>All ordering in this test is explicit: writers rendezvous before the
	 * concurrent sync loop starts, every future is joined, and verification runs
	 * only after the syncer has stopped. Concurrency is the behaviour under test,
	 * not a timing assumption.</p>
	 */
	@Test
	public void concurrentDlfsPromotionsRemainReadableAcrossSyncAndRestart() throws Exception {
		File dir = TestTemp.dir("etch-v3-dlfs-promotions").toFile();
		String storePath = new File(dir, "venue.etch").getAbsolutePath().replace('\\', '/');
		AString userDID = Strings.create("did:key:z6Mk-test-dlfs-promotions");
		String driveName = "drive";
		int writerCount = 6;
		int filesPerWriter = 20;
		Map<String, byte[]> expected = new LinkedHashMap<>();
		String commonPrefix = "2026-08-13 - NHS health document "; // >32 shared chars
		for (int writer = 0; writer < writerCount; writer++) {
			for (int item = 0; item < filesPerWriter; item++) {
				String name = commonPrefix + writer + "-" + item + ".pdf";
				expected.put(name, ("writer=" + writer + ",item=" + item)
					.getBytes(StandardCharsets.UTF_8));
			}
		}

		VenueServer first = VenueServer.launch(config(storePath, KEY_HEX));
		try {
			Engine engine = first.getEngine();
			DLFSAdapter dlfs = (DLFSAdapter) engine.getAdapter("dlfs");
			Path uploads = driveRoot(dlfs, userDID, driveName).resolve("Uploads");
			Files.createDirectories(uploads);

			CountDownLatch ready = new CountDownLatch(writerCount);
			CountDownLatch start = new CountDownLatch(1);
			AtomicBoolean syncing = new AtomicBoolean(true);
			try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
				var writers = new java.util.ArrayList<java.util.concurrent.Future<?>>();
				for (int writer = 0; writer < writerCount; writer++) {
					int writerId = writer;
					writers.add(tasks.submit(() -> {
						ready.countDown();
						start.await();
						for (int item = 0; item < filesPerWriter; item++) {
							String name = commonPrefix + writerId + "-" + item + ".pdf";
							byte[] content = expected.get(name);
							// Fresh cursor/filesystem per request, as in DLFSAdapter dispatch.
							Path freshUploads = driveRoot(dlfs, userDID, driveName).resolve("Uploads");
							Path target = freshUploads.resolve(name);
							Path staged = freshUploads.resolve(name + ".part");
							Files.write(staged, content);
							Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
							assertArrayEquals(content, Files.readAllBytes(target));
							try (var listing = Files.list(
									driveRoot(dlfs, userDID, driveName).resolve("Uploads"))) {
								listing.count();
							}
						}
						return null;
					}));
				}
				ready.await();
				var syncer = tasks.submit(() -> {
					start.countDown();
					while (syncing.get()) {
						engine.syncState();
						Thread.yield();
					}
					return null;
				});
				try {
					for (var writer : writers) writer.get();
				} finally {
					syncing.set(false);
				}
				syncer.get();
			}

			assertDlfsContents(dlfs, userDID, driveName, expected);
			engine.flush();
		} finally {
			first.close();
		}

		VenueServer reopened = VenueServer.launch(config(storePath, KEY_HEX));
		try {
			DLFSAdapter dlfs = (DLFSAdapter) reopened.getEngine().getAdapter("dlfs");
			assertDlfsContents(dlfs, userDID, driveName, expected);
		} finally {
			reopened.close();
		}
	}

	private static Path driveRoot(DLFSAdapter dlfs, AString userDID, String driveName) {
		return dlfs.getDriveForIdentity(userDID.toString(), driveName).getPath("/");
	}

	private static void assertDlfsContents(DLFSAdapter dlfs, AString userDID,
			String driveName, Map<String, byte[]> expected) throws Exception {
		Path uploads = driveRoot(dlfs, userDID, driveName).resolve("Uploads");
		try (var listing = Files.list(uploads)) {
			var paths = listing.toList();
			assertEquals(expected.size(), paths.size(),
				"every promoted sibling must remain reachable");
			assertTrue(paths.stream().noneMatch(path -> path.toString().endsWith(".part")),
				"no staged file may remain after promotion");
		}
		for (var entry : expected.entrySet()) {
			assertArrayEquals(entry.getValue(), Files.readAllBytes(uploads.resolve(entry.getKey())),
				"content mismatch for " + entry.getKey());
		}
	}
}
