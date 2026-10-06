package covia.adapter.whatsapp;

import java.util.HashSet;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.adapter.messaging.ConversationRouter;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.messaging.WebhookBotSpec;

/** One WhatsApp Business Account / phone number, bound to a venue principal. */
public record BotSpec(String name, AMap<AString, ACell> settings) implements WebhookBotSpec {
	static final Set<String> CREDENTIALS = Set.of("token", "appSecret", "verifyToken");
	static final String PHONE = "[0-9]{5,20}";
	static BotSpec parse(String name, ACell input, boolean strict) {
		var map = MessagingSettings.object(input);
		var known = new HashSet<>(MessagingSettings.COMMON);
		known.addAll(Set.of("appSecret", "verifyToken", "phoneNumberId", "businessAccountId", "apiVersion"));
		MessagingSettings.validate(name, map, known, CREDENTIALS, strict);
		MessagingSettings.id(map, "phoneNumberId", "[0-9]+");
		MessagingSettings.id(map, "businessAccountId", "[0-9]+");
		MessagingSettings.id(map, "apiVersion", "v[0-9]+\\.[0-9]+");
		MessagingSettings.ids(map, "allow", PHONE);
		return new BotSpec(name, map);
	}
	public String phoneNumberId() { return MessagingSettings.required(settings, "phoneNumberId"); }
	public String businessAccountId() { return MessagingSettings.required(settings, "businessAccountId"); }
	public String apiVersion() { return MessagingSettings.required(settings, "apiVersion"); }
	public boolean allows(String from) {
		return from != null && from.matches(PHONE) && (MessagingSettings.bool(settings, "open", false)
			|| MessagingSettings.ids(settings, "allow", PHONE).contains(from));
	}
	@Override public Set<String> credentialFields() { return CREDENTIALS; }
	@Override public String installationKey() { return ConversationRouter.key(businessAccountId(), phoneNumberId()); }
	@Override public String toString() { return "WhatsAppBot[" + name + "]"; }
}
