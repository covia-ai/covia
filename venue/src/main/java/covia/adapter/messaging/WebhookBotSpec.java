package covia.adapter.messaging;

import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;

/** Configuration retained as secret references, never resolved credentials. */
public interface WebhookBotSpec extends MessagingBotSpec {
	AMap<AString, ACell> settings();
	/** Provider account identity, independent of handler and admission policy. */
	String installationKey();
	Set<String> credentialFields();
	@Override default String userRef() { return MessagingSettings.required(settings(), "user"); }
	@Override default String agent() { return MessagingSettings.optional(settings(), "agent"); }
	@Override default String operation() { return MessagingSettings.optional(settings(), "operation"); }
	@Override default ACell reply() { return settings().get(MessagingSettings.key("reply")); }
}
