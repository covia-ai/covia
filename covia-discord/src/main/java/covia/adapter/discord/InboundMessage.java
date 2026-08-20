package covia.adapter.discord;

import convex.core.data.ACell;
import convex.core.data.AVector;

/** Discord message data captured at the Gateway boundary, independent of JDA. */
record InboundMessage(String id, String channelId, String channelType, String channelName,
	String guildId, String guildName, String authorId, String username, String globalName,
	String content, boolean direct, boolean mentionedBot, AVector<ACell> attachments) {}
