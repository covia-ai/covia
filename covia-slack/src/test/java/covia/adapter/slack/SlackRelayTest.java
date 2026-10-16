package covia.adapter.slack;

import static covia.adapter.slack.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.messaging.RelayBinding;
import covia.adapter.messaging.RelaySettings;
import covia.adapter.webhook.WebhookRequest;
import covia.adapter.webhook.WebhookResponse;

/**
 * The relay is provider-neutral: a Slack binding whose handler is {@code relay} links a
 * user by a DM, relays a channel mention to the principal, and the principal's answer
 * goes back into that channel's thread rather than to the user, with no Slack-specific
 * relay code. Nothing but the link is stored.
 */
class SlackRelayTest {

	static final AString ALICE = Strings.create("did:test:relay:alice");

	static AMap<AString, ACell> spec() {
		return Maps.of("token", "s/TOKEN", "signingSecret", "s/SIGNING", "user", OWNER, "appId", "A123", "teamId", "T123",
			"botUserId", "UBOT", "allowChannels", Vectors.of("C123"), "relay", CVMBool.TRUE);
	}

	static AMap<AString, ACell> payload(String id, ACell event) {
		return Maps.of("type", "event_callback", "event_id", id, "api_app_id", "A123", "team_id", "T123",
			"authorizations", Vectors.of(Maps.of("team_id", "T123", "user_id", "UBOT", "is_bot", true, "is_enterprise_install", false)), "event", event);
	}

	static AMap<AString, ACell> dm(String ts, String text) {
		return Maps.of("type", "message", "channel_type", "im", "user", "UALICE", "channel", "D123", "ts", ts, "text", text);
	}

	static AMap<AString, ACell> mention(String ts, String text) {
		return Maps.of("type", "app_mention", "user", "UALICE", "channel", "C123", "ts", ts, "text", "<@UBOT> " + text);
	}

	static AMap<AString, ACell> call(Fixture f, String action, String body) throws Exception {
		WebhookResponse r = f.adapter.handleRelay("c-main", action, ALICE,
			new WebhookRequest("POST", Map.of(), Map.of(), body.getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
		AMap<AString, ACell> answer = RT.castMap(JSON.parse(new String(r.body(), StandardCharsets.UTF_8)));
		return answer.assoc(Strings.create("status"), CVMLong.create(r.status()));
	}

	@Test void aSlackAppRelaysAMention_andTheAnswerGoesToItsThread() throws Exception {
		try (Fixture f = new Fixture(spec())) {
			AMap<AString, ACell> issued = call(f, RelayBinding.LINK, "{}");
			assertEquals(200, status(issued), issued.toString());
			String code = str(issued, "code");
			assertNull(RT.getIn(issued, "link"), "Slack offers no prefilled link");

			assertEquals(200, f.webhook(payload("Ev1", dm("1700000000.000100", "LINK " + code))).status());
			Sent linked = f.api.take();
			assertEquals("D123", str(linked.body(), "channel"), "the DM hears it is linked");
			assertEquals(RelaySettings.DEFAULT_NOTICES.get(RelayBinding.N_LINKED), str(linked.body(), "text"));
			assertEquals("UALICE", str(call(f, RelayBinding.STATUS, "{}"), "linked"));

			CompletableFuture<WebhookResponse> polling = f.adapter.handleRelay("c-main", RelayBinding.POLL, ALICE,
				new WebhookRequest("POST", Map.of(), Map.of(), "{\"wait_ms\":8000}".getBytes(StandardCharsets.UTF_8)));
			Thread.sleep(100);
			assertEquals(200, f.webhook(payload("Ev2", mention("1700000000.000200", "what is new?"))).status());
			AMap<AString, ACell> got = RT.ensureMap(JSON.parse(new String(polling.get(8, TimeUnit.SECONDS).body(), StandardCharsets.UTF_8)));
			AVector<ACell> messages = RT.ensureVector(got.get(Strings.create("messages")));
			assertEquals(1, messages.count(), got.toString());
			assertEquals("what is new?", str(messages.get(0), "text"));
			assertEquals("UALICE", str(RT.getIn(messages.get(0), "via"), "from"));
			assertEquals("C123", str(RT.getIn(messages.get(0), "via"), "chat"));
			assertEquals("1700000000.000200", str(RT.getIn(messages.get(0), "via"), "thread"));

			AMap<AString, ACell> replied = call(f, RelayBinding.REPLY, "{\"message_id\":\"Ev2\",\"text\":\"Nothing yet.\"}");
			assertEquals(200, status(replied), replied.toString());
			Sent answer = f.api.take();
			assertEquals("C123", str(answer.body(), "channel"), "the answer goes to the channel, not the sender");
			assertEquals("1700000000.000200", str(answer.body(), "thread_ts"), "in the message's thread");
			assertEquals("Nothing yet.", str(answer.body(), "text"));

			ACell stored = f.engine.resolvePath(Strings.create("w/adapters/" + SlackAdapter.NAME), f.engine.venueContext());
			assertNotNull(RT.getIn(stored, "config", "main", "relay"), "the link is stored");
			assertFalse(String.valueOf(stored).contains("what is new?"), "no message is stored");
			AVector<ACell> bots = RT.ensureVector(RT.getIn(f.run("bots", Maps.empty()), "bots"));
			assertEquals("/relay/slack/c-main", str(bots.get(0), "relayPath"));
		}
	}

	private static int status(AMap<AString, ACell> m) {
		return (int) RT.ensureLong(m.get(Strings.create("status"))).longValue();
	}

	private static String str(ACell m, Object... keys) {
		ACell v = RT.getIn(m, keys);
		return v == null ? null : v.toString();
	}
}
