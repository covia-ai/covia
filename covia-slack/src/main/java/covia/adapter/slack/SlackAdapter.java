package covia.adapter.slack;

import java.time.Instant;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.messaging.AWebhookMessagingAdapter;
import covia.adapter.messaging.ConversationRouter;
import covia.adapter.messaging.MessagingHttp;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.WebhookBot;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;
import covia.adapter.webhook.WebhookSignatures;

/** Optional Slack Events API module for DMs and explicitly admitted channel mentions. */
public final class SlackAdapter extends AWebhookMessagingAdapter<BotSpec> {
	public static final String NAME = "slack";
	public static final int MAX_TEXT = 4000;
	public static final String API_ERROR = "Slack rejected the message";
	public static final String INVALID_RESPONSE = "Slack did not return a message timestamp; delivery may be uncertain";
	@Override public String getName() { return NAME; }
	@Override public String getDescription() { return "Slack text messages, direct conversations and channel mentions through signed Events API callbacks."; }
	@Override protected String defaultApiUrl() { return "https://slack.com/api"; }
	@Override protected BotSpec parseBot(String name, ACell settings, boolean strict) { return BotSpec.parse(name, settings, strict); }
	@Override protected WebhookResponse receive(WebhookBot<BotSpec> bot, WebhookRequest request) {
		if (!"POST".equals(request.method())) return WebhookResponse.text(405, INVALID_REQUEST);
		String timestamp = request.header("X-Slack-Request-Timestamp");
		if (timestamp == null || !timestamp.matches("[0-9]{1,12}")) return WebhookResponse.text(401, UNAUTHORIZED);
		long time = Long.parseLong(timestamp), now = Instant.now().getEpochSecond();
		if (time < now - 300 || time > now + 300) return WebhookResponse.text(401, UNAUTHORIZED);
		String secret = bot.credential("signingSecret");
		if (secret == null || secret.isBlank()) return WebhookResponse.text(503, UNAVAILABLE);
		if (!WebhookSignatures.verify(secret, request.header("X-Slack-Signature"), "v0=", "v0:" + timestamp + ":", request.body())) return WebhookResponse.text(401, UNAUTHORIZED);
		var payload = json(request);
		String type = MessagingSettings.required(payload, "type");
		// Slack's signed URL challenge does not contain installation IDs.
		if (type.equals("url_verification")) return WebhookResponse.text(200, MessagingSettings.required(payload, "challenge"));
		if (!type.equals("event_callback")) return WebhookResponse.text(200, "");
		if (!bot.spec().appId().equals(MessagingSettings.required(payload, "api_app_id"))
			|| !bot.spec().teamId().equals(MessagingSettings.required(payload, "team_id")) || !authorizedInstallation(bot.spec(), payload)) return WebhookResponse.text(403, UNAUTHORIZED);
		var event = MessagingSettings.object(payload.get(MessagingSettings.key("event")));
		String eventType = MessagingSettings.required(event, "type");
		if (!eventType.equals("message") && !eventType.equals("app_mention")) return WebhookResponse.text(200, "");
		// Edits/deletes, bot messages and self echoes do not start conversations.
		if (event.get(MessagingSettings.key("subtype")) != null || event.get(MessagingSettings.key("bot_id")) != null) return WebhookResponse.text(200, "");
		String user = MessagingSettings.optional(event, "user");
		if (!bot.spec().allowsUser(user) || bot.spec().botUserId().equals(user)) return WebhookResponse.text(200, "");
		String channel = MessagingSettings.id(event, "channel", BotSpec.CHANNEL_ID);
		boolean dm = "im".equals(MessagingSettings.optional(event, "channel_type")) && channel.startsWith("D");
		// Subscribe to message.im and app_mention. Other channel message subscriptions
		// are ignored so the same mention cannot execute twice under two event IDs.
		if (!(dm && eventType.equals("message")) && !(eventType.equals("app_mention") && !channel.startsWith("D") && bot.spec().allowsChannel(channel))) return WebhookResponse.text(200, "");
		String ts = MessagingSettings.id(event, "ts", BotSpec.TIMESTAMP);
		String thread = MessagingSettings.optional(event, "thread_ts");
		if (thread != null) MessagingSettings.id(event, "thread_ts", BotSpec.TIMESTAMP);
		if (!dm && thread == null) thread = ts;
		String text = MessagingSettings.required(event, "text");
		if (eventType.equals("app_mention")) text = text.replace("<@" + bot.spec().botUserId() + ">", "").strip();
		if (text.isBlank()) return WebhookResponse.text(200, "");
		String id = MessagingSettings.required(payload, "event_id");
		var via = Maps.of("channel", NAME, "bot", bot.spec().name(), "from", user, "chat", channel,
			"message_id", ts, "event_id", id, "team", bot.spec().teamId(), "thread", thread,
			"access", MessagingSettings.bool(bot.spec().settings(), "open", false) ? "open" : "allow");
		bot.accept(id, Maps.of("conversation", ConversationRouter.key(bot.spec().installationKey(), channel, thread == null ? "" : thread),
			"text", text, "channel", channel, "thread", thread, "via", via,
			"input", Maps.of("event_id", id, "team_id", bot.spec().teamId(), "api_app_id", bot.spec().appId(), "event", event)));
		return WebhookResponse.text(200, "");
	}
	private static boolean authorizedInstallation(BotSpec spec, AMap<AString, ACell> payload) {
		AVector<ACell> auths = RT.ensureVector(payload.get(MessagingSettings.key("authorizations")));
		if (auths == null) return false;
		for (ACell entry : auths) {
			var auth = MessagingSettings.object(entry);
			// Slack may report a user installation in this abbreviated list even
			// when the app also has a bot token. The signed app/team pair identifies
			// our workspace binding; user_id here is not the event's sender.
			String team = MessagingSettings.optional(auth, "team_id");
			if ((team == null || spec.teamId().equals(team))
				&& !CVMBool.TRUE.equals(auth.get(MessagingSettings.key("is_enterprise_install")))
				&& (spec.enterpriseId() == null || spec.enterpriseId().equals(MessagingSettings.optional(auth, "enterprise_id")))) return true;
		}
		return false;
	}
	@Override protected ACell send(WebhookBot<BotSpec> bot, AMap<AString, ACell> input) {
		MessagingSettings.known(input, java.util.Set.of("bot", "channel", "text", "thread_ts"));
		String channel = MessagingSettings.id(input, "channel", BotSpec.CHANNEL_ID);
		if (!bot.spec().allowsChannel(channel)) throw new IllegalArgumentException(DESTINATION_DENIED);
		String thread = MessagingSettings.optional(input, "thread_ts");
		if (thread != null) MessagingSettings.id(input, "thread_ts", BotSpec.TIMESTAMP);
		return deliver(bot, channel, text(input, MAX_TEXT), thread);
	}
	@Override protected void sendReply(WebhookBot<BotSpec> bot, AMap<AString, ACell> event, String text) {
		deliver(bot, MessagingSettings.required(event, "channel"), truncate(text, MAX_TEXT), MessagingSettings.optional(event, "thread"));
	}
	private ACell deliver(WebhookBot<BotSpec> bot, String channel, String text, String thread) {
		bot.requireReady();
		text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		AMap<AString, ACell> body = Maps.of("channel", channel, "text", text, "mrkdwn", false, "parse", "none", "link_names", false,
			"unfurl_links", false, "unfurl_media", false);
		if (thread != null) body = body.assoc(MessagingSettings.key("thread_ts"), convex.core.data.Strings.create(thread));
		ACell result = MessagingHttp.post(apiUrl() + "/chat.postMessage", bot.credential("token"), body);
		if (!CVMBool.TRUE.equals(RT.getIn(result, MessagingSettings.key("ok")))) throw new IllegalStateException(API_ERROR);
		if (RT.ensureString(RT.getIn(result, MessagingSettings.key("ts"))) == null) throw new IllegalStateException(INVALID_RESPONSE);
		return result;
	}
}
