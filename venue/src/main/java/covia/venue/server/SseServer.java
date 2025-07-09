package covia.venue.server;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import covia.venue.Venue;
import io.javalin.http.sse.SseClient;

public class SseServer {
	
	public static final Logger log=LoggerFactory.getLogger(SseServer.class);

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
		client.onClose(()->{
			clients.remove(client);
			log.info("SSE Client closed: "+client);
		});
		log.info("SSE Client connected: "+client);
	}
}
