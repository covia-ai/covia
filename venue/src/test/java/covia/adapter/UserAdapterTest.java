package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.UCAN;
import convex.auth.ucan.RootAuthorityPolicy;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;

class UserAdapterTest {

	private static Engine engine;

	@BeforeAll
	static void setup() {
		engine = Engine.createTemp(Maps.of(
			Config.HOSTNAME, Strings.create("venue.example.com")));
		Engine.addDemoAssets(engine);
	}

	@AfterAll
	static void close() {
		if (engine != null) engine.close();
	}

	@Test
	void adapterAndOperationsAreInstalled() {
		assertInstanceOf(UserAdapter.class, engine.getAdapter("user"));
		assertNotNull(engine.resolvePath(Strings.create("v/ops/user/create"), engine.venueContext()));
		assertNotNull(engine.resolvePath(Strings.create("v/ops/user/info"), engine.venueContext()));
		assertNotNull(engine.resolvePath(Strings.create("v/ops/user/list"), engine.venueContext()));
		assertNotNull(engine.resolvePath(Strings.create("v/ops/user/authentication-add"), engine.venueContext()));
		assertNotNull(engine.resolvePath(Strings.create("v/ops/user/authentication-revoke"), engine.venueContext()));
		assertNotNull(engine.resolvePath(Strings.create("v/ops/user/authentication-list"), engine.venueContext()));
	}

	@Test
	void createsArbitrarySelfSovereignDIDIdempotently() throws Exception {
		AString did = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ACell first = create(Maps.of(Fields.DID, did));
		assertEquals(did, RT.getIn(first, Fields.DID));
		assertEquals(CVMBool.TRUE, RT.getIn(first, Fields.CREATED));
		assertNotNull(engine.getVenueState().users().get(did));

		ACell second = create(Maps.of(Fields.DID, did));
		assertEquals(CVMBool.FALSE, RT.getIn(second, Fields.CREATED));
	}

	@Test
	void createsExplicitDidWebAndVenueManagedUsername() throws Exception {
		AString external = Strings.create("did:web:identity.example:u:bob");
		assertEquals(external, RT.getIn(create(Maps.of(Fields.DID, external)), Fields.DID));

		ACell managed = create(Maps.of("username", "alice"));
		AString expected = Strings.create("did:web:venue.example.com:u:alice");
		assertEquals(expected, RT.getIn(managed, Fields.DID));
		assertNotNull(engine.getVenueState().users().get(expected));
		assertEquals(expected,
			engine.getAuth().getUser(Strings.create("alice")).get(Fields.DID));
	}

	@Test
	void managedAccountCreationDoesNotMintAUserIdentityKey() throws Exception {
		ACell created = create(Maps.of("username", "no-user-key"));
		AString did = RT.ensureString(RT.getIn(created, Fields.DID));
		User user = engine.getVenueState().users().get(did);

		assertNotNull(user);
		assertEquals(Maps.empty(), user.get(),
			"account provisioning should create only an empty user-state record");
		assertTrue(user.secrets().list().isEmpty(),
			"creating a custodial account must not mint or store a per-user identity key");
	}

	@Test
	void venueRootAuthorityIsLimitedToItsManagedUsers() {
		RootAuthorityPolicy policy = engine.rootAuthorityPolicy();
		AString venue = engine.getDIDString();
		AString managed = Strings.create("did:web:venue.example.com:u:alice");
		AString self = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		AString external = Strings.create("did:web:identity.example:u:bob");

		assertTrue(policy.acceptsRoot(venue, Strings.create(managed + "/w/notes")));
		assertTrue(policy.acceptsRoot(self, Strings.create(self + "/w/notes")));
		assertFalse(policy.acceptsRoot(venue, Strings.create(self + "/w/notes")),
			"registration must not transfer control of a self-sovereign DID");
		assertFalse(policy.acceptsRoot(venue, Strings.create(external + "/w/notes")),
			"a similarly shaped DID on another domain is not locally custodial");
		assertFalse(policy.acceptsRoot(venue,
			Strings.create("did:web:venue.example.com.evil:u:alice/w/notes")),
			"a hostname-prefix lookalike must not enter the managed namespace");
		assertFalse(policy.acceptsRoot(venue,
			Strings.create(managed + ":admin/w/notes")),
			"a nested managed-DID suffix must not be accepted as a username");
	}

	@Test
	void rejectsMalformedDIDAndAmbiguousIdentityInput() {
		ExecutionException badDID = assertThrows(ExecutionException.class,
			() -> create(Maps.of(Fields.DID, "alice")));
		assertInstanceOf(IllegalArgumentException.class, badDID.getCause());

		ExecutionException both = assertThrows(ExecutionException.class,
			() -> create(Maps.of(Fields.DID, "did:example:alice", "username", "alice")));
		assertInstanceOf(IllegalArgumentException.class, both.getCause());

		ExecutionException badUsername = assertThrows(ExecutionException.class,
			() -> create(Maps.of("username", "alice/admin")));
		assertInstanceOf(IllegalArgumentException.class, badUsername.getCause());
	}

	@Test
	void ordinaryRegisteredUserCannotProvisionAnotherUser() throws Exception {
		AString caller = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		engine.getVenueState().users().create(caller);
		AString target = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());

		ExecutionException denied = assertThrows(ExecutionException.class,
			() -> engine.jobs().invokeInternal("v/ops/user/create",
				Maps.of(Fields.DID, target), RequestContext.of(caller))
				.get(5, TimeUnit.SECONDS));
		assertInstanceOf(AuthException.class, denied.getCause());
		assertTrue(denied.getCause().getMessage().contains("venue-issued delegation"));
		assertEquals(null, engine.getVenueState().users().get(target));
	}

	@Test
	void userCanReadSelfAndVenueCanList() throws Exception {
		AString did = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		create(Maps.of(Fields.DID, did));

		ACell info = engine.jobs().invokeInternal("v/ops/user/info", Maps.empty(),
			RequestContext.of(did)).get(5, TimeUnit.SECONDS);
		assertEquals(did, RT.getIn(info, Fields.DID));
		assertEquals(CVMBool.TRUE, RT.getIn(info, "registered"));

		ACell list = engine.jobs().invokeInternal("v/ops/user/list", Maps.empty(),
			engine.venueContext()).get(5, TimeUnit.SECONDS);
		assertTrue(RT.ensureLong(RT.getIn(list, "total")).longValue() >= 2);
	}

	private static ACell create(AMap<AString, ACell> input) throws Exception {
		return engine.jobs().invokeInternal("v/ops/user/create", input,
			engine.venueContext()).get(5, TimeUnit.SECONDS);
	}
}
