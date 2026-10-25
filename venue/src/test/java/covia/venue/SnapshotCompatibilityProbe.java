package covia.venue;

import java.nio.file.Files;
import java.util.Objects;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.DLFSAdapter;
import covia.venue.server.VenueServer;

/**
 * Cross-version store rehearsal. Compile against the baseline jar, then run the
 * same class with each jar: {@code <store> <expected-marker-or-dash> <new-marker>}.
 * Uses a disposable store and a public test seed; never point this at a real venue.
 */
public final class SnapshotCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        var key = AKeyPair.create(Blob.fromHex("42".repeat(32)));
        var did = Strings.create("did:key:snapshot-rehearsal");
        var server = VenueServer.launch(Maps.of(
            Config.STORE, args[0], Config.SEED, key.getSeed().toHexString(),
            "port", 0, Config.USERS, Maps.of(Config.AUTO_CREATE, true)));
        try {
            var engine = server.getEngine();
            var ctx = RequestContext.of(did);
            var user = engine.getVenueState().users().ensure(did);
            var secrets = SecretStore.keys(key);
            var drive = ((DLFSAdapter) engine.getAdapter("dlfs"))
                .getDriveForIdentity(did.toString(), "rehearsal");
            if (!"-".equals(args[1])) {
                equal(args[1], RT.getIn(run(engine, ctx, "covia/read",
                    Maps.of("path", "w/rehearsal")), "value").toString());
                equal(args[1], user.secrets().decrypt("rehearsal", secrets).toString());
                equal(args[1], Files.readString(drive.getPath("/marker.txt")));
                equal(args[1], RT.getIn(user.agent("rehearsal").getRecord(), "config", "marker").toString());
                if (user.getJobs().isEmpty()) throw new AssertionError("Persisted jobs missing");
            }
            run(engine, ctx, "covia/write", Maps.of("path", "w/rehearsal", "value", args[2]));
            user.secrets().store("rehearsal", args[2], secrets);
            Files.writeString(drive.getPath("/marker.txt"), args[2]);
            user.removeAgent(Strings.create("rehearsal"));
            user.ensureAgent("rehearsal", Maps.of("marker", args[2]), null);
            engine.flush();
            System.out.println("REHEARSAL OK " + args[1] + " -> " + args[2]);
        } finally {
            server.close();
        }
    }

    private static ACell run(Engine engine, RequestContext ctx, String operation, ACell input) {
        return engine.jobs().invokeOperation("v/ops/" + operation, input, ctx).awaitResult(5000);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
