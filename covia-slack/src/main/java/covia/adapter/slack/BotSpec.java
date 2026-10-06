package covia.adapter.slack;

import java.util.HashSet;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.messaging.ConversationRouter;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.WebhookBotSpec;

/** Workspace-scoped Slack installation; channel visibility is a separate admission gate. */
public record BotSpec(String name, AMap<AString, ACell> settings) implements WebhookBotSpec {
	static final Set<String> CREDENTIALS = Set.of("token", "signingSecret");
	static final String USER_ID = "[UW][A-Z0-9]+", CHANNEL_ID = "[CDG][A-Z0-9]+", TIMESTAMP = "[0-9]+\\.[0-9]+";
	static BotSpec parse(String name, ACell input, boolean strict) {
		var map = MessagingSettings.object(input);
		var known = new HashSet<>(MessagingSettings.COMMON);
		known.addAll(Set.of("signingSecret", "appId", "teamId", "botUserId", "allowChannels", "enterpriseId"));
		MessagingSettings.validate(name, map, known, CREDENTIALS, strict);
		MessagingSettings.id(map, "appId", "A[A-Z0-9]+");
		MessagingSettings.id(map, "teamId", "T[A-Z0-9]+");
		MessagingSettings.id(map, "botUserId", USER_ID);
		if (MessagingSettings.optional(map, "enterpriseId") != null) MessagingSettings.id(map, "enterpriseId", "E[A-Z0-9]+");
		MessagingSettings.ids(map, "allow", USER_ID);
		MessagingSettings.ids(map, "allowChannels", CHANNEL_ID);
		return new BotSpec(name, map);
	}
	public String appId() { return MessagingSettings.required(settings, "appId"); }
	public String teamId() { return MessagingSettings.required(settings, "teamId"); }
	public String botUserId() { return MessagingSettings.required(settings, "botUserId"); }
	public String enterpriseId() { return MessagingSettings.optional(settings, "enterpriseId"); }
	public boolean allowsUser(String id) {
		return id != null && id.matches(USER_ID) && (MessagingSettings.bool(settings, "open", false)
			|| MessagingSettings.ids(settings, "allow", USER_ID).contains(id));
	}
	public boolean allowsChannel(String id) { return MessagingSettings.ids(settings, "allowChannels", CHANNEL_ID).contains(id); }
	@Override public Set<String> credentialFields() { return CREDENTIALS; }
	@Override public String installationKey() { return ConversationRouter.key(appId(), teamId(), enterpriseId() == null ? "" : enterpriseId()); }
	@Override public String toString() { return "SlackBot[" + name + "]"; }
}
