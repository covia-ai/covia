package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.NotSupportedException;

/** The admin namespace changes delegation, not operation paths or resource ownership. */
class AdminCapabilitiesTest {

	private final Engine engine = TestEngine.ENGINE;
	private final AKeyPair delegateKey = AKeyPair.generate();
	private final AString delegate = UCAN.toDIDKey(delegateKey.getAccountKey());

	private RequestContext grant(AKeyPair issuer, String resource, String ability) {
		engine.getVenueState().users().create(delegate);
		AString proof = UCAN.create(issuer, delegateKey.getAccountKey(),
			engine.currentTimestamp().longValue() / 1000 + 3600,
			Vectors.of(Capability.create(Strings.create(resource), Strings.create(ability))),
			Vectors.empty()).toJWT(issuer);
		return engine.withTransportGrants(RequestContext.of(delegate), Vectors.of(proof));
	}

	private RequestContext venueGrant(String resource, String ability) {
		return grant(engine.getKeyPair(), engine.getDIDString() + "/" + resource, ability);
	}

	private ACell run(String operation, ACell input, RequestContext ctx) throws Exception {
		return engine.jobs().invokeInternal(operation, input, ctx).get(5, TimeUnit.SECONDS);
	}

	private void denied(String operation, ACell input, RequestContext ctx) {
		ExecutionException failure = assertThrows(ExecutionException.class, () -> run(operation, input, ctx));
		assertInstanceOf(AuthException.class, failure.getCause());
	}

	@Test
	void administrativeReadsRequireAdminAbilities() throws Exception {
		checkRead("v/ops/user/list", "users", "admin/user/read", "user/read", "admin/user");
		checkRead("v/ops/venue/adapters", "adapters", "admin/adapter/manage", "adapter/manage", "admin/adapter");
	}

	private void checkRead(String operation, String resource,
			String ability, String legacy, String family) throws Exception {
		ACell input = Maps.empty();
		for (String allowed : new String[] {ability, family, "admin", "*"}) {
			assertNotNull(run(operation, input, venueGrant(resource, allowed)), allowed);
		}
		for (String refused : new String[] {legacy, legacy.split("/")[0], "mcp/manage", "grant/" + ability}) {
			denied(operation, input, venueGrant(resource, refused));
		}
		denied(operation, input, venueGrant("unrelated", "admin"));
		// The caller's own admin grant cannot administer the venue's resource.
		denied(operation, input, grant(delegateKey, delegate + "/" + resource, "admin"));
	}

	@Test
	void registrationAndDeletionHaveSeparateAdminAbilities() throws Exception {
		AString target = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		ACell input = Maps.of(Fields.DID, target);
		denied("v/ops/user/create", input, venueGrant("users", "user/create"));
		assertNull(engine.getVenueState().users().user(target));
		RequestContext provisioner = venueGrant("users", "admin/user/create");
		run("v/ops/user/create", input, provisioner);
		assertNotNull(engine.getVenueState().users().user(target));
		denied("v/ops/user/delete", input, provisioner);
		denied("v/ops/user/delete", input, venueGrant("users", "user/delete"));
		run("v/ops/user/delete", input, venueGrant("users", "admin/user/delete"));
		assertTrue(engine.getVenueState().users().isDeleted(target));
	}

	@Test
	void restartRetainsItsProcessRestrictionAfterAuthorization() {
		denied("v/ops/venue/restart", Maps.empty(), venueGrant("process", "venue/restart"));
		ExecutionException failure = assertThrows(ExecutionException.class,
			() -> run("v/ops/venue/restart", Maps.empty(), venueGrant("process", "admin/venue/restart")));
		assertInstanceOf(NotSupportedException.class, failure.getCause());
	}

	@Test
	void collectionRetainsItsStoreRestrictionAfterAuthorization() {
		ACell input = Maps.of("status", true);
		for (String refused : new String[] {"venue/gc", "venue", "admin/venue/restart"}) {
			denied("v/ops/venue/gc", input, venueGrant("store", refused));
		}
		for (String allowed : new String[] {"admin/venue/gc", "admin/venue", "admin"}) {
			ExecutionException failure = assertThrows(ExecutionException.class,
				() -> run("v/ops/venue/gc", input, venueGrant("store", allowed)));
			assertInstanceOf(NotSupportedException.class, failure.getCause());
		}
	}

	@Test
	void mcpManagementRemainsIndependentOfAdmin() {
		RequestContext admin = venueGrant("", "admin");
		assertFalse(engine.hasVenueAuthority(admin, "v/mcp", Abilities.MCP_MANAGE));
		RequestContext mcp = venueGrant("v/mcp", "mcp/manage");
		assertTrue(engine.hasVenueAuthority(mcp, "v/mcp", Abilities.MCP_MANAGE));
		assertFalse(engine.hasVenueAuthority(mcp, "adapters", Abilities.ADMIN_ADAPTER_MANAGE));
	}

	@Test
	void sudoNeedsItsOwnAdminAbilityAndKeepsTargetAndActorSeparate() {
		AKeyPair ownerKey = AKeyPair.generate();
		AString owner = UCAN.toDIDKey(ownerKey.getAccountKey());
		AString other = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		engine.getVenueState().users().create(owner);
		engine.getVenueState().users().create(other);
		for (String refused : new String[] {"user/sudo", "user", "admin/user"}) {
			RequestContext ctx = grant(ownerKey, owner.toString(), refused);
			assertThrows(AuthException.class, () -> engine.sudoContext(ctx, owner));
		}
		for (String allowed : new String[] {"admin/sudo", "admin"}) {
			RequestContext ctx = grant(ownerKey, owner.toString(), allowed);
			RequestContext sudo = engine.sudoContext(ctx, owner);
			assertEquals(owner, sudo.getUserDID());
			assertEquals(delegate, sudo.getCallerDID());
			assertEquals(Vectors.empty(), sudo.getCaps());
			assertThrows(AuthException.class, () -> engine.sudoContext(ctx, other));
			assertThrows(AuthException.class,
				() -> engine.requireAuthority(sudo, "w/private", "crud/read"));
		}
	}
}
