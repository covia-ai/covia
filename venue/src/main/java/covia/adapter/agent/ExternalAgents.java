package covia.adapter.agent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import convex.core.data.*;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.remote.ARemoteAgentAdapter;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.*;

/** Optional agent facet dispatch. Absence alone selects the historical native runtime. */
public final class ExternalAgents {
	public static final String INVALID = "agent must be an object with a non-empty adapter name";
	public static final String UNAVAILABLE = "Agent adapter is unavailable or does not support agent definitions: ";
	public static final String UNSUPPORTED = "Operation is not supported for external agents: ";
	public static final String SESSION_BUSY = "External agent session has an active turn; continue or cancel that job first";
	public static final String UNKNOWN_SESSION = "Unknown sessionId; omit sessionId to start a new session";
	public static final String MISSING_CHILD = "External agent backend job is unavailable; submission will not be replayed";
	public static final String IMMUTABLE_DEFINITION = "Changing agent metadata requires creating a new agent";
	public static final String NO_ACTIVE_TURN = "External agent message requires an active task or session turn";
	public static final AString CHILD = Strings.intern("agentBackendJob");
	private static final AString KIND = Strings.intern("agentInteraction");
	private static final AString OWNER = Strings.intern("agentOwner");
	private final Engine engine;

	public ExternalAgents(Engine engine) { this.engine = engine; }

	public AMap<AString, ACell> validate(ACell value) {
		AMap<AString, ACell> definition = RT.ensureMap(value);
		AString name = definition == null ? null : RT.ensureString(definition.get(Fields.ADAPTER));
		if (name == null || name.toString().isBlank() || name.toString().contains(":"))
			throw new IllegalArgumentException(INVALID);
		backend(definition).validateAgent(definition);
		return definition;
	}

	public ARemoteAgentAdapter<?> backend(AMap<AString, ACell> definition) {
		AString name = definition == null ? null : RT.ensureString(definition.get(Fields.ADAPTER));
		AAdapter adapter = name == null ? null : engine.getAdapter(name.toString());
		if (!(adapter instanceof ARemoteAgentAdapter<?> remote))
			throw new IllegalArgumentException(UNAVAILABLE + name);
		return remote;
	}

	public boolean isExternal(AgentState agent) {
		AMap<AString, ACell> record = agent == null ? null : agent.getRecord();
		return record != null && record.containsKey(AgentState.KEY_AGENT);
	}

	public void start(Job job, RequestContext caller, AString owner, AString id, AString address,
			AgentState agent, String operation, ACell input, Blob sid, boolean supplied) {
		AMap<AString, ACell> definition = validate(agent.getRecord().get(AgentState.KEY_AGENT));
		ARemoteAgentAdapter<?> adapter = backend(definition);
		for (String option : new String[]{"loads", "outputPath", "responseSchema", "strict"}) {
			if (RT.getIn(input, option) != null) throw new IllegalArgumentException(UNSUPPORTED + option);
		}
		if (supplied && agent.getSession(sid) == null) throw new IllegalArgumentException(UNKNOWN_SESSION);
		ACell content = RT.getIn(input, "chat".equals(operation) ? Fields.MESSAGE : Fields.INPUT);
		job.update(d -> d.assoc(Fields.AGENT_ID, id).assoc(Fields.ADDRESS, address)
			.assoc(Fields.SESSION_ID, Strings.create(sid.toHexString())).assoc(OWNER, owner)
			.assoc(KIND, Strings.create(operation)));
		if (job.isFinished()) return;
		if (!agent.claimExternalTurn(sid, job.getID(), caller.getCallerDID(), content, !supplied))
			throw new IllegalStateException(SESSION_BUSY);
		if (job.isFinished()) { agent.finishExternalTurn(sid, job.getID(), null, null); return; }
		try {
			ACell remoteSession = RT.getIn(agent.getSession(sid), AgentState.KEY_EXTERNAL, Fields.SESSION_ID);
			var invocation = adapter.agentInvocation(operation, definition, content, remoteSession);
			AString backendOp = Strings.create(adapter.getName() + ":" + invocation.operation());
			AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, backendOp));
			// The resident agent's configured authority applies, never the waking caller's grants.
			RequestContext execution = AbstractLLMAdapter.capsContext(agent.getConfig(), RequestContext.ofAgent(owner, id)).withJob(job)
				.withOp(Strings.create("v/ops/" + adapter.getName() + "/" + invocation.operation()));
			var prepared = engine.jobs().prepareAgentInteraction(meta, invocation.input(), execution);
			Job child = prepared.job();
			job.update(d -> d.assoc(CHILD, child.getID()));
			attach(job, child, agent, sid, adapter);
			if (job.isFinished()) child.cancel();
			else job.setStatus(Status.STARTED);
			prepared.start();
		} catch (RuntimeException | Error e) {
			agent.finishExternalTurn(sid, job.getID(), null, null);
			throw e;
		}
	}

	private void attach(Job parent, Job child, AgentState agent, Blob sid, ARemoteAgentAdapter<?> adapter) {
		AtomicBoolean settled = new AtomicBoolean();
		Consumer<Job> listener = j -> {
			try {
				if (j.isFinished()) {
					if (!settled.compareAndSet(false, true)) return;
					ACell response = Status.COMPLETE.equals(j.getStatus()) ? adapter.agentOutput(j) : null;
					agent.finishExternalTurn(sid, parent.getID(), adapter.agentSession(j), response);
					if (Status.COMPLETE.equals(j.getStatus())) {
						AMap<AString, ACell> data = parent.getData();
						AMap<AString, ACell> result = Maps.of(Fields.AGENT_ID, data.get(Fields.AGENT_ID),
							Fields.ADDRESS, data.get(Fields.ADDRESS), Fields.SESSION_ID, data.get(Fields.SESSION_ID));
						if (Strings.create("chat").equals(data.get(KIND))) {
							result = result.assoc(Fields.RESPONSE, response).assoc(Strings.intern("answered"),
								Vectors.of(Strings.create(parent.getID().toHexString())));
						} else {
							result = result.assoc(Fields.ID, Strings.create(parent.getID().toHexString()))
								.assoc(Fields.STATUS, Status.COMPLETE).assoc(Fields.OUTPUT, response);
						}
						parent.completeWith(result);
					} else if (Status.CANCELLED.equals(j.getStatus())) parent.cancel();
					else parent.fail(j.getErrorMessage() == null ? MISSING_CHILD : j.getErrorMessage());
				} else {
					parent.update(d -> {
						AMap<AString, ACell> next = d.assoc(Fields.STATUS,
							Status.PENDING.equals(j.getStatus()) ? Status.STARTED : j.getStatus());
						ACell progress = j.getData().get(ARemoteAgentAdapter.PROGRESS);
						return progress == null ? next.dissoc(ARemoteAgentAdapter.PROGRESS)
							: next.assoc(ARemoteAgentAdapter.PROGRESS, progress);
					});
				}
			} catch (RuntimeException failure) {
				agent.finishExternalTurn(sid, parent.getID(), null, null);
				parent.fail(failure);
			}
		};
		child.subscribe(listener);
		child.future().whenComplete((v, e) -> child.unsubscribe(listener));
		parent.setCancelHook(child::cancel);
		listener.accept(child);
	}

	/** Reattach by recorded handle only. The child adapter owns provider recovery. */
	public boolean recover(Job job) {
		if (job.getData().get(KIND) == null) return false;
		AString owner = RT.ensureString(job.getData().get(OWNER));
		User user = owner == null ? null : engine.getVenueState().users().get(owner);
		AgentState agent = user == null ? null : user.agent(RT.ensureString(job.getData().get(Fields.AGENT_ID)));
		ACell childId = job.getData().get(CHILD);
		Job child = childId == null ? null : engine.jobs().getJob(Job.parseID(childId), RequestContext.of(owner));
		if (agent == null || child == null) {
			job.fail(MISSING_CHILD);
			if (agent != null) agent.finishExternalTurn(Job.parseID(job.getData().get(Fields.SESSION_ID)), job.getID(), null, null);
			return true;
		}
		attach(job, child, agent, Job.parseID(job.getData().get(Fields.SESSION_ID)),
			backend(validate(agent.getRecord().get(AgentState.KEY_AGENT))));
		return true;
	}

	/** No execution is replayed when startup finds an abandoned session claim. */
	public void reconcile(AgentState agent) {
		for (var entry : agent.getSessions().entrySet()) {
			ACell id = RT.getIn(entry.getValue(), AgentState.KEY_EXTERNAL, Fields.JOB_ID);
			if (id != null && engine.jobs().getJob(Job.parseID(id)) == null)
				agent.finishExternalTurn(entry.getKey(), Job.parseID(id), null, null);
		}
	}

	public void cancelAll(AgentState agent) {
		for (var entry : agent.getSessions().entrySet()) {
			ACell id = RT.getIn(entry.getValue(), AgentState.KEY_EXTERNAL, Fields.JOB_ID);
			Job parent = id == null ? null : engine.jobs().getJob(Job.parseID(id));
			if (parent != null) parent.cancel();
		}
	}

	public boolean owns(Job job) { return job.getData().get(KIND) != null; }

	public void deliver(AgentState agent, ACell taskId, Blob sid, ACell content) {
		ACell id = taskId != null ? taskId : RT.getIn(agent.getSession(sid), AgentState.KEY_EXTERNAL, Fields.JOB_ID);
		Job parent = id == null ? null : engine.jobs().getJob(Job.parseID(id));
		if (parent == null || !owns(parent) || parent.isFinished()
				|| !parent.getID().equals(RT.getIn(agent.getSession(sid), AgentState.KEY_EXTERNAL, Fields.JOB_ID)))
			throw new IllegalArgumentException(NO_ACTIVE_TURN);
		Job child = engine.jobs().getJob(Job.parseID(parent.getData().get(CHILD)));
		if (child == null) throw new IllegalArgumentException(MISSING_CHILD);
		backend(validate(agent.getRecord().get(AgentState.KEY_AGENT))).handleMessage(child, Maps.of(Fields.MESSAGE, content));
	}
}
