package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.auth.VenueAuth;
import covia.grid.client.VenueHTTP;
import covia.venue.server.VenueServer;

/**
 * #148 — the public read-only capability ceiling, end to end through the HTTP
 * auth layer.
 *
 * <p>Verifies the three properties of the secure-by-default public profile:
 * <ol>
 *   <li>an unauthenticated (public) caller may <b>read</b> but may not
 *       <b>mutate</b> or <b>invoke</b> — invoking any operation creates a job
 *       and is therefore resource-consuming, so the read-only ceiling denies it;</li>
 *   <li>authenticating (with no token attenuation) lifts the ceiling;</li>
 *   <li>an operator can opt out via {@code auth.public.caps = "unrestricted"}.</li>
 * </ol>
 *
 * <p>The shared {@link TestServer} runs unrestricted (its functional tests
 * invoke as the public caller); this class launches its own venues to exercise
 * the ceiling itself.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class PublicCeilingTest {

	private static final AString OP_WRITE = Strings.create("v/ops/covia/write");
	private static final AString OP_READ  = Strings.create("v/ops/covia/read");
	private static final AString OP_ECHO  = Strings.create("v/test/ops/echo");

	/** Venue with the secure read-only public default (auth.public.caps absent). */
	private VenueServer secureServer;
	private String secureBase;
	/** The shared TestServer runs unrestricted public — reuse it for the operator
	 *  opt-out case rather than launching a second venue. */
	private final String openBase = TestServer.BASE_URL;

	@BeforeAll
	public void setup() {
		secureServer = VenueServer.launch(Maps.of(
			Strings.create("port"), 0, // ephemeral
			Config.AUTH, Maps.of(
				Config.PUBLIC, Maps.of(Config.ENABLED, true)))); // default → read-only
		secureBase = "http://localhost:" + secureServer.port();
	}

	@AfterAll
	public void teardown() {
		if (secureServer != null) try { secureServer.close(); } catch (Exception ignored) {}
	}

	private static VenueHTTP anon(String base) {
		VenueHTTP c = VenueHTTP.create(URI.create(base));
		c.setTimeout(5000);
		return c;
	}

	/**
	 * An authenticated client whose caller carries no attenuation ceiling. The
	 * UCAN is audienced to THIS venue (so it passes audience validation — a real
	 * bearer must be intended for the venue it is presented to). Presented proofs
	 * are additive grants, never a subtractive ceiling, so the session runs
	 * unrestricted; identity authenticates as the issuer DID.
	 */
	private static VenueHTTP authed(VenueServer server) {
		AKeyPair kp = AKeyPair.generate();
		AString did = UCAN.toDIDKey(kp.getAccountKey());
		long exp = (System.currentTimeMillis() / 1000) + 3600;
		// Audience = THIS venue (its account key's DID), so the token passes
		// audience validation; the bearer authenticates identity, not a ceiling.
		UCAN token = UCAN.create(kp, server.getEngine().getAccountKey(), exp,
			Vectors.of(Capability.create(Strings.create(did + "/w/"), Capability.CRUD_READ)),
			Vectors.empty());
		String jwt = token.toJWT(kp).toString();
		VenueHTTP c = VenueHTTP.create(URI.create("http://localhost:" + server.port()), VenueAuth.bearer(jwt));
		c.setTimeout(5000);
		return c;
	}

	private static String causeChain(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c.getMessage() != null) sb.append(c.getMessage()).append(" | ");
		}
		return sb.toString();
	}

	// ===================== secure read-only default =====================

	@Test
	public void anonymousMutationDeniedByDefault() throws Exception {
		VenueHTTP pub = anon(secureBase);
		// Denial fails the Job at the adapter's enforcement point.
		Job job = pub.invokeAndWait(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("w/x"),
			Strings.create("value"), Strings.create("nope")));
		assertEquals(Status.FAILED, job.getStatus(), "public write must be denied");
		assertTrue(String.valueOf(job.getErrorMessage()).contains("Capability denied"),
			"public write denial must name the cap, got: " + job.getErrorMessage());
	}

	@Test
	public void anonymousInvokeDeniedByDefault() throws Exception {
		// Invoking any op creates a job (resource-consuming), so the read-only
		// ceiling denies it — even an otherwise-harmless echo.
		VenueHTTP pub = anon(secureBase);
		Job job = pub.invokeAndWait(OP_ECHO, Maps.of(Strings.create("hi"), Strings.create("there")));
		assertEquals(Status.FAILED, job.getStatus(), "public invoke must be denied");
		assertTrue(String.valueOf(job.getErrorMessage()).contains("Capability denied"),
			"public invoke denial must name the cap, got: " + job.getErrorMessage());
	}

	@Test
	public void anonymousReadAllowedByDefault() throws Exception {
		VenueHTTP pub = anon(secureBase);
		Job job = pub.invokeAndWait(OP_READ, Maps.of(Strings.create("path"), Strings.create("w/x")));
		assertEquals(Status.COMPLETE, job.getStatus(),
			"public read of own/venue lattice is allowed under the read-only default");
	}

	@Test
	public void authenticatedMutationAllowed() throws Exception {
		VenueHTTP client = authed(secureServer);
		Job job = client.invokeAndWait(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("w/x"),
			Strings.create("value"), Strings.create("ok")));
		assertEquals(Status.COMPLETE, job.getStatus(),
			"an authenticated caller with no attenuation writes freely");
	}

	// ========================= operator opt-out =========================

	@Test
	public void unrestrictedConfigAllowsAnonymousMutation() throws Exception {
		VenueHTTP pub = anon(openBase);
		Job job = pub.invokeAndWait(OP_WRITE, Maps.of(
			Strings.create("path"), Strings.create("w/x"),
			Strings.create("value"), Strings.create("ok")));
		assertEquals(Status.COMPLETE, job.getStatus(),
			"auth.public.caps=unrestricted re-opens public mutation");
	}
}
