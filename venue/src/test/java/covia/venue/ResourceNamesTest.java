package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AssetAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.CapabilityChecker;

/**
 * The rules for names of resources (GRID_LATTICE_DESIGN.md §3.2): the standard
 * functions, then that every route to a resource applies them alike.
 */
public class ResourceNamesTest {

	private static final String OWNER = "did:key:zOwner";
	private static final AString NAMESPACE = Strings.create(OWNER);
	private static final String HASH = "ab".repeat(32);
	private static final Hash PARSED = Hash.fromHex(HASH);

	private final Engine engine = TestEngine.ENGINE;
	private AKeyPair aliceKey, bobKey;
	private AString alice, bob;
	private RequestContext ALICE, BOB;

	@BeforeEach
	void setup() {
		aliceKey = AKeyPair.generate();
		bobKey = AKeyPair.generate();
		alice = UCAN.toDIDKey(aliceKey.getAccountKey());
		bob = UCAN.toDIDKey(bobKey.getAccountKey());
		ALICE = RequestContext.of(alice);
		BOB = RequestContext.of(bob);
		engine.getVenueState().users().ensure(alice);
		engine.getVenueState().users().ensure(bob);
	}

	private static AString s(String value) {
		return Strings.create(value);
	}

	private static String resolved(String name) {
		return CapabilityChecker.canonicalResource(name, NAMESPACE);
	}

	private static String stored(String name) {
		return CapabilityChecker.canonicalResource(name, null);
	}

	// ========== canonicalResource ==========

	@Test
	void aBareNameResolvesInItsNamespace() {
		assertEquals(OWNER + "/w/x", resolved("w/x"));
		assertEquals(OWNER + "/g/a/n/x", resolved("g/a/n/x"));
		assertEquals(OWNER + "/w/x/", resolved("w/x/"), "a trailing slash is kept");
	}

	@Test
	void leadingSlashesChangeNothing() {
		for (String name : new String[] {"w/x", "w/x/", "did:key:zOther/w/x", "file://root/x", "dlfs://drive/x"}) {
			assertEquals(resolved(name), resolved("/" + name), name);
			assertEquals(resolved(name), resolved("///" + name), name);
			assertEquals(stored(name), stored("/" + name), name);
		}
	}

	@Test
	void aLoneSlashIsTheNamespaceRoot() {
		assertEquals(OWNER + "/", resolved("/"));
		assertEquals(OWNER + "/", resolved("//"));
		assertEquals("/", stored("/"), "stored as written: it must not become the empty name");
	}

	@Test
	void theEmptyNameHasNoCanonicalForm() {
		assertEquals("", resolved(""));
		assertEquals("", stored(""));
		assertNull(resolved(null));
	}

	@Test
	void aDidUrlAndASchemeReferenceSayWhereTheyAre() {
		assertEquals("did:key:zOther/w/x", resolved("did:key:zOther/w/x"));
		assertEquals("did:key:zOther", resolved("did:key:zOther"));
		assertEquals("file://root/x", resolved("file://root/x"));
		assertEquals("file://", resolved("file://"));
	}

	@Test
	void aLegacyDriveShorthandIsTheDrivesPath() {
		assertEquals(OWNER + "/dlfs/drive/x", resolved("dlfs://drive/x"));
		assertEquals(OWNER + "/dlfs/drive/x", resolved("dlfs/drive/x"));
	}

	@Test
	void everythingElseInANameIsKeptAsWritten() {
		for (String name : new String[] {"w/a//b", "w/./a", "w/../a", "w/a%20b", "w/a#b", "w/a?b", "w/a://b", "w/did:key:z/x"}) {
			assertEquals(OWNER + "/" + name, resolved(name), name);
		}
	}

	@Test
	void aBareNameStaysBareWhenStored() {
		assertEquals("w/x/", stored("/w/x/"));
		assertEquals("n/", stored("/n/"));
		assertEquals("w/x", stored("w/x"));
	}

	// ========== isScheme ==========

	@Test
	void aSchemeReferenceIsNamedAtTheStart() {
		for (String name : new String[] {"file://r/x", "dlfs://d", "/file://r/x", "file://"}) {
			assertTrue(CapabilityChecker.isScheme(name), name);
		}
		for (String name : new String[] {"w/a://b", "file:/r/x", "did:key:z/x", "w/x", "", "/", "://x"}) {
			assertFalse(CapabilityChecker.isScheme(name), name);
		}
		assertFalse(CapabilityChecker.isScheme(null));
	}

	// ========== ownerOf ==========

	@Test
	void theOwnerIsTheDidAsWritten() {
		assertEquals(s("did:key:zA"), Engine.ownerOf(s("did:key:zA/w/x")));
		assertEquals(s("did:key:zA"), Engine.ownerOf(s("did:key:zA")));
		assertEquals(s("did:key:zA"), Engine.ownerOf(s("/did:key:zA/w/x")), "a leading slash changes nothing");
		assertEquals(s("did:web:example.com%3A8080:u:a"), Engine.ownerOf(s("did:web:example.com%3A8080:u:a/w/x")));
		assertEquals(s("did:key:%7AA"), Engine.ownerOf(s("did:key:%7AA/w/x")), "another spelling is not decoded");
	}

	@Test
	void aBareNameHasNoOwner() {
		for (String name : new String[] {"w/x", "/w/x", "", "/", "file://r/x"}) {
			assertNull(Engine.ownerOf(s(name)), name);
		}
		assertNull(Engine.ownerOf(null));
	}

	// ========== parseAssetId ==========

	@Test
	void anAssetIsNamedByItsHashInTheAssetNamespace() {
		for (String name : new String[] {HASH, "/" + HASH, "a/" + HASH, "/a/" + HASH, "//a/" + HASH, "a/" + HASH + "/",
				OWNER + "/a/" + HASH, "/" + OWNER + "/a/" + HASH}) {
			assertEquals(PARSED, AssetAdapter.parseAssetId(s(name)), name);
		}
	}

	@Test
	void nothingElseNamesAnAsset() {
		for (String name : new String[] {"w/a/" + HASH, OWNER + "/w/shared/a/" + HASH, OWNER + "/g/x/a/" + HASH,
				OWNER + "/" + HASH, "a/" + HASH + "/x", "a/not-a-hash", "w/x", "", "/"}) {
			assertNull(AssetAdapter.parseAssetId(s(name)), name);
		}
		assertNull(AssetAdapter.parseAssetId(null));
	}

	// ========== Secret references ==========

	@Test
	void aSecretIsNamedLikeAnyOtherResource() {
		for (String name : new String[] {"s/key", "/s/key", "//s/key", OWNER + "/s/key", "/" + OWNER + "/s/key"}) {
			assertTrue(Engine.isSecretRef(name), name);
		}
		for (String name : new String[] {"w/key", "key", "", OWNER}) {
			assertFalse(Engine.isSecretRef(name), name);
		}
		assertEquals("key", Engine.secretName("s/key"));
		assertEquals("key", Engine.secretName("//s/key"));
		assertEquals("key", Engine.secretName("key"));
	}

	// ========== Every route ==========

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		try {
			job.awaitResult(10_000);
		} catch (Exception refused) {
			// The job's status says what happened.
		}
		return job;
	}

	private static void assertRefused(Job job) {
		assertEquals(Status.FAILED, job.getStatus());
	}

	private void complete(String op, ACell input, RequestContext ctx) {
		Job job = run(op, input, ctx);
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
	}

	private ACell value(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job.getErrorMessage());
		return RT.getIn(job.getOutput(), Fields.VALUE);
	}

	private ACell cap(String with, AString can) {
		return Capability.create(s(with), can);
	}

	/** Bob under Alice's hour-long grant of {@code caps}. */
	private RequestContext bobGranted(ACell... caps) {
		return BOB.withProofs(Vectors.of(UCAN.create(aliceKey, bobKey.getAccountKey(),
			System.currentTimeMillis() / 1000 + 3600, Vectors.of((Object[]) caps), Vectors.empty()).toMap()));
	}

	private String storeAsset() {
		Job stored = run("v/ops/asset/store", Maps.of("metadata", Maps.of("name", "private")), ALICE);
		String id = RT.getIn(stored.getOutput(), Fields.ID).toString();
		return id.substring(id.lastIndexOf('/') + 1);
	}

	@Test
	void aRecordIsNeverReadWhole() {
		complete("v/ops/covia/write", Maps.of(Fields.PATH, "w/doc", Fields.VALUE, Maps.of("k", "v")), ALICE);
		for (ACell path : new ACell[] {s(""), s("/"), s("//"), alice, s("/" + alice), s(alice + "/"), Vectors.empty(), Vectors.of(alice), CVMLong.ONE}) {
			for (String op : new String[] {"read", "list", "slice", "aggregate"}) {
				Job job = run("v/ops/covia/" + op, Maps.of(Fields.PATH, path), ALICE);
				assertEquals(Status.FAILED, job.getStatus(), op + " " + path);
				assertEquals(Engine.USER_PATH_NO_NAMESPACE, job.getErrorMessage(), op + " " + path);
			}
		}
		for (String name : new String[] {"", "/", alice.toString()}) {
			Job inspected = run("v/ops/covia/inspect", Maps.of(Fields.PATH, name), ALICE);
			assertEquals(Status.FAILED, inspected.getStatus(), name);
			assertEquals(Engine.USER_PATH_NO_NAMESPACE, inspected.getErrorMessage(), name);
			assertNull(engine.resolvePath(s(name), ALICE), name);
			assertNull(RT.getIn(run("v/ops/asset/get", Maps.of(Fields.ID, name), ALICE).getOutput(), Fields.VALUE), name);
		}
		assertNotNull(value(run("v/ops/covia/read", Maps.of(Fields.PATH, "w"), ALICE)), "a namespace is a place");
	}

	@Test
	void everyWayOfWritingAPathReachesTheSamePlaceOnEveryRoute() {
		ACell held = Maps.of("k", "v");
		complete("v/ops/covia/write", Maps.of(Fields.PATH, "w/doc", Fields.VALUE, held), ALICE);
		for (String name : new String[] {"w/doc", "/w/doc", "//w/doc", "w/doc/", alice + "/w/doc", "/" + alice + "/w/doc"}) {
			assertEquals(held, value(run("v/ops/covia/read", Maps.of(Fields.PATH, name), ALICE)), name);
			assertEquals(held, value(run("v/ops/asset/get", Maps.of(Fields.ID, name), ALICE)), name);
			assertEquals(held, engine.resolvePath(s(name), ALICE), name);
		}
	}

	@Test
	void aPathAfterADidIsReadAsABarePathIs() {
		ACell held = Strings.create("kept");
		complete("v/ops/covia/write", Maps.of(Fields.PATH, alice + "/w/a#b", Fields.VALUE, held), ALICE);
		assertEquals(held, value(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/a#b"), ALICE)));
		assertNull(RT.getIn(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/a"), ALICE).getOutput(), Fields.VALUE),
			"the fragment is part of the key");
	}

	@Test
	void everyWayOfWritingAGrantCoversTheSameResources() {
		for (String with : new String[] {"w/", "/w/", "//w/", "w", alice + "/w/", "/" + alice + "/w/"}) {
			RequestContext scoped = ALICE.withCaps(Vectors.of(cap(with, Capability.CRUD_READ)));
			for (String name : new String[] {"w/x", "/w/x", alice + "/w/x", "/" + alice + "/w/x"}) {
				assertTrue(engine.authorityCovers(scoped, s(name), Capability.CRUD_READ), with + " covers " + name);
			}
			assertFalse(engine.authorityCovers(scoped, s("wx/y"), Capability.CRUD_READ), with);
			assertFalse(engine.authorityCovers(scoped, s("w/x"), Capability.CRUD_WRITE), with);
		}
	}

	@Test
	void aLoneSlashGrantCoversItsNamespaceOnly() {
		RequestContext scoped = ALICE.withCaps(Vectors.of(cap("/", Capability.CRUD_READ)));
		assertTrue(engine.authorityCovers(scoped, s("w/x"), Capability.CRUD_READ));
		assertFalse(engine.authorityCovers(scoped, s(bob + "/w/x"), Capability.CRUD_READ));
	}

	@Test
	void aSignedTokenReadsItsGrantsTheSameWay() {
		RequestContext granted = bobGranted(cap("/w/shared/", Capability.CRUD_READ));
		assertTrue(engine.authorityCovers(granted, s(alice + "/w/shared/doc"), Capability.CRUD_READ));
		assertFalse(engine.authorityCovers(granted, s(alice + "/w/other"), Capability.CRUD_READ));
	}

	@Test
	void aWithThatIsNotAStringGrantsNothing() {
		RequestContext scoped = ALICE.withCaps(Vectors.of(Maps.of(Capability.WITH, 42L, Capability.CAN, Capability.CRUD_READ)));
		assertFalse(engine.authorityCovers(scoped, s("w/x"), Capability.CRUD_READ));
	}

	@Test
	void theCheckAndTheReadSeeTheSameOwner() {
		String hash = storeAsset();
		String respelled = alice.toString().replaceFirst("did:key:z", "did:key:%7A") + "/a/" + hash;
		RequestContext unscoped = BOB.withCaps(Vectors.of(cap("", Abilities.ASSET_READ)));
		assertNull(engine.resolvePathUnchecked(s(respelled), unscoped), "another spelling names nobody here");
		assertRefused(run("v/ops/asset/get", Maps.of(Fields.ID, alice + "/a/" + hash), unscoped));
	}

	@Test
	void aGrantOverASubtreeReachesOnlyThatSubtree() {
		String hash = storeAsset();
		RequestContext granted = bobGranted(cap(alice + "/w/shared/", Abilities.ASSET_READ));
		assertRefused(run("v/ops/asset/get", Maps.of(Fields.ID, alice + "/a/" + hash), granted));
		assertRefused(run("v/ops/asset/get", Maps.of(Fields.ID, alice + "/w/shared/a/" + hash), granted));
	}

	@Test
	void anAssetIsNamedTheSameWithALeadingSlash() {
		String hash = storeAsset();
		RequestContext scoped = ALICE.withCaps(Vectors.of(cap("a/", Abilities.ASSET_READ)));
		assertNotNull(value(run("v/ops/asset/get", Maps.of(Fields.ID, "a/" + hash), scoped)));
		assertNotNull(value(run("v/ops/asset/get", Maps.of(Fields.ID, "/a/" + hash), scoped)));
	}

	@Test
	void aPathThroughTheAssetOperationsIsCheckedAsAPath() {
		complete("v/ops/covia/write", Maps.of(Fields.PATH, "w/private", Fields.VALUE, Maps.of("k", "v")), ALICE);
		RequestContext assetReader = ALICE.withCaps(Vectors.of(cap("", Abilities.ASSET_READ)));
		assertRefused(run("v/ops/asset/get", Maps.of(Fields.ID, "w/private"), assetReader));
		assertRefused(run("v/ops/covia/read", Maps.of(Fields.PATH, "w/private"), assetReader));
		RequestContext reader = ALICE.withCaps(Vectors.of(cap("w/", Capability.CRUD_READ)));
		assertNotNull(value(run("v/ops/asset/get", Maps.of(Fields.ID, "w/private"), reader)));
	}

	@Test
	void anAgentsOwnNamesResolveOnEveryRoute() {
		AString id = s("notes-agent");
		engine.getVenueState().users().ensure(alice).ensureAgent(id,
			Maps.of("caps", Vectors.of(cap("n/", Capability.TOP))), null);
		RequestContext step = engine.stepContext(ALICE, id);
		ACell held = Maps.of("k", "v");
		complete("v/ops/covia/write", Maps.of(Fields.PATH, "n/m", Fields.VALUE, held), step);
		assertEquals(held, value(run("v/ops/covia/read", Maps.of(Fields.PATH, "n/m"), step)));
		assertEquals(held, value(run("v/ops/asset/get", Maps.of(Fields.ID, "n/m"), step)));
		assertEquals(held, value(run("v/ops/asset/get", Maps.of(Fields.ID, "/n/m"), step)));
	}

	@Test
	void anOperationIsNamedTheSameWithALeadingSlash() {
		RequestContext scoped = ALICE.withCaps(Vectors.of(cap("v/test/ops/", Abilities.INVOKE)));
		for (String op : new String[] {"v/test/ops/echo", "/v/test/ops/echo"}) {
			assertTrue(engine.authorityCovers(scoped, s(op), Abilities.INVOKE), op);
		}
	}

	@Test
	void aCapabilityIsStoredWithoutItsLeadingSlash() {
		AString id = s("stored-caps");
		complete("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id, Fields.CONFIG, Maps.of(
			Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm",
			"caps", Vectors.of(cap("/w/x/", Capability.CRUD_READ), cap("w/y/", Capability.CRUD_READ)))), ALICE);
		AMap<AString, ACell> config = engine.getAgent(alice, id).getConfig();
		assertEquals(Vectors.of(cap("w/x/", Capability.CRUD_READ), cap("w/y/", Capability.CRUD_READ)), RT.getIn(config, "caps"));
		assertEquals(Principals.agentDID(alice, id), engine.context(alice, Principals.agentDID(alice, id)).getCallerDID());
	}
}
