package covia.venue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.venue.server.VenueServer;

/**
 * Child-JVM driver for hard-kill persistence tests. Started by
 * {@link HardKillPersistenceTest} via {@link ProcessBuilder}. Listens
 * for line-oriented protocol commands on stdin and writes protocol
 * responses to stdout, each prefixed with {@link #PROTO} so the parent
 * can ignore arbitrary log output that may share stdout.
 *
 * <p>Protocol (each command + response is a single line):</p>
 * <pre>
 *   parent → child                   child → parent
 *   --------------------------------  --------------------------------
 *   (after launch)                    PROTO READY &lt;port&gt;
 *   WRITE &lt;drive&gt; &lt;path&gt; &lt;content&gt;     PROTO OK | PROTO ERR &lt;msg&gt;
 *   WRITE_NOFLUSH &lt;drive&gt; &lt;path&gt; &lt;content&gt; PROTO OK | PROTO ERR &lt;msg&gt;
 *   FLUSH                             PROTO OK
 *   SLEEP &lt;ms&gt;                        PROTO OK
 *   HALT                              (no response — Runtime.halt)
 *   EXIT                              (no response — orderly close + exit 0)
 * </pre>
 *
 * <p>WRITE invokes {@code v/ops/dlfs/write} then calls {@code engine.flush()}
 * before responding. After OK, the value is durable on disk.</p>
 *
 * <p>WRITE_NOFLUSH invokes the write but does NOT flush — durability then
 * relies on the background sweep daemon.</p>
 *
 * <p>HALT calls {@link Runtime#halt(int)} which bypasses shutdown hooks and
 * simulates a process crash.</p>
 */
public class HardKillTestChild {

	private static final String PROTO = "PROTO";

	/** Stable test identity used for all writes from the child. */
	static final AString ALICE_DID = Strings.create("did:key:z6MkHardKillAlice");

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> buildConfig(String storePath, String seedHex) {
		return (AMap<AString, ACell>) (AMap<?, ?>) Maps.of(
			Fields.NAME, Strings.create("Hard-Kill Test Venue"),
			Strings.create("port"), 0,
			Config.STORE, Strings.create(storePath),
			Config.SEED, Strings.create(seedHex),
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, true))
		);
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("Usage: HardKillTestChild <storePath> <seedHex>");
			System.exit(2);
		}
		String storePath = args[0];
		String seedHex = args[1];

		PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

		VenueServer server;
		try {
			server = VenueServer.launch(buildConfig(storePath, seedHex));
		} catch (Exception e) {
			out.println(PROTO + " ERR launch " + e.getMessage());
			System.exit(3);
			return;
		}
		Engine engine = server.getEngine();

		out.println(PROTO + " READY " + server.port());

		BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		String line;
		while ((line = in.readLine()) != null) {
			try {
				if (line.startsWith("WRITE_NOFLUSH ")) {
					handleWrite(engine, line.substring("WRITE_NOFLUSH ".length()), false);
					out.println(PROTO + " OK");
				} else if (line.startsWith("WRITE ")) {
					handleWrite(engine, line.substring("WRITE ".length()), true);
					out.println(PROTO + " OK");
				} else if ("FLUSH".equals(line)) {
					engine.flush();
					out.println(PROTO + " OK");
				} else if (line.startsWith("SLEEP ")) {
					Thread.sleep(Long.parseLong(line.substring("SLEEP ".length()).trim()));
					out.println(PROTO + " OK");
				} else if ("HALT".equals(line)) {
					// No response — bypass shutdown hooks entirely.
					Runtime.getRuntime().halt(137);
				} else if ("EXIT".equals(line)) {
					server.close();
					System.exit(0);
				} else {
					out.println(PROTO + " ERR unknown_command " + line);
				}
			} catch (Exception e) {
				out.println(PROTO + " ERR " + e.getClass().getSimpleName() + " " + e.getMessage());
			}
		}
	}

	/**
	 * Parses "{@code <drive> <path> <content>}" (content may contain spaces),
	 * invokes the DLFS write operation, and optionally flushes.
	 */
	private static void handleWrite(Engine engine, String rest, boolean flush) throws Exception {
		String[] parts = rest.split(" ", 3);
		if (parts.length < 3) {
			throw new IllegalArgumentException("expected: <drive> <path> <content>");
		}
		String drive = parts[0];
		String path = parts[1];
		String content = parts[2];

		engine.jobs().invokeOperation(
			"v/ops/dlfs/write",
			Maps.of("drive", drive, "path", path, "content", content),
			RequestContext.of(ALICE_DID)
		).awaitResult(5000);

		if (flush) engine.flush();
	}
}
