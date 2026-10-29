package covia.adapter.slack;

import java.util.HashSet;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.messaging.ConversationRouter;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.WebhookBotSpec;

/**
 * Workspace-scoped Slack installation; channel visibility is a separate admission gate.
 * A signing secret enables Events API callbacks and an app token Socket Mode.
 */
public record BotSpec(String name, AMap<AString, ACell> settings) implements WebhookBotSpec {
	public static final String INGRESS_REQUIRED = "signingSecret (Events API) or appToken (Socket Mode) is required";
	static final String TOKEN = "token", SIGNING_SECRET = "signingSecret", APP_TOKEN = "appToken";
	static final String USER_ID = "[UW][A-Z0-9]+", CHANNEL_ID = "[CDG][A-Z0-9]+", TIMESTAMP = "[0-9]+\\.[0-9]+";
	static BotSpec parse(String name, ACell input, boolean strict) {
		var spec = new BotSpec(name, MessagingSettings.object(input));
		var map = spec.settings();
		if (!spec.webhookIngress() && !spec.connectionIngress()) throw new IllegalArgumentException(INGRESS_REQUIRED);
		var known = new HashSet<>(MessagingSettings.COMMON);
		known.addAll(Set.of(SIGNING_SECRET, APP_TOKEN, "appId", "teamId", "botUserId", "allowChannels", "enterpriseId"));
		MessagingSettings.validate(name, map, known, spec.credentialFields(), strict);
		MessagingSettings.id(map, "appId", "A[A-Z0-9]+");
		MessagingSettings.id(map, "teamId", "T[A-Z0-9]+");
		MessagingSettings.id(map, "botUserId", USER_ID);
		if (MessagingSettings.optional(map, "enterpriseId") != null) MessagingSettings.id(map, "enterpriseId", "E[A-Z0-9]+");
		MessagingSettings.ids(map, "allow", USER_ID);
		MessagingSettings.ids(map, "allowChannels", CHANNEL_ID);
		return spec;
	}
	public String appId() { return MessagingSettings.required(settings, "appId"); }
	public String teamId() { return MessagingSettings.required(settings, "teamId"); }
	public String botUserId() { return MessagingSettings.required(settings, "botUserId"); }
	public String enterpriseId() { return MessagingSettings.optional(settings, "enterpriseId"); }
	public boolean allowsUser(String id) {
		return id != null && id.matches(USER_ID) && (admitsAnySender() || listsUser(id));
	}
	/** Whether the allow-list names this user, whom the binding then identifies as its principal. */
	public boolean listsUser(String id) {
		return id != null && MessagingSettings.ids(settings, "allow", USER_ID).contains(id);
	}
	public boolean allowsChannel(String id) { return MessagingSettings.ids(settings, "allowChannels", CHANNEL_ID).contains(id); }
	@Override public boolean webhookIngress() { return settings.containsKey(MessagingSettings.key(SIGNING_SECRET)); }
	@Override public boolean connectionIngress() { return settings.containsKey(MessagingSettings.key(APP_TOKEN)); }
	@Override public Set<String> credentialFields() {
		if (!connectionIngress()) return Set.of(TOKEN, SIGNING_SECRET);
		return webhookIngress() ? Set.of(TOKEN, SIGNING_SECRET, APP_TOKEN) : Set.of(TOKEN, APP_TOKEN);
	}
	@Override public String installationKey() { return ConversationRouter.key(appId(), teamId(), enterpriseId() == null ? "" : enterpriseId()); }
	@Override public String toString() { return "SlackBot[" + name + "]"; }
}
