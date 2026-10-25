package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.remote.ARemoteAgentAdapter;
import covia.adapter.remote.RemoteInteraction;
import covia.adapter.remote.VenueRemoteAgent;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.Venue;
import covia.venue.LocalVenue;
import covia.venue.RemoteJobs;
import covia.venue.RequestContext;

/** Native Covia agent task proxy, sharing the external-agent interaction lifecycle. */
public class RemoteAgentAdapter extends ARemoteAgentAdapter<RemoteInteraction> {
	public static final String INVALID_INPUT = "Remote agent request requires an object input and agentId";
	public static final String INVALID_TASK = "Remote agent returned an invalid task identity or status";
	public static final String LOCAL_INTERRUPTED = "Local agent proxy interrupted by restart; inspect the original task before retrying";
	public static final String UNKNOWN_OPERATION = "Unknown remoteagent operation";
	private static final AString PROTOCOL = Strings.intern("covia-agent");
	private static final AString LOCAL_PROTOCOL = Strings.intern("covia-agent-local");
	private static final AString AGENT = Strings.intern("remoteAgent");
	private static final AString AUTHENTICATE_AS = Strings.intern("authenticateAs");

	@Override public String getName() { return "remoteagent"; }
	@Override public String getDescription() { return "Request tasks from Covia agents on this or another venue, with continuation, progress and cancellation."; }
	@Override protected void installAssets() {
		installAsset("remoteagent/request", "/adapters/remoteagent/request.json");
	}
	@Override protected boolean isInteractionOperation(String operation) { return "request".equals(operation) || "chat".equals(operation); }
	@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		if (!isInteractionOperation(getSubOperation(meta))) throw new IllegalArgumentException(UNKNOWN_OPERATION);
		return engine.jobs().invokeInternal(meta, input, ctx);
	}

	@Override public void validateAgent(AMap<AString, ACell> definition) {
		AString id = RT.ensureString(definition.get(Fields.AGENT_ID));
		if (id == null || id.toString().isBlank()) throw new IllegalArgumentException(INVALID_INPUT);
	}

	@Override public AgentInvocation agentInvocation(String operation, AMap<AString, ACell> definition,
			ACell content, ACell session) {
		if (!"chat".equals(operation) && !"request".equals(operation))
			return super.agentInvocation(operation, definition, content, session);
		AMap<AString, ACell> input = definition.dissoc(Fields.ADAPTER).dissoc(Fields.SESSION_ID)
			.assoc("chat".equals(operation) ? Fields.MESSAGE : Fields.INPUT, content);
		if (session != null) input = input.assoc(Fields.SESSION_ID, session);
		return new AgentInvocation(operation, input);
	}

	@Override public ACell agentSession(Job job) { return job.getData().get(Fields.SESSION_ID); }
	@Override public ACell agentOutput(Job job) {
		AMap<AString, ACell> output = RT.ensureMap(job.getOutput());
		if (output == null) return job.getOutput();
		return output.containsKey(Fields.RESPONSE) ? output.get(Fields.RESPONSE)
			: output.containsKey(Fields.OUTPUT) ? output.get(Fields.OUTPUT) : output;
	}

	@Override protected void startInteraction(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		AMap<AString, ACell> params = RT.ensureMap(input);
		AString agent = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (params == null || agent == null || agent.toString().isBlank()
			|| ("request".equals(getSubOperation(meta)) && RT.ensureMap(params.get(Fields.INPUT)) == null)) throw new IllegalArgumentException(INVALID_INPUT);
		AString target = RT.ensureString(params.get(Fields.VENUE));
		Venue venue;
		AMap<AString, ACell> credentials;
		if (target == null) {
			LocalVenue local = new LocalVenue(engine);
			local.setRequestContext(ctx);
			venue = local;
			credentials = Maps.empty();
		} else {
			VenueConnection connection = VenueConnection.connect(engine, ctx, target, input);
			venue = connection.venue();
			credentials = connection.credentials();
		}
		VenueRemoteAgent proxy = new VenueRemoteAgent(venue, agent);
		job.update(data -> data.assoc(AGENT, agent));
		RemoteJobs observer = engine.remoteJobs();
		observer.prepare(job, (target == null ? LOCAL_PROTOCOL : PROTOCOL).toString(),
			target == null ? engine.getDIDString().toString() : target.toString(), credentials);
		if (job.isFinished()) return;
		proxy.submit(getSubOperation(meta), params.dissoc(Fields.VENUE).dissoc(AUTHENTICATE_AS)).whenComplete((accepted, error) -> {
			observer.received(job, () -> {
				if (error != null) {
					if (target == null && unwrap(error) instanceof covia.exception.AuthException) job.fail(unwrap(error));
					else observer.submissionFailed(job, error);
					return;
				}
				try {
					observer.accepted(job, accepted.getID().toHexString());
					bind(job, proxy, accepted.getID());
					if (job.isFinished()) return;
					apply(job, accepted.getData());
				} catch (RuntimeException | Error failure) { observer.submissionUnknown(job, failure); }
				finally {
					ACell savedId = RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID);
					Blob id = savedId == null ? null : Job.parseID(savedId);
					if (id != null && !job.isFinished()) watch(job, proxy, id);
				}
			});
		});
	}

	private void bind(Job job, VenueRemoteAgent proxy, Blob id) {
		bindInteraction(job, new RemoteInteraction() {
			@Override public void deliver(AMap<AString, ACell> record) {
				AMap<AString, ACell> message = RT.ensureMap(record.get(Fields.MESSAGE));
				if (message == null) message = Maps.of(Fields.CONTENT, record.get(Fields.MESSAGE));
				proxy.deliver(id, message);
				// Discard any older poll that raced the acknowledged continuation.
				engine.remoteJobs().received(job, () -> { if (!job.isFinished()) watch(job, proxy, id); });
			}
			@Override public void cancel() {
				if (LOCAL_PROTOCOL.equals(RemoteJobs.descriptor(job).get(RemoteJobs.PROTOCOL))) {
					proxy.cancel(id); // In-process child: propagate before returning.
					return;
				}
				engine.remoteJobs().attemptCancellation(job, id.toHexString(),
					() -> RemoteJobs.cancellationResult(id, proxy.cancel(id)));
			}
		});
	}

	private void watch(Job job, VenueRemoteAgent proxy, Blob id) {
		engine.remoteJobs().observe(job, () -> proxy.task(id), snapshot -> apply(job, snapshot));
	}

	private void apply(Job job, AMap<AString, ACell> snapshot) {
		Blob expected = Job.parseID(RemoteJobs.descriptor(job).get(RemoteJobs.REMOTE_ID));
		AString status = RT.ensureString(snapshot.get(Fields.STATUS));
		if (expected == null || !expected.equals(Job.parseID(snapshot.get(Fields.ID)))
			|| !(Status.PENDING.equals(status) || Status.STARTED.equals(status) || Status.PAUSED.equals(status)
			|| Status.INPUT_REQUIRED.equals(status) || Status.AUTH_REQUIRED.equals(status) || Status.COMPLETE.equals(status)
			|| Status.FAILED.equals(status) || Status.REJECTED.equals(status) || Status.CANCELLED.equals(status))) {
			throw new IllegalArgumentException(INVALID_TASK);
		}
		if (Status.COMPLETE.equals(status)) {
			job.completeWith(snapshot.get(Fields.OUTPUT), data -> observation(data, snapshot, status));
		} else {
			job.update(data -> {
				AMap<AString, ACell> next = observation(data, snapshot, status)
					.assoc(Fields.STATUS, Status.PENDING.equals(status) ? Status.STARTED : status);
				if (snapshot.containsKey(Fields.OUTPUT)) next = next.assoc(Fields.OUTPUT, snapshot.get(Fields.OUTPUT));
				if (Job.isFinished(snapshot)) next = next.assoc(Fields.ERROR, snapshot.get(Fields.ERROR));
				return next;
			});
			// The remote Job's progress is shown here as it is there: never recorded.
			job.setProgress(snapshot.get(PROGRESS));
		}
	}

	private static AMap<AString, ACell> observation(AMap<AString, ACell> data,
		AMap<AString, ACell> snapshot, AString status) {
		AMap<AString, ACell> next = RemoteJobs.observed(data, status);
		if (snapshot.containsKey(Fields.SESSION_ID)) next = next.assoc(Fields.SESSION_ID, snapshot.get(Fields.SESSION_ID));
		return next;
	}

	@Override public void recoverJob(Job job) {
		AMap<AString, ACell> record = RemoteJobs.descriptor(job);
		if (record == null) { super.recoverJob(job); return; }
		if (LOCAL_PROTOCOL.equals(record.get(RemoteJobs.PROTOCOL))) {
			// A caller DID alone cannot reconstruct the original attenuated local context.
			job.fail(LOCAL_INTERRUPTED);
			return;
		}
		ACell savedId = record.get(RemoteJobs.REMOTE_ID);
		Blob id = savedId == null ? null : Job.parseID(savedId);
		if (id == null) { engine.remoteJobs().submissionUnknown(job, null); return; }
		try {
			Venue venue = VenueConnection.restore(engine, RT.ensureString(record.get(RemoteJobs.TARGET)),
				engine.remoteJobs().credentials(job));
			VenueRemoteAgent proxy = new VenueRemoteAgent(venue, RT.ensureString(job.getData().get(AGENT)));
			bind(job, proxy, id);
			watch(job, proxy, id);
		} catch (RuntimeException e) { engine.remoteJobs().unavailable(job, e); }
	}

	@Override public void suspendJob(Job job) {
		if (RemoteJobs.descriptor(job) != null) engine.remoteJobs().suspend(job);
		else super.suspendJob(job);
	}
}
