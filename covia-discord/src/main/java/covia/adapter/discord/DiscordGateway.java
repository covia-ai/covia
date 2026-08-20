package covia.adapter.discord;

import java.util.function.Consumer;

interface DiscordGateway extends AutoCloseable {
	String botId();
	String username();
	@Override void close();
	interface Factory { DiscordGateway connect(String token, Consumer<InboundMessage> messages) throws Exception; }
}
