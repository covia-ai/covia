package covia.venue.server;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import covia.venue.Venue;
import io.javalin.http.sse.SseClient;

public class SseServer {

	private ConcurrentLinkedQueue<SseClient> clients = new ConcurrentLinkedQueue<SseClient>();
	
	public Consumer<SseClient> registerSSE = client -> {
		addClient(client);
	};

	protected Venue venue;

	public SseServer(Venue venue) {
		this.venue=venue;
	}

	private void addClient(SseClient client) {
		clients.add(client);
		client.onClose(()->clients.remove(client));
	}
}
