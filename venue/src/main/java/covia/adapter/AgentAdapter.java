package covia.adapter;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.User;
import covia.venue.Users;

/**
 * Adapter for agent lifecycle management.
 *
 * <p>Provides three operations:</p>
 * <ul>
 *   <li>{@code agent:create} — create and initialise an agent</li>
 *   <li>{@code agent:message} — deliver a message to an agent's inbox</li>
 *   <li>{@code agent:run} — run the agent update loop</li>
 * </ul>
 *
 * <p>All operations atomically replace the agent record with a new {@code ts}.
 * See {@code AGENT_LOOP.md} for design.</p>
 */
public class AgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(AgentAdapter.class);

	@Override
	public String getName() {
		return "agent";
	}

	@Override
	public String getDescription() {
		return "Manages agent lifecycle: create agents, deliver messages to their inbox, "
			+ "and run their transition loop to process messages. Agents are per-user, "
			+ "identified by human-readable names, with persistent state in the lattice.";
	}

	@Override
	protected void installAssets() {
		String BASE = "/adapters/agent/";
		installAsset(BASE + "create.json");
		installAsset(BASE + "message.json");
		installAsset(BASE + "run.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		throw new UnsupportedOperationException(
			"AgentAdapter requires caller DID — use invoke(Job, ...) path");
	}

	@Override
	public void invoke(Job job, String operation, ACell meta, ACell input) {
		AString callerDID = RT.ensureString(job.getData().get(Fields.CALLER));
		if (callerDID == null) {
			job.fail("Agent operations require an authenticated caller");
			return;
		}

		String op = operation.contains(":") ? operation.split(":")[1] : operation;

		try {
			switch (op) {
				case "create":
					handleCreate(job, input, callerDID);
					break;
				case "message":
					handleMessage(job, input, callerDID);
					break;
				case "run":
					handleRun(job, input, callerDID);
					break;
				default:
					job.fail("Unknown agent operation: " + op);
			}
		} catch (Exception e) {
			job.fail(e.getMessage());
		}
	}

	/**
	 * agent:create — create and initialise an agent.
	 */
	@SuppressWarnings("unchecked")
	private void handleCreate(Job job, ACell input, AString callerDID) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AMap<AString, ACell> config = (AMap<AString, ACell>) RT.getIn(input, Fields.CONFIG);

		Users users = engine.getVenueState().users();
		User user = users.ensure(callerDID);
		user.ensureAgent(agentId, config);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.STATUS, AgentState.SLEEPING
		));
	}

	/**
	 * agent:message — deliver a message to an agent's inbox.
	 */
	private void handleMessage(Job job, ACell input, AString callerDID) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		ACell message = RT.getIn(input, Fields.MESSAGE);

		Users users = engine.getVenueState().users();
		User user = users.get(callerDID);
		if (user == null) {
			job.fail("User not found: " + callerDID);
			return;
		}

		AgentState agent = user.agent(agentId);
		if (agent == null) {
			job.fail("Agent not found: " + agentId);
			return;
		}

		// Reject messages to terminated agents
		if (AgentState.TERMINATED.equals(agent.getStatus())) {
			job.fail("Agent is terminated: " + agentId);
			return;
		}

		agent.deliverMessage(message);

		job.setStatus(Status.STARTED);
		job.completeWith(Maps.of(
			Fields.AGENT_ID, agentId,
			Fields.DELIVERED, CVMBool.TRUE
		));
	}

	/**
	 * agent:run — run the agent update loop.
	 *
	 * <p>Follows the agent update sequence from AGENT_LOOP.md §4.3.</p>
	 */
	private void handleRun(Job job, ACell input, AString callerDID) {
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		if (agentId == null) {
			job.fail("agentId is required");
			return;
		}

		AString transitionOp = RT.ensureString(RT.getIn(input, Fields.OPERATION));
		if (transitionOp == null) {
			job.fail("operation is required");
			return;
		}

		job.setStatus(Status.STARTED);

		CompletableFuture.runAsync(() -> {
			try {
				Users users = engine.getVenueState().users();
				User user = users.get(callerDID);
				if (user == null) {
					job.fail("User not found: " + callerDID);
					return;
				}

				AgentState agent = user.agent(agentId);
				if (agent == null) {
					job.fail("Agent not found: " + agentId);
					return;
				}

				// Step 1: Read current agent record
				AMap<AString, ACell> record = agent.getRecord();
				AVector<ACell> inbox = agent.getInbox();

				// Step 2: If inbox is empty, no-op
				if (inbox == null || inbox.count() == 0) {
					job.completeWith(Maps.of(
						Fields.AGENT_ID, agentId,
						Fields.STATUS, agent.getStatus()
					));
					return;
				}

				// Step 3: Set status to RUNNING
				long startTs = Utils.getCurrentTimestamp();
				agent.setStatus(AgentState.RUNNING);

				// Step 4: Invoke transition function
				ACell transitionInput = Maps.of(
					Fields.AGENT_ID, agentId,
					AgentState.KEY_STATE, agent.getState(),
					Fields.MESSAGES, inbox
				);

				Job transitionJob = engine.jobs().invokeOperation(
					transitionOp, transitionInput, callerDID);
				ACell transitionResult = transitionJob.awaitResult();

				// Step 5: On success — update record atomically
				long endTs = Utils.getCurrentTimestamp();
				ACell newState = RT.getIn(transitionResult, AgentState.KEY_STATE);
				ACell result = RT.getIn(transitionResult, Fields.RESULT);

				// Build timeline entry
				AMap<AString, ACell> timelineEntry = Maps.of(
					Strings.intern("start"), CVMLong.create(startTs),
					Strings.intern("end"), CVMLong.create(endTs),
					Fields.OP, transitionOp,
					AgentState.KEY_STATE, record.get(AgentState.KEY_STATE), // starting state
					Fields.MESSAGES, inbox,
					Fields.RESULT, result
				);

				AVector<ACell> timeline = agent.getTimeline();
				if (timeline == null) timeline = Vectors.empty();

				// Write complete agent record
				AMap<AString, ACell> newRecord = record
					.assoc(AgentState.KEY_STATE, newState)
					.assoc(AgentState.KEY_INBOX, Vectors.empty())
					.assoc(AgentState.KEY_TIMELINE, timeline.conj(timelineEntry))
					.assoc(AgentState.KEY_STATUS, AgentState.SLEEPING)
					.dissoc(AgentState.KEY_ERROR);
				agent.putRecord(newRecord);

				job.completeWith(Maps.of(
					Fields.AGENT_ID, agentId,
					Fields.STATUS, AgentState.SLEEPING
				));

			} catch (Exception e) {
				// Step 6: On error — suspend agent, preserve inbox
				try {
					Users users = engine.getVenueState().users();
					User user = users.get(callerDID);
					if (user != null) {
						AgentState agent = user.agent(agentId);
						if (agent != null) {
							AMap<AString, ACell> record = agent.getRecord();
							if (record != null) {
								AMap<AString, ACell> errorRecord = record
									.assoc(AgentState.KEY_STATUS, AgentState.SUSPENDED)
									.assoc(AgentState.KEY_ERROR, Strings.create(e.getMessage()));
								agent.putRecord(errorRecord);
							}
						}
					}
				} catch (Exception inner) {
					log.warn("Failed to set agent error state", inner);
				}
				job.fail(e.getMessage());
			}
		}, VIRTUAL_EXECUTOR);
	}
}
