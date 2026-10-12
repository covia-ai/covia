package covia.adapter.remote;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.client.VenueHTTP;

/**
 * A proxy for a Covia agent on an already authenticated Venue, including
 * LocalVenue. The native agent reference is resolved by the destination under
 * that Venue's authority. Conversations and task IDs remain separate.
 */
public final class VenueRemoteAgent {
	public static final String AGENT_REQUIRED = "agentId is required";
	private final Venue venue;
	private final AString agent;

	public VenueRemoteAgent(Venue venue, AString agent) {
		this.venue = Objects.requireNonNull(venue);
		if (agent == null || agent.toString().isBlank()) throw new IllegalArgumentException(AGENT_REQUIRED);
		this.agent = agent;
	}

	/** Submit once and return the task handle, without SDK background result polling. */
	public CompletableFuture<Job> request(AMap<AString, ACell> input) {
		return submit("request", input);
	}

	public CompletableFuture<Job> submit(String operation, AMap<AString, ACell> input) {
		if (!"request".equals(operation) && !"chat".equals(operation)) throw new IllegalArgumentException(operation);
		AMap<AString, ACell> params = input.assoc(Fields.AGENT_ID, agent)
			.assoc(Fields.TIMEOUT, CVMLong.ZERO);
		return venue instanceof VenueHTTP http
			? http.startJobAsync(Strings.create("v/ops/agent/" + operation), params)
			: venue.invoke("v/ops/agent/" + operation, params);
	}

	/** Observe a previously accepted task. */
	public CompletableFuture<AMap<AString, ACell>> task(Blob id) { return venue.getJobStatus(id); }

	/** Delivery acknowledgement is separate from execution completion. */
	public void deliver(Blob id, AMap<AString, ACell> message) { venue.sendMessage(id.toHexString(), message); }

	/** Request cancellation and return the destination's response. */
	public AMap<AString, ACell> cancel(Blob id) { return venue.cancelJob(id); }
}
