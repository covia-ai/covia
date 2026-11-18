package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.json.schema.JsonSchema;
import convex.core.lang.RT;
import covia.adapter.messaging.MessagingBotSpec;
import covia.adapter.messaging.MessagingSettings;
import covia.api.Fields;
import covia.exception.InvalidInputException;
import covia.grid.Job;
import covia.grid.Principals;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;

/**
 * Users of the venue in short form (AUTHORITY.md, "Where names resolve"):
 * {@code u:bob} for {@code <webDID>:u:bob}, expanded at each kind of entry
 * point, recorded in full, and refused by a venue with no public hostname.
 * The shared engine has a public hostname; the shared test server has none.
 */
public class ShortFormTest {

	private final Engine engine = TestEngine.ENGINE;

	private static AString s(String value) {
		return Strings.create(value);
	}

	/** A managed user of the shared engine, with the short form that names it. */
	private record User(AString did, String shortForm) {
		RequestContext ctx() {
			return RequestContext.of(did);
		}
	}

	private User user(String discriminator) {
		AString did = TestEngine.uniqueManagedDID(discriminator);
		return new User(did, "u:" + engine.managedUserName(did));
	}

	private static Job run(Engine venue, String op, ACell input, RequestContext ctx) {
		Job job = venue.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		return job;
	}

	private Job run(String op, ACell input, RequestContext ctx) {
		return run(engine, op, input, ctx);
	}

	private static ACell complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job.getOutput();
	}

	private static String failure(Job job) {
		assertEquals(Status.FAILED, job.getStatus(), () -> "expected a refusal, got " + job.getData());
		return job.getErrorMessage();
	}

	private static AMap<AString, ACell> write(Object path, Object value) {
		return Maps.of(Fields.PATH, path, Fields.VALUE, value);
	}

	private static AMap<AString, ACell> read(Object path) {
		return Maps.of(Fields.PATH, path);
	}

	// ========== The expansion ==========

	@Test
	void aShortFormNamesAUserUnderTheWebDID() {
		AString bob = engine.managedUserDID(s("bob"));
		assertEquals(bob, engine.expandPrincipal(s("u:bob")));
		assertEquals(Principals.agentDID(bob, s("helper")), engine.expandPrincipal(s("u:bob:g:helper")));
		assertEquals(s(bob + "/w/notes"), engine.expandPath(s("u:bob/w/notes")));
		assertEquals(s(bob + "/w/notes"), engine.expandPath(s("/u:bob/w/notes")), "a leading slash changes nothing");
		assertEquals(bob, engine.expandPath(s("u:bob")), "a principal alone is a path, as a capability names it");
	}

	@Test
	void everythingElseIsLeftAsGiven() {
		for (String given : new String[] {"did:key:z6MkOther", "did:key:z6MkOther/w/notes", "w/notes",
				"venue", "anyone", "w/u:bob", "file://root/u:bob"}) {
			AString value = s(given);
			assertSame(value, engine.expandPrincipal(value), given);
			assertSame(value, engine.expandPath(value), given);
		}
		assertNull(engine.expandPrincipal(null));
		assertNull(engine.expandPath(null));
	}

	@Test
	void aValueThatStartsLikeAShortFormButIsNoneIsRefused() {
		for (String given : new String[] {"u:", "u:b@d", "u:bob:x:y", "u:bob:g:"}) {
			InvalidInputException refused = assertThrows(InvalidInputException.class,
				() -> engine.expandPrincipal(s(given)), given);
			assertTrue(refused.getMessage().startsWith(Engine.NOT_A_SHORT_FORM), refused.getMessage());
		}
	}

	@Test
	void aVenueWithNoPublicHostnameRefusesTheForm(TestInfo info) {
		Engine plain = TestServer.ENGINE;
		assertNull(plain.config().getWebDID(), "the shared test server has no public hostname");
		for (String given : new String[] {"u:bob", "u:bob:g:helper"}) {
			InvalidInputException refused = assertThrows(InvalidInputException.class,
				() -> plain.expandPrincipal(s(given)), given);
			assertTrue(refused.getMessage().startsWith(Engine.NO_SHORT_FORM), refused.getMessage());
		}
		InvalidInputException refused = assertThrows(InvalidInputException.class, () -> plain.expandPath(s("u:bob/w/notes")));
		assertTrue(refused.getMessage().startsWith(Engine.NO_SHORT_FORM), refused.getMessage());

		// At an entry point, where a full DID works as before
		RequestContext caller = RequestContext.of(TestEngine.uniqueDID(info));
		complete(run(plain, "v/ops/covia/write", write(caller.getUserDID() + "/w/notes", "kept"), caller));
		String failed = failure(run(plain, "v/ops/covia/read", read("u:bob/w/notes"), caller));
		assertTrue(failed.startsWith(Engine.NO_SHORT_FORM), failed);
	}

	// ========== Paths ==========

	@Test
	void aPathInShortFormIsTheUsersPath(TestInfo info) {
		User bob = user("sf-path");
		complete(run("v/ops/covia/write", write(bob.shortForm() + "/w/notes", "hello"), bob.ctx()));
		AString full = s(bob.did() + "/w/notes");
		assertEquals(s("hello"), RT.getIn(complete(run("v/ops/covia/read", read(full), bob.ctx())), Fields.VALUE));
		assertEquals(s("hello"), RT.getIn(complete(run("v/ops/covia/read",
			read(Vectors.of(bob.shortForm(), "w", "notes")), bob.ctx())), Fields.VALUE), "a path array's first segment");

		// Another user is refused exactly as for the full path
		RequestContext alice = RequestContext.of(TestEngine.uniqueDID(info));
		assertEquals(failure(run("v/ops/covia/read", read(full), alice)),
			failure(run("v/ops/covia/read", read(bob.shortForm() + "/w/notes"), alice)));
	}

	@Test
	void anOperationReferenceInShortFormIsRecordedInFull() {
		User bob = user("sf-op");
		complete(run("v/ops/covia/copy", Maps.of(Fields.FROM, "v/test/ops/echo", Fields.TO, "o/echo"), bob.ctx()));
		Job echoed = run(bob.shortForm() + "/o/echo", Maps.of(Fields.VALUE, "hi"), bob.ctx());
		complete(echoed);
		assertEquals(s(bob.did() + "/o/echo"), echoed.getData().get(Fields.OP), "the job names the operation in full");
	}

	@Test
	void aSecretReferenceInShortFormIsTheUsersSecret() {
		User bob = user("sf-secret");
		complete(run("v/ops/secret/set", Maps.of(Fields.NAME, "KEY", Fields.VALUE, "sesame"), bob.ctx()));
		String reference = bob.shortForm() + "/s/KEY";
		assertTrue(Engine.isSecretRef(reference));
		assertEquals("sesame", engine.resolveSecret(reference, bob.ctx()));
	}

	@Test
	void aCallableReferenceAndItsCapsInShortFormAreTheUsers() {
		User bob = user("sf-callable");
		ACell created = complete(run("v/ops/callable/create", Maps.of(Fields.NAME, "echo",
			Fields.OPERATION, "v/test/ops/echo",
			Fields.CAPS, Vectors.of(Capability.create(s(bob.shortForm() + "/w/notes"), Capability.CRUD_READ))), bob.ctx()));
		assertEquals(s(bob.did() + "/w/notes"), RT.getIn(created, Fields.CAPS, 0, Capability.WITH),
			"the callable records its caps in full");
		ACell called = complete(run("v/ops/callable/call", Maps.of(Fields.CALLABLE, bob.shortForm() + "/x/callable/echo",
			Fields.INPUT, Maps.of(Fields.VALUE, "hi")), bob.ctx()));
		assertEquals(s("hi"), RT.getIn(called, Fields.VALUE));
	}

	@Test
	void aPresentedGrantNamingAShortFormCoversNothing(TestInfo info) {
		User carol = user("sf-grant");
		complete(run("v/ops/covia/write", write("w/x", "carol's"), carol.ctx()));
		AKeyPair bobKey = AKeyPair.generate();
		RequestContext bob = RequestContext.of(UCAN.toDIDKey(bobKey.getAccountKey()));
		long exp = System.currentTimeMillis() / 1000 + 3600;
		AMap<AString, ACell> read = read(carol.did() + "/w/x");

		// The venue roots grants over what it hosts: in full, the grant covers the read
		complete(run("v/ops/covia/read", read, bob.withProofs(Vectors.of(grant(bobKey, carol.did() + "/w/x", exp)))));
		// In short form it is refused as a grant over another path is
		String uncovered = failure(run("v/ops/covia/read", read,
			bob.withProofs(Vectors.of(grant(bobKey, carol.did() + "/w/elsewhere", exp)))));
		assertEquals(uncovered, failure(run("v/ops/covia/read", read,
			bob.withProofs(Vectors.of(grant(bobKey, carol.shortForm() + "/w/x", exp))))));
	}

	/** A grant the venue signs to {@code audience} over {@code with}, as presented. */
	private AMap<AString, ACell> grant(AKeyPair audience, String with, long exp) {
		return UCAN.create(engine.getKeyPair(), audience.getAccountKey(), exp,
			Vectors.of(Capability.create(s(with), Capability.CRUD_READ)), Vectors.empty()).toMap();
	}

	// ========== Principals ==========

	@Test
	void anAgentsPrincipalsAndCapsInShortFormAreRecordedInFull() {
		User bob = user("sf-owner");
		User alice = user("sf-asker");
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "helper", Fields.CONFIG, Maps.of(
			Fields.OPERATION, "v/test/ops/echo",
			Fields.ACCEPTS, Vectors.of(alice.shortForm()),
			Fields.CAPS, Vectors.of(Capability.create(s(bob.shortForm() + "/w/notes"), Capability.CRUD_READ)))), bob.ctx()));
		AMap<AString, ACell> config = engine.getAgent(bob.did(), s("helper")).getConfig();
		assertEquals(Vectors.of(alice.did()), config.get(Fields.ACCEPTS));
		assertEquals(s(bob.did() + "/w/notes"), RT.getIn(config, Fields.CAPS, 0, Capability.WITH));

		// The agent's reference in short form names it
		ACell info = complete(run("v/ops/agent/info", Maps.of(Fields.AGENT_ID, bob.shortForm() + ":g:helper"), bob.ctx()));
		assertEquals(s(bob.did() + "/g/helper"), RT.getIn(info, Fields.ADDRESS));
	}

	@Test
	void aJobsOwnerInShortFormLooksUpItsAccount() {
		User bob = user("sf-job");
		Job written = run("v/ops/covia/write", write("w/notes", "x"), bob.ctx());
		complete(written);
		ACell status = complete(run("v/ops/job/status", Maps.of(Fields.ID, written.getID(), Fields.OWNER, bob.shortForm()), bob.ctx()));
		assertEquals(Status.COMPLETE, RT.getIn(status, Fields.STATUS));
	}

	@Test
	void aBindingsUserMayBeAUserOfTheVenueInShortForm() {
		User bob = user("sf-bot");
		MessagingSettings.validate("main", RT.castMap(Maps.of("user", bob.shortForm(), "agent", "helper", "token", "s/TOKEN")),
			MessagingSettings.COMMON, Set.of("token"), true);
		MessagingBotSpec spec = new MessagingBotSpec() {
			@Override public String name() { return "main"; }
			@Override public String userRef() { return bob.shortForm(); }
			@Override public String agent() { return "helper"; }
			@Override public String operation() { return null; }
			@Override public ACell reply() { return null; }
		};
		assertEquals(bob.did(), spec.userDID(engine), "the binding acts for the user in full");
	}

	// ========== REST and MCP ==========

	@Test
	void theValuesRouteTakesAPathInShortForm() throws Exception {
		Engine venue = TwoVenueTestServer.ENGINE_A;
		String name = "sf-rest-" + Long.toHexString(System.nanoTime());
		AKeyPair key = AKeyPair.generate();
		complete(run(venue, "v/ops/user/create", Maps.of("username", name,
			Fields.AUTHENTICATION_KEYS, Vectors.of(UCAN.toDIDKey(key.getAccountKey()))), venue.venueContext()));
		AString did = venue.managedUserDID(s(name));
		complete(run(venue, "v/ops/covia/write", write("w/notes", "over http"), RequestContext.of(did)));

		VenueHTTP client = VenueHTTP.create(URI.create(TwoVenueTestServer.BASE_URL_A), VenueAuth.namedKeyPair(key, did.toString()));
		client.setTimeout(5000);
		AMap<AString, ACell> value = client.getValue("read", "u:" + name + "/w/notes").get(5, TimeUnit.SECONDS);
		assertEquals(s("over http"), value.get(Fields.VALUE));
	}

	@Test
	void theValuesRouteOfAVenueWithNoPublicHostnameRefusesTheForm() throws Exception {
		String path = URLEncoder.encode("u:bob/w/notes", StandardCharsets.UTF_8);
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create(TestServer.BASE_URL + "/api/v1/values/read?path=" + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(400, response.statusCode(), response.body());
		assertTrue(response.body().contains(Engine.NO_SHORT_FORM), response.body());
	}

	@Test
	void theMcpToolsTakeABearerSecretInShortForm(TestInfo info) {
		for (String op : new String[] {"v/ops/mcp/tools-call", "v/ops/mcp/tools-list"}) {
			AMap<AString, ACell> schema = RT.ensureMap(RT.getIn(engine.resolveAsset(s(op)).meta(), Fields.OPERATION, Fields.INPUT));
			AMap<AString, ACell> input = Maps.of(Fields.SERVER, "https://mcp.example.com", Fields.TOOL_NAME, "tool",
				Fields.ARGUMENTS, Maps.empty());
			for (String reference : new String[] {"s/TOKEN", "did:key:z6MkOther/s/TOKEN", "u:bob/s/TOKEN"}) {
				assertNull(JsonSchema.validate(schema, input.assoc(Fields.BEARER_SECRET, s(reference))), op + " " + reference);
			}
			assertNotNull(JsonSchema.validate(schema, input.assoc(Fields.BEARER_SECRET, s("u:bob"))), op);
		}

		// The form reaches the adapter, which expands it: here, on a venue that names no users so
		Engine plain = TestServer.ENGINE;
		String failed = failure(run(plain, "v/ops/mcp/tools-list", Maps.of(Fields.SERVER, TestServer.BASE_URL + "/mcp",
			Fields.BEARER_SECRET, "u:bob/s/TOKEN"), RequestContext.of(TestEngine.uniqueDID(info))));
		assertTrue(failed.startsWith(Engine.NO_SHORT_FORM), failed);
	}
}
