package covia.adapter.whatsapp;

import java.time.Instant;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.messaging.AWebhookMessagingAdapter;
import covia.adapter.messaging.ConversationRouter;
import covia.adapter.messaging.MessagingHttp;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.WebhookBot;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.adapter.webhook.WebhookSignatures;

/** Optional WhatsApp Cloud API module: signed text intake and capability-gated sends. */
public final class WhatsAppAdapter extends AWebhookMessagingAdapter<BotSpec> {
	public static final String NAME = "whatsapp";
	public static final String WINDOW_EXPIRED = "WhatsApp text reply window has expired; use an approved template through another supported client";
	public static final String INVALID_RESPONSE = "WhatsApp did not return a message ID; delivery may be uncertain";
	public static final int MAX_TEXT = 4096;
	@Override public String getName() { return NAME; }
	@Override public String getDescription() { return "WhatsApp Cloud API text messages and signed inbound conversations, bound to an owner and phone number."; }
	@Override protected String defaultApiUrl() { return "https://graph.facebook.com"; }
	@Override protected BotSpec parseBot(String name, ACell settings, boolean strict) { return BotSpec.parse(name, settings, strict); }
	@Override protected WebhookResponse receive(WebhookBot<BotSpec> bot, WebhookRequest request) {
		if ("GET".equals(request.method())) {
			String token = bot.credential("verifyToken");
			if (token == null || token.isBlank()) return WebhookResponse.text(503, UNAVAILABLE);
			if (!"subscribe".equals(request.queryParam("hub.mode"))
				|| !WebhookSignatures.equal(token, request.queryParam("hub.verify_token"))) return WebhookResponse.text(403, UNAUTHORIZED);
			String challenge = request.queryParam("hub.challenge");
			if (challenge == null || challenge.isBlank()) return WebhookResponse.text(400, INVALID_REQUEST);
			return WebhookResponse.text(200, challenge);
		}
		if (!"POST".equals(request.method())) return WebhookResponse.text(405, INVALID_REQUEST);
		String secret = bot.credential("appSecret");
		if (secret == null || secret.isBlank()) return WebhookResponse.text(503, UNAVAILABLE);
		if (!WebhookSignatures.verify(secret, request.header("X-Hub-Signature-256"), "sha256=", "", request.body())) return WebhookResponse.text(401, UNAUTHORIZED);
		var payload = json(request);
		if (!"whatsapp_business_account".equals(MessagingSettings.required(payload, "object"))) return WebhookResponse.text(400, INVALID_REQUEST);
		for (ACell entryCell : array(payload, "entry")) {
			var entry = MessagingSettings.object(entryCell);
			if (!bot.spec().businessAccountId().equals(MessagingSettings.required(entry, "id"))) continue;
			for (ACell changeCell : array(entry, "changes")) {
				var change = MessagingSettings.object(changeCell);
				if (!"messages".equals(MessagingSettings.optional(change, "field"))) continue;
				var value = MessagingSettings.object(change.get(MessagingSettings.key("value")));
				if (!"whatsapp".equals(MessagingSettings.optional(value, "messaging_product"))) continue;
				var metadata = MessagingSettings.object(value.get(MessagingSettings.key("metadata")));
				if (!bot.spec().phoneNumberId().equals(MessagingSettings.required(metadata, "phone_number_id"))) continue;
				// Delivery statuses and unsupported message types are acknowledged without routing.
				for (ACell messageCell : array(value, "messages")) {
					var message = MessagingSettings.object(messageCell);
					if (!"text".equals(MessagingSettings.optional(message, "type"))) continue;
					String from = MessagingSettings.required(message, "from");
					if (!bot.spec().allows(from)) continue;
					String id = MessagingSettings.required(message, "id");
					String timestamp = MessagingSettings.id(message, "timestamp", "[0-9]{1,12}");
					long time = Long.parseLong(timestamp);
					long now = Instant.now().getEpochSecond();
					if (time > now + 300 || time < now - 86400) continue;
					String text = MessagingSettings.required(MessagingSettings.object(message.get(MessagingSettings.key("text"))), "body");
					if (text.codePointCount(0, text.length()) > MAX_TEXT) throw new IllegalArgumentException(TEXT_LENGTH.formatted(MAX_TEXT));
					var via = Maps.of("channel", NAME, "bot", bot.spec().name(), "from", from, "chat", from,
						"message_id", id, "phoneNumberId", bot.spec().phoneNumberId(),
						"access", MessagingSettings.bool(bot.spec().settings(), "open", false) ? "open" : "allow");
					bot.accept(id, Maps.of("conversation", ConversationRouter.key(bot.spec().installationKey(), from),
						"text", text, "to", from, "messageId", id, "timestamp", timestamp, "via", via,
						"input", Maps.of("message", message, "metadata", metadata, "business_account_id", bot.spec().businessAccountId())));
				}
			}
		}
		return WebhookResponse.text(200, "");
	}
	private static AVector<ACell> array(AMap<AString, ACell> map, String key) {
		ACell value = map.get(MessagingSettings.key(key));
		if (value == null) return Vectors.empty();
		AVector<ACell> values = RT.ensureVector(value);
		if (values == null) throw new IllegalArgumentException(INVALID_REQUEST);
		return values;
	}
	@Override protected ACell send(WebhookBot<BotSpec> bot, AMap<AString, ACell> input) {
		MessagingSettings.known(input, java.util.Set.of("bot", "to", "text", "reply_to"));
		String to = MessagingSettings.id(input, "to", BotSpec.PHONE);
		if (!bot.spec().allows(to)) throw new IllegalArgumentException(DESTINATION_DENIED);
		return deliver(bot, to, text(input, MAX_TEXT), MessagingSettings.optional(input, "reply_to"));
	}
	@Override protected void sendReply(WebhookBot<BotSpec> bot, AMap<AString, ACell> event, String text) {
		long timestamp = Long.parseLong(MessagingSettings.required(event, "timestamp"));
		if (Instant.now().getEpochSecond() - timestamp >= 86400) throw new IllegalStateException(WINDOW_EXPIRED);
		deliver(bot, MessagingSettings.required(event, "to"), truncate(text, MAX_TEXT), MessagingSettings.required(event, "messageId"));
	}
	private ACell deliver(WebhookBot<BotSpec> bot, String to, String text, String replyTo) {
		bot.requireReady();
		AMap<AString, ACell> body = Maps.of("messaging_product", "whatsapp", "recipient_type", "individual", "to", to,
			"type", "text", "text", Maps.of("body", text, "preview_url", false));
		if (replyTo != null) body = body.assoc(MessagingSettings.key("context"), Maps.of("message_id", replyTo));
		ACell response = MessagingHttp.post(apiUrl() + "/" + bot.spec().apiVersion() + "/" + bot.spec().phoneNumberId() + "/messages", bot.credential("token"), body);
		AVector<ACell> messages = RT.ensureVector(RT.getIn(response, MessagingSettings.key("messages")));
		if (messages == null || messages.isEmpty() || RT.ensureString(RT.getIn(messages.get(0), MessagingSettings.key("id"))) == null) throw new IllegalStateException(INVALID_RESPONSE);
		return response;
	}
}
