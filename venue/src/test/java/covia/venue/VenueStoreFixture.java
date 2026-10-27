package covia.venue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

/**
 * Seeds an authentic old-release store through authenticated HTTP APIs.
 * Deliberately uses only APIs present in every release in {@link #RELEASES},
 * so Java source-file mode can run this file against each released jar,
 * without loading current venue code.
 * All identities/secrets are public test data. Bind only to loopback.
 */
public final class VenueStoreFixture {

	public static final String AGENT = "migration-helper";
	public static final String SECRET = "MIGRATION_TEST_ONLY";
	public static final String VENUE_SEED = "42".repeat(32);
	public static final String FILE_TEXT = "Migration fixture: café, 日本語, 🌍\n".repeat(128);
	public static final String ASSET_TEXT = "Immutable migration fixture content\n";
	/** The published covia.jar SHA-256 of each release that wrote a fixture, one per release line. */
	public static final Map<String, String> RELEASES = Map.of(
		"0.9.11", "c5975145ff860c2d6d893ecfa832cdb9a7e532d71929f06a4d9b335d47269530",
		"0.10.0", "f0f4a4f369ac22482e414d1c0e5d64bd345e58eaeaa064c0f0b86118321eefc1");

	public static AKeyPair key(String hexByte) {
		return AKeyPair.create(Blob.fromHex(hexByte.repeat(32)));
	}

	public static AMap<AString, ACell> config(Path store) {
		return Maps.of("name", "Public-data migration test venue", "port", 0,
			"bindAddress", "127.0.0.1", "store", store.toAbsolutePath().toString(),
			"seed", VENUE_SEED, "users", Maps.of("autoCreate", true),
			"rateLimit", Maps.of("enabled", false),
			"auth", Maps.of("public", Maps.of("enabled", false)));
	}

	public static VenueHTTP client(VenueServer server, String keyByte) {
		VenueHTTP client = VenueHTTP.create(URI.create("http://127.0.0.1:" + server.port()),
			VenueAuth.keyPair(key(keyByte), server.getEngine().getDIDString().toString()));
		client.setTimeout(20_000);
		return client;
	}

	public static ACell run(VenueHTTP client, String operation, ACell input) throws Exception {
		return client.run("v/ops/" + operation, input).get(20, TimeUnit.SECONDS);
	}

	public static ACell read(VenueHTTP client, String path) throws Exception {
		return RT.getIn(run(client, "covia/read", Maps.of("path", path)), "value");
	}

	public static void write(VenueHTTP client, String path, ACell value) throws Exception {
		run(client, "covia/write", Maps.of("path", path, "value", value));
	}

	public static void equal(Object expected, Object actual) {
		if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
	}

	public static void require(boolean condition, String reason) {
		if (!condition) throw new AssertionError(reason);
	}

	public static String text(ACell cell) {
		if (cell == null) throw new AssertionError("Expected a value");
		return cell.toString();
	}

	public static String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (var in = Files.newInputStream(path)) {
			byte[] buffer = new byte[64 * 1024];
			for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/** The fixed expected values are independent of whichever build verifies them. */
	private static ACell profile(String name) {
		return Maps.of("name", name, "unicode", "café 日本語 🌍", "nullable", null,
			"nested", Maps.of("enabled", true, "values", Vectors.of(1L, 2L, 3L)),
			"emptyMap", Maps.empty(), "emptyVector", Vectors.empty());
	}

	private static AMap<AString, ACell> seedUser(VenueServer server, String name, String keyByte, String release) throws Exception {
		VenueHTTP client = client(server, keyByte);
		String did = VenueAuth.keyPair(key(keyByte)).getDID();
		write(client, "w/profile", profile(name));
		write(client, "w/events", Vectors.of("baseline"));
		write(client, "w/stored-null", null);
		write(client, "o/fixture-echo", Maps.of("name", "Fixture echo", "operation", Maps.of("adapter", "test:echo")));
		run(client, "secret/set", Maps.of("name", SECRET, "value", "test-only-" + name));
		run(client, "dlfs/create-drive", Maps.of("name", "documents"));
		run(client, "dlfs/write", Maps.of("drive", "documents", "path", "/notes.txt", "content", FILE_TEXT + name));
		run(client, "dlfs/write", Maps.of("drive", "documents", "path", "/bytes.bin", "bytes", "AAECA/7/"));
		ACell asset = run(client, "asset/store", Maps.of("metadata", Maps.of("name", "Fixture " + name), "contentText", ASSET_TEXT));
		Job job = client.invokeAndWait(Strings.create("v/test/ops/echo"), Maps.of("owner", name), 20_000);
		equal(Maps.of("owner", name), job.awaitResult(20_000));
		ACell jobRecord = client.getJobData(job.getID()).get(20, TimeUnit.SECONDS);
		// 0.9.11 wrote the pre-#611 history chain; 0.10.0 writes current state only.
		boolean legacyJob = release.equals("0.9.11");
		require((RT.getIn(jobRecord, "prev") != null) == legacyJob, "Job history must be in the form " + release + " wrote");
		run(client, "agent/create", Maps.of("agentId", AGENT, "config", Maps.of(
			"operation", "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm",
			"tools", Vectors.of("v/test/ops/echo"), "systemPrompt", "Deterministic migration fixture")));
		ACell chat = run(client, "agent/chat", Maps.of("agentId", AGENT, "message", "baseline message for " + name));
		String session = text(RT.getIn(chat, "sessionId"));
		require(text(RT.getIn(chat, "response")).contains("baseline message for " + name), "Mock chat must answer");
		run(client, "agent/rename-session", Maps.of("agentId", AGENT, "sessionId", session, "title", "Baseline " + name));
		return Maps.of("name", name, "keyByte", keyByte, "did", did,
			"sessionId", session, "jobId", job.getID().toHexString(),
			"assetRef", RT.getIn(asset, "ref"), "legacyJobHasPrev", legacyJob);
	}

	/** Read through public APIs, including decryption and conversation rendering. */
	public static void verifyUser(VenueServer server, ACell user, boolean updated) throws Exception {
		String name = text(RT.getIn(user, "name"));
		VenueHTTP client = client(server, text(RT.getIn(user, "keyByte")));
		equal(profile(name), read(client, "w/profile"));
		equal(updated ? Vectors.of("baseline", "upgraded") : Vectors.of("baseline"), read(client, "w/events"));
		equal("true", text(RT.getIn(run(client, "covia/read", Maps.of("path", "w/stored-null")), "exists")));
		equal(null, read(client, "w/stored-null"));
		equal(Maps.of("owner", name), client.run("o/fixture-echo", Maps.of("owner", name)).get(20, TimeUnit.SECONDS));
		equal("test-only-" + name + (updated ? "-updated" : ""), text(RT.getIn(run(client, "secret/extract", Maps.of("name", SECRET)), "value")));
		equal(FILE_TEXT + name + (updated ? "\nupdated" : ""), text(RT.getIn(run(client, "dlfs/read", Maps.of("drive", "documents", "path", "/notes.txt", "mode", "text")), "content")));
		equal("AAECA/7/", text(RT.getIn(run(client, "dlfs/read", Maps.of("drive", "documents", "path", "/bytes.bin", "mode", "bytes")), "content")));
		ACell content = RT.getIn(run(client, "asset/content", Maps.of("ref", RT.getIn(user, "assetRef"))), "value");
		equal(Blob.wrap(ASSET_TEXT.getBytes(StandardCharsets.UTF_8)), Blob.parse(content));
		ACell record = client.getJobData(text(RT.getIn(user, "jobId"))).get(20, TimeUnit.SECONDS);
		equal("COMPLETE", text(RT.getIn(record, "status")));
		equal(Maps.of("owner", name), RT.getIn(record, "output"));
		ACell history = run(client, "agent/session-read", Maps.of("agentId", AGENT,
			"sessionId", RT.getIn(user, "sessionId"), "maxChars", 20000));
		require(text(history).contains("baseline message for " + name), "Stored conversation missing for " + name);
		equal("Baseline " + name, text(RT.getIn(history, "title")));
		if (updated) require(text(history).contains("upgraded message for " + name), "Continued conversation missing");
	}

	public static void updateUser(VenueServer server, ACell user) throws Exception {
		String name = text(RT.getIn(user, "name"));
		VenueHTTP client = client(server, text(RT.getIn(user, "keyByte")));
		run(client, "covia/append", Maps.of("path", "w/events", "value", "upgraded"));
		run(client, "covia/copy", Maps.of("from", "w/profile", "to", "w/profile-copy"));
		equal(read(client, "w/profile"), read(client, "w/profile-copy"));
		run(client, "covia/delete", Maps.of("path", "w/profile-copy"));
		equal(null, read(client, "w/profile-copy"));
		run(client, "secret/set", Maps.of("name", SECRET, "value", "test-only-" + name + "-updated", "overwrite", true));
		run(client, "dlfs/write", Maps.of("drive", "documents", "path", "/notes.txt", "content", FILE_TEXT + name + "\nupdated"));
		ACell chat = run(client, "agent/chat", Maps.of("agentId", AGENT,
			"sessionId", RT.getIn(user, "sessionId"), "message", "upgraded message for " + name));
		equal(RT.getIn(user, "sessionId"), RT.getIn(chat, "sessionId"));
		require(text(RT.getIn(chat, "response")).contains("upgraded message for " + name), "Continued mock chat must answer");
	}

	/** Seed only with the verified old release; verify opens a disposable copy with the selected jar. */
	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			throw new IllegalArgumentException("Usage: VenueStoreFixture.java <seed|verify|verify-updated> <fixture directory>");
		}
		if (args[0].equals("verify") || args[0].equals("verify-updated")) {
			Path directory = Path.of(args[1]).toAbsolutePath();
			ACell manifest = JSON.parse(Files.readString(directory.resolve("manifest.json")));
			VenueServer server = VenueServer.launch(config(directory.resolve("venue.etch")));
			try {
				equal(RT.getIn(manifest, "venueDID"), server.getEngine().getDIDString());
				for (ACell user : RT.ensureVector(RT.getIn(manifest, "users"))) {
					verifyUser(server, user, args[0].equals("verify-updated"));
				}
			} finally {
				server.close();
			}
			System.out.println("FIXTURE VERIFIED " + directory);
			return;
		}
		if (!args[0].equals("seed")) throw new IllegalArgumentException("Unknown fixture command: " + args[0]);
		Path jar = Path.of(VenueServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		String jarSha256 = sha256(jar);
		String release = RELEASES.entrySet().stream().filter(e -> e.getValue().equals(jarSha256))
			.map(Map.Entry::getKey).findFirst()
			.orElseThrow(() -> new AssertionError("Not a published release jar: " + jar + " sha256=" + jarSha256));
		Path directory = Path.of(args[1]).toAbsolutePath();
		Files.createDirectory(directory);
		Path store = directory.resolve("venue.etch");
		AMap<AString, ACell> manifest;
		VenueServer server = VenueServer.launch(config(store));
		try {
			ACell alice = seedUser(server, "alice", "11", release);
			ACell bob = seedUser(server, "bob", "22", release);
			// The test adapter owns a synthetic scratch value of Alice's; no bot is
			// started. 0.9.11 kept it at the legacy w/adapters route, written via
			// the venue user; 0.10.0 keeps it in the adapter's own state, at x/.
			ACell scratch = Maps.of("origin", release);
			if (release.equals("0.9.11")) {
				write(client(server, "42"), "w/adapters/test", Maps.of("users", Maps.of(
					RT.getIn(alice, "did"), Maps.of("scratch", scratch))));
			} else {
				write(client(server, "11"), "x/test/scratch", scratch);
				equal(scratch, read(client(server, "11"), "x/test/scratch"));
			}
			verifyUser(server, alice, false);
			verifyUser(server, bob, false);
			manifest = Maps.of("fixtureVersion", 1L, "coviaVersion", release,
				"sourceJarSha256", jarSha256, "venueSeed", VENUE_SEED,
				"venueDID", server.getEngine().getDIDString(), "users", Vectors.of(alice, bob));
			server.getEngine().flush();
		} finally {
			server.close();
		}
		// A separate old-release open proves the baseline was durably written.
		server = VenueServer.launch(config(store));
		try {
			for (ACell user : RT.ensureVector(manifest.get(Strings.create("users")))) verifyUser(server, user, false);
		} finally {
			server.close();
		}
		manifest = manifest.assoc(Strings.create("storeSha256"), Strings.create(sha256(store)));
		Files.writeString(directory.resolve("manifest.json"), JSON.toString(manifest), StandardCharsets.UTF_8);
		Path archive = archive(directory, release);
		System.out.println("FIXTURE OK " + archive + " sha256=" + sha256(store));
	}

	/** The fixture archive, {@code covia-<release>.zip}: the manifest and the store, as the tests unpack it. */
	private static Path archive(Path directory, String release) throws Exception {
		Path archive = directory.resolve("covia-" + release + ".zip");
		try (var out = new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW))) {
			for (String name : new String[] { "manifest.json", "venue.etch" }) {
				out.putNextEntry(new ZipEntry(name));
				Files.copy(directory.resolve(name), out);
				out.closeEntry();
			}
		}
		return archive;
	}
}
