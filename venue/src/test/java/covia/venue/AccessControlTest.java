package covia.venue;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.api.Fields;
import covia.grid.Job;

public class AccessControlTest {

	Engine engine;

	@BeforeEach
	public void setup() throws IOException {
		engine = Engine.createTemp(null);
		Engine.addDemoAssets(engine);
	}

	@Test
	public void testAccessControlExists() {
		AccessControl ac = engine.getAccessControl();
		assertNotNull(ac, "AccessControl should be initialized");
		assertNotNull(ac.getCursor(), "Auth cursor should be initialized");
	}

	@Test
	public void testSkeletonAllowsAll() {
		AccessControl ac = engine.getAccessControl();

		// Anonymous context
		assertTrue(ac.hasCapability(RequestContext.ANONYMOUS, Keyword.intern("invoke")));
		assertTrue(ac.canAccessOperation(RequestContext.ANONYMOUS, null));
		assertTrue(ac.canAccessJob(RequestContext.ANONYMOUS, null));

		// Authenticated context
		RequestContext authed = RequestContext.of(Strings.create("did:key:z6MkTest"));
		assertTrue(ac.hasCapability(authed, Keyword.intern("invoke")));
		assertTrue(ac.canAccessOperation(authed, null));
		assertTrue(ac.canAccessJob(authed, null));

		// Internal context
		assertTrue(ac.hasCapability(RequestContext.INTERNAL, Keyword.intern("admin")));
		assertTrue(ac.canAccessOperation(RequestContext.INTERNAL, null));
		assertTrue(ac.canAccessJob(RequestContext.INTERNAL, null));
	}

	@Test
	public void testRequestContextProperties() {
		// Internal
		assertTrue(RequestContext.INTERNAL.isInternal());
		assertTrue(!RequestContext.INTERNAL.isAuthenticated());
		assertTrue(!RequestContext.INTERNAL.isAnonymous());

		// Anonymous
		assertTrue(!RequestContext.ANONYMOUS.isInternal());
		assertTrue(!RequestContext.ANONYMOUS.isAuthenticated());
		assertTrue(RequestContext.ANONYMOUS.isAnonymous());

		// Authenticated
		RequestContext authed = RequestContext.of(Strings.create("did:key:z6MkTest"));
		assertTrue(!authed.isInternal());
		assertTrue(authed.isAuthenticated());
		assertTrue(!authed.isAnonymous());

		// Null DID returns anonymous
		assertTrue(RequestContext.of(null) == RequestContext.ANONYMOUS);
	}

	@Test
	public void testInvokeWithRequestContext() {
		// Invoke via RequestContext (anonymous)
		Job job1 = engine.invokeOperation(Strings.create("test:echo"), Maps.of("message", "hello"), RequestContext.ANONYMOUS);
		assertNotNull(job1, "Should be able to invoke with anonymous context");

		// Invoke via RequestContext (authenticated)
		RequestContext authed = RequestContext.of(Strings.create("did:key:z6MkAlice"));
		Job job2 = engine.invokeOperation(Strings.create("test:echo"), Maps.of("message", "hello"), authed);
		assertNotNull(job2, "Should be able to invoke with authenticated context");

		// Verify caller DID flows through to job record
		AMap<?, ?> jobData = job2.getData();
		assertNotNull(jobData);
		ACell caller = ((AMap<ACell, ACell>) jobData).get(Fields.CALLER);
		assertNotNull(caller, "Job record should contain caller field");
		assertTrue(caller.toString().contains("did:key:z6MkAlice"), "Caller DID should be in job record");
	}

	@Test
	public void testJobManagementWithRequestContext() throws Exception {
		// Create a job via RequestContext
		RequestContext authed = RequestContext.of(Strings.create("did:key:z6MkBob"));
		Job job = engine.invokeOperation(Strings.create("test:delay"), Maps.of("delay", 10000), authed);
		assertNotNull(job);

		// Cancel with RequestContext
		AMap<?, ?> status = engine.cancelJob(job.getID(), authed);
		assertNotNull(status, "Cancel should return job status");
	}
}
