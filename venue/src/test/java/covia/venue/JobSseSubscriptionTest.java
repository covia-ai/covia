package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.grid.Job;
import covia.venue.server.SseServer;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

/** Force an update between the initial snapshot read and its delivery. */
class JobSseSubscriptionTest {
	private static final class Streams extends SseServer {
		Streams() { super(TestEngine.ENGINE); }
		void open(Job job, SseClient client, RequestContext ctx) {
			subscribeJob(job.getID(), client, ctx, ctx.getUserDID());
		}
	}

	@Test void completionDuringInitialReadCannotBeLost(TestInfo info) {
		Engine engine = TestEngine.ENGINE;
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Job job = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
		Streams streams = new Streams();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		SseClient client = client(bytes);
		AtomicBoolean first = new AtomicBoolean(true);
		AString resource = JobManager.resource(owner.getUserDID(), job.getID());
		RequestContext reader = owner.readingScope(checked -> {
			// JobManager has fetched the old record and is authorizing it now.
			// Deliver the completion callback before that read returns.
			if (resource.equals(checked) && first.compareAndSet(true, false)) {
				job.completeWith(Strings.create("finished during subscription"));
				streams.broadcastJobUpdate(job.getID().toHexString(), job);
			}
			return Vectors.of(Capability.create(resource, Abilities.JOB_READ));
		});
		try {
			streams.open(job, client, reader);
			assertFalse(first.get(), "the interleaving must have occurred");
			String frames = bytes.toString(StandardCharsets.UTF_8);
			assertTrue(frames.contains("COMPLETE"), frames);
			assertFalse(frames.contains("STARTED"), frames);
			assertTrue(client.terminated(), "a terminal stream must close");
			int delivered = bytes.size();
			streams.broadcastJobUpdate(job.getID().toHexString(), job);
			assertEquals(delivered, bytes.size(), "closed subscriptions must be removed");
		} finally { client.close(); job.cancel(); }
	}

	@Test void permissionLossEndsStreamWithoutDisclosingCompletion(TestInfo info) {
		RequestContext owner = RequestContext.of(TestEngine.uniqueDID(info));
		Job job = TestEngine.ENGINE.jobs().invokeOperation("v/test/ops/never", Maps.empty(), owner);
		Streams streams = new Streams();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		SseClient client = client(bytes);
		AtomicBoolean allowed = new AtomicBoolean(true);
		RequestContext reader = owner.readingScope(resource -> allowed.get()
			? Vectors.of(Capability.create(JobManager.resource(owner.getUserDID(), job.getID()), Abilities.JOB_READ)) : Vectors.empty());
		try {
			streams.open(job, client, reader);
			allowed.set(false);
			job.completeWith(Strings.create("private result"));
			streams.broadcastJobUpdate(job.getID().toHexString(), job);
			String frames = bytes.toString(StandardCharsets.UTF_8);
			assertTrue(frames.contains(SseServer.JOB_NOT_FOUND), frames);
			assertFalse(frames.contains("private result"), frames);
			assertTrue(client.terminated());
		} finally { client.close(); job.cancel(); }
	}

	/** Real Javalin SSE encoding, with only the servlet output supplied by the test. */
	private static SseClient client(ByteArrayOutputStream bytes) {
		ServletOutputStream output = new ServletOutputStream() {
			@Override public void write(int value) { bytes.write(value); }
			@Override public boolean isReady() { return true; }
			@Override public void setWriteListener(WriteListener listener) { throw new UnsupportedOperationException(); }
		};
		HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
			HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> {
				if (method.getName().equals("getOutputStream")) return output;
				if (method.getName().equals("flushBuffer")) return null;
				throw new UnsupportedOperationException(method.getName());
			});
		Context ctx = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class},
			(proxy, method, args) -> {
				if (method.getName().equals("res")) return response;
				throw new UnsupportedOperationException(method.getName());
			});
		return new SseClient(ctx);
	}
}
