package covia.adapter.agent;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import convex.core.data.*;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.remote.ARemoteAgentAdapter;
import covia.api.Fields;
import covia.exception.ConflictException;
import covia.exception.InvalidDefinitionException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.grid.Principals;
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
	public static final String MISCONFIGURED = "Agent is misconfigured and cannot be reached until it is recreated: ";
	/** In the adapter's data for a job ({@link covia.venue.JobManager#adapterData}): the backend job serving it. */
	public static final AString CHILD = Strings.intern("agentBackendJob");
	/** In the adapter's data: the asking operation, {@code chat} or {@code request}. */
	public static final AString KIND = Strings.intern("agentInteraction");
	/** In the adapter's data: the DID of the user whose agent it is. */
	public static final AString OWNER = Strings.intern("agentOwner");
	private final Engine engine;
	/** The backend job serving each running external turn, by the job asking: what {@link #stop} cancels. */
	private final ConcurrentHashMap<Blob, Job> children;

	/**
	 * @param children Backend jobs by asking job, kept by the adapter across these short-lived views
	 */
	public ExternalAgents(Engine engine, ConcurrentHashMap<Blob, Job> children) {
		this.engine = engine;
		this.children = children;
	}

	/** Cancelling a job that asked an external agent cancels the backend job serving it. */
	public void stop(Job parent) {
		Job child = children.remove(parent.getID());
		if (child != null) child.cancel();
	}

	/** Checks a definition offered for a new agent: a bad one is the caller's input. */
	public AMap<AString, ACell> validate(ACell value) {
		AMap<AString, ACell> definition = RT.ensureMap(value);
		AString name = definition == null ? null : RT.ensureString(definition.get(Fields.ADAPTER));
		if (name == null || name.toString().isBlank() || name.toString().contains(":"))
			throw new InvalidInputException(INVALID);
		backend(definition).validateAgent(definition);
		return definition;
	}

	/**
	 * An existing agent's definition, checked again before use. It passed
	 * {@link #validate} when the agent was created and cannot change, so a
	 * failure now is the agent's, not the caller's: the agent cannot be
	 * reached until it is recreated.
	 */
	public AMap<AString, ACell> stored(Agent agent) {
		try {
			return validate(agent.getRecord().get(Agent.KEY_AGENT));
		} catch (InvalidInputException e) {
			throw new InvalidDefinitionException(MISCONFIGURED + e.getMessage(), e);
		}
	}

	public ARemoteAgentAdapter<?> backend(AMap<AString, ACell> definition) {
		AString name = definition == null ? null : RT.ensureString(definition.get(Fields.ADAPTER));
		AAdapter adapter = name == null ? null : engine.getAdapter(name.toString());
		if (!(adapter instanceof ARemoteAgentAdapter<?> remote))
			throw new UnavailableException(UNAVAILABLE + name);
		return remote;
	}

	public boolean isExternal(Agent agent) {
		AMap<AString, ACell> record = agent == null ? null : agent.getRecord();
		return record != null && record.containsKey(Agent.KEY_AGENT);
	}

	public void start(Job job, RequestContext caller, AString owner, AString id, AString address,
			Agent agent, String operation, ACell input, Blob sid, boolean supplied) {
		AMap<AString, ACell> definition = stored(agent);
		ARemoteAgentAdapter<?> adapter = backend(definition);
		for (String option : new String[]{"loads", "outputPath", "responseSchema", "strict"}) {
			if (RT.getIn(input, option) != null) throw new NotSupportedException(UNSUPPORTED + option);
		}
		if (supplied && agent.getSession(sid) == null) throw new NotFoundException(UNKNOWN_SESSION, sid.toHexString());
		ACell content = RT.getIn(input, "chat".equals(operation) ? Fields.MESSAGE : Fields.INPUT);
		engine.jobs().updateAdapterData(job, d -> d.assoc(OWNER, owner).assoc(KIND, Strings.create(operation)));
		job.update(d -> d.assoc(Fields.AGENT_ID, id).assoc(Fields.ADDRESS, address)
			.assoc(Fields.SESSION_ID, Strings.create(sid.toHexString())));
		if (job.isFinished()) return;
		if (!agent.claimExternalTurn(sid, job.getID(), caller.getCallerDID(), content, !supplied))
			throw new ConflictException(SESSION_BUSY);
		if (job.isFinished()) { agent.session(sid).finishExternalTurn(job.getID(), null, null); return; }
		try {
			ACell remoteSession = RT.getIn(agent.getSession(sid), Agent.KEY_EXTERNAL, Fields.SESSION_ID);
			var invocation = adapter.agentInvocation(operation, definition, content, remoteSession);
			AString backendOp = Strings.create(adapter.getName() + ":" + invocation.operation());
			AMap<AString, ACell> meta = Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, backendOp));
			// The resident agent's configured authority applies, never the waking caller's grants.
			RequestContext execution = engine.context(owner, Principals.agentDID(owner, id)).withJob(job)
				.withOp(Strings.create("v/ops/" + adapter.getName() + "/" + invocation.operation()));
			var prepared = engine.jobs().prepareAgentInteraction(meta, invocation.input(), execution);
			Job child = prepared.job();
			engine.jobs().updateAdapterData(job, d -> d.assoc(CHILD, child.getID()));
			attach(job, child, agent, sid, adapter);
			if (job.isFinished()) child.cancel();
			else job.setStatus(Status.STARTED);
			prepared.start();
		} catch (RuntimeException | Error e) {
			agent.session(sid).finishExternalTurn(job.getID(), null, null);
			throw e;
		}
	}

	private void attach(Job parent, Job child, Agent agent, Blob sid, ARemoteAgentAdapter<?> adapter) {
		AtomicBoolean settled = new AtomicBoolean();
		Consumer<Job> listener = j -> {
			try {
				if (j.isFinished()) {
					if (!settled.compareAndSet(false, true)) return;
					ACell response = Status.COMPLETE.equals(j.getStatus()) ? adapter.agentOutput(j) : null;
					agent.session(sid).finishExternalTurn(parent.getID(), adapter.agentSession(j), response);
					if (Status.COMPLETE.equals(j.getStatus())) {
						AMap<AString, ACell> data = parent.getData();
						AMap<AString, ACell> result = Maps.of(Fields.AGENT_ID, data.get(Fields.AGENT_ID),
							Fields.ADDRESS, data.get(Fields.ADDRESS), Fields.SESSION_ID, data.get(Fields.SESSION_ID));
						if (Strings.create("chat").equals(kept(parent, KIND))) {
							result = result.assoc(Fields.RESPONSE, response);
						} else {
							result = result.assoc(Fields.ID, Strings.create(parent.getID().toHexString()))
								.assoc(Fields.STATUS, Status.COMPLETE).assoc(Fields.OUTPUT, response);
						}
						parent.completeWith(result);
					} else if (Status.CANCELLED.equals(j.getStatus())) parent.cancel();
					else parent.fail(j.getErrorMessage() == null ? MISSING_CHILD : j.getErrorMessage());
				} else {
					// The parent records the child's status when it changes, and
					// its progress in place of the parent's own.
					AString status = Status.PENDING.equals(j.getStatus()) ? Status.STARTED : j.getStatus();
					parent.update(d -> status.equals(d.get(Fields.STATUS)) ? null : d.assoc(Fields.STATUS, status));
					parent.setProgress(j.getData().get(Fields.PROGRESS));
				}
			} catch (RuntimeException failure) {
				agent.session(sid).finishExternalTurn(parent.getID(), null, null);
				parent.fail(failure);
			}
		};
		child.subscribe(listener);
		children.put(parent.getID(), child);
		if (Status.CANCELLED.equals(parent.getStatus())) stop(parent);
		child.future().whenComplete((v, e) -> {
			child.unsubscribe(listener);
			children.remove(parent.getID(), child);
		});
		listener.accept(child);
	}

	/** Reattach by recorded handle only. The child adapter owns provider recovery. */
	public boolean recover(Job job) {
		if (!owns(job)) return false;
		AString owner = owner(job);
		User user = owner == null ? null : engine.getVenueState().users().user(owner);
		Agent agent = user == null ? null : user.agent(RT.ensureString(job.getData().get(Fields.AGENT_ID)));
		ACell childId = kept(job, CHILD);
		Job child = childId == null ? null : engine.jobs().getJob(Job.parseID(childId), engine.context(owner));
		if (agent == null || child == null) {
			job.fail(MISSING_CHILD);
			if (agent != null) agent.session(Blob.fromHex(job.getData().get(Fields.SESSION_ID).toString())).finishExternalTurn(job.getID(), null, null);
			return true;
		}
		attach(job, child, agent, Blob.fromHex(job.getData().get(Fields.SESSION_ID).toString()),
			backend(stored(agent)));
		return true;
	}

	/** No execution is replayed when startup finds an abandoned session claim. */
	public void reconcile(Agent agent) {
		for (var entry : agent.getSessions().entrySet()) {
			ACell id = RT.getIn(entry.getValue(), Agent.KEY_EXTERNAL, Fields.JOB_ID);
			if (id != null && engine.jobs().getJob(Job.parseID(id)) == null)
				agent.session(entry.getKey()).finishExternalTurn(Job.parseID(id), null, null);
		}
	}

	public void cancelAll(Agent agent) {
		for (var entry : agent.getSessions().entrySet()) {
			ACell id = RT.getIn(entry.getValue(), Agent.KEY_EXTERNAL, Fields.JOB_ID);
			Job parent = id == null ? null : engine.jobs().getJob(Job.parseID(id));
			if (parent != null) parent.cancel();
		}
	}

	/** Whether {@code job} is an unfinished call this component serves through an external agent's backend. */
	public boolean owns(Job job) { return kept(job, KIND) != null; }

	/** The DID of the user whose agent serves {@code job}, or null when this component does not serve it. */
	public AString owner(Job job) { return RT.ensureString(kept(job, OWNER)); }

	/** The backend job serving {@code job}, or null when none is started or this component does not serve it. */
	public Job child(Job job) {
		ACell id = kept(job, CHILD);
		return (id == null) ? null : engine.jobs().getJob(Job.parseID(id));
	}

	private ACell kept(Job job, AString key) {
		return RT.getIn(engine.jobs().adapterData(job), key);
	}

	public void deliver(Agent agent, ACell taskId, Blob sid, ACell content) {
		ACell id = taskId != null ? taskId : RT.getIn(agent.getSession(sid), Agent.KEY_EXTERNAL, Fields.JOB_ID);
		Job parent = id == null ? null : engine.jobs().getJob(Job.parseID(id));
		if (parent == null || !owns(parent) || parent.isFinished()
				|| !parent.getID().equals(RT.getIn(agent.getSession(sid), Agent.KEY_EXTERNAL, Fields.JOB_ID)))
			throw new NotSupportedException(NO_ACTIVE_TURN);
		Job child = child(parent);
		if (child == null) throw new NotSupportedException(MISSING_CHILD);
		backend(stored(agent)).handleMessage(child, Maps.of(Fields.MESSAGE, content));
	}
}
