package covia.adapter.whatsapp;

import static covia.adapter.whatsapp.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
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
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.messaging.MessagingMedia;
import covia.adapter.messaging.MessagingSettings;
import covia.adapter.webhook.WebhookInbox;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * WhatsApp media for agent bindings: a voice note is fetched with the binding's
 * token (lookup, then the short-lived link), stored in the owner's private
 * {@code whatsapp} drive, given to the binding's {@code mediaOperation}, and reaches
 * the agent as an attachment; a captioned photo also arrives as an image block;
 * operation bindings stay text-only; {@code mediaOperation} belongs to agent bindings.
 */
class WhatsAppMediaTest {

	static final String FROM = "441234567890";
	static final byte[] VOICE = "OggS-voice-note-bytes".getBytes(StandardCharsets.UTF_8);
	static final byte[] PHOTO = "jpeg-photo-bytes".getBytes(StandardCharsets.UTF_8);

	static AMap<AString, ACell> agentSpec(ACell mediaOperation) {
		AMap<AString, ACell> spec = Maps.of("user", OWNER, "token", "s/TOKEN", "appSecret", "s/SIGNING", "verifyToken", "s/VERIFY",
			"businessAccountId", "123", "phoneNumberId", "456", "apiVersion", "v25.0", "agent", "media-agent", "open", CVMBool.TRUE);
		return mediaOperation == null ? spec : spec.assoc(MessagingSettings.key("mediaOperation"), mediaOperation);
	}

	static ACell payload(ACell message) {
		return Maps.of("object", "whatsapp_business_account", "entry", Vectors.of(Maps.of("id", "123", "changes",
			Vectors.of(Maps.of("field", "messages", "value", Maps.of("messaging_product", "whatsapp",
				"metadata", Maps.of("phone_number_id", "456"), "messages", Vectors.of(message)))))));
	}

	static AMap<AString, ACell> media(String id, String type, ACell body) {
		return Maps.of("from", FROM, "id", id, "timestamp", Long.toString(Instant.now().getEpochSecond()), "type", type, type, body);
	}

	@Test void aVoiceNoteReachesTheAgentAsAnAttachment_transcribedByTheMediaOperation() throws Exception {
		try (Fixture f = new Fixture(agentSpec(Strings.create("v/ops/transcriber/run")))) {
			Agent agent = new Agent(); f.engine.registerAdapter(agent);
			Transcriber transcriber = new Transcriber(); f.engine.registerAdapter(transcriber);
			f.api.media.put("90001", new Media(VOICE, "audio/ogg"));
			assertEquals(200, f.webhook(payload(media("wamid.voice", "audio",
				Maps.of("id", "90001", "mime_type", "audio/ogg; codecs=opus", "voice", CVMBool.TRUE)))).status());
			AMap<AString, ACell> message = RT.ensureMap(agent.inputs.poll(8, TimeUnit.SECONDS).get(Fields.MESSAGE));
			assertEquals("", RT.getIn(message, "text").toString(), "a bare voice note has no text of its own");
			AVector<ACell> attachments = RT.ensureVector(message.get(MessagingMedia.K_ATTACHMENTS));
			assertEquals(1, attachments.count());
			AMap<AString, ACell> attachment = RT.ensureMap(attachments.get(0));
			assertEquals("audio", RT.getIn(attachment, "kind").toString());
			assertEquals(CVMBool.TRUE, attachment.get(MessagingSettings.key("voice")));
			assertEquals("transcribed words", RT.getIn(attachment, "text").toString(), "the media hook's text");
			String ref = RT.getIn(attachment, "ref").toString();
			assertTrue(ref.startsWith(MessagingMedia.ownerDirectory(OWNER, WhatsAppAdapter.NAME)), ref);
			assertTrue(ref.endsWith(".ogg"), ref);
			assertNull(message.get(Fields.CONTENT), "audio is not an image block");
			assertArrayEquals(VOICE, f.engine.resolveContent(Strings.create(ref), RequestContext.of(OWNER)).content().getInputStream().readAllBytes());
			assertTrue(String.valueOf(run(f.engine, OWNER, "v/ops/dlfs/list-drives", Maps.empty())).contains(WhatsAppAdapter.NAME),
				"the owner's whatsapp drive holds it");
			AMap<AString, ACell> hookInput = transcriber.inputs.poll(1, TimeUnit.SECONDS);
			assertEquals(ref, RT.getIn(hookInput, "ref").toString(), "the hook sees the stored reference");
			assertEquals(FROM, RT.getIn(hookInput, "via", "from").toString());

			Sent lookup = f.api.gets.poll(1, TimeUnit.SECONDS);
			Sent download = f.api.gets.poll(1, TimeUnit.SECONDS);
			assertEquals("/v25.0/90001", lookup.path());
			assertTrue(download.path().startsWith("/media/90001"), download.path());
			assertEquals("Bearer " + TOKEN, lookup.authorization());
			assertEquals("Bearer " + TOKEN, download.authorization());
			assertEquals(FROM, RT.getIn(f.api.take().body(), "to").toString(), "the agent's answer goes back");
			awaitStatus(f.bot(), "wamid.voice", WebhookInbox.COMPLETE);
		}
	}

	@Test void aCaptionedPhotoArrivesAsTextAndAnImageBlock() throws Exception {
		try (Fixture f = new Fixture(agentSpec(null))) {
			Agent agent = new Agent(); f.engine.registerAdapter(agent);
			f.api.media.put("90002", new Media(PHOTO, "image/jpeg"));
			f.webhook(payload(media("wamid.photo", "image", Maps.of("id", "90002", "mime_type", "image/jpeg", "caption", "What is this?"))));
			AMap<AString, ACell> message = RT.ensureMap(agent.inputs.poll(8, TimeUnit.SECONDS).get(Fields.MESSAGE));
			assertEquals("What is this?", RT.getIn(message, "text").toString());
			AVector<ACell> content = RT.ensureVector(message.get(Fields.CONTENT));
			assertEquals(1, content.count());
			assertEquals("image", RT.getIn(content.get(0), "type").toString());
			assertEquals(RT.getIn(firstAttachment(message), "ref"), RT.getIn(content.get(0), "source", "ref"));
			assertNull(RT.getIn(firstAttachment(message), "text"), "no media hook, no text");
		}
	}

	@Test void aDocumentKeepsASafeNameButNeverAPath() throws Exception {
		try (Fixture f = new Fixture(agentSpec(null))) {
			Agent agent = new Agent(); f.engine.registerAdapter(agent);
			f.api.media.put("90003", new Media(PHOTO, "application/pdf"));
			f.api.media.put("90004", new Media(PHOTO, "application/pdf"));
			f.webhook(payload(media("wamid.doc1", "document", Maps.of("id", "90003", "mime_type", "application/pdf", "filename", "report.pdf"))));
			f.webhook(payload(media("wamid.doc2", "document", Maps.of("id", "90004", "mime_type", "application/pdf", "filename", "../escape.pdf"))));
			String first = RT.getIn(firstAttachment(RT.ensureMap(agent.inputs.poll(8, TimeUnit.SECONDS).get(Fields.MESSAGE))), "fileName").toString();
			String second = RT.getIn(firstAttachment(RT.ensureMap(agent.inputs.poll(8, TimeUnit.SECONDS).get(Fields.MESSAGE))), "fileName").toString();
			assertEquals("report.pdf", first);
			assertEquals(MessagingMedia.fallbackName("main", "90004", "application/pdf"), second, "an unsafe name is never used");
		}
	}

	@Test void blankSenderDescriptionsNeverRefuseTheBatch() throws Exception {
		try (Fixture f = new Fixture(agentSpec(null))) {
			Agent agent = new Agent(); f.engine.registerAdapter(agent);
			f.api.media.put("90006", new Media(PHOTO, "application/pdf"));
			assertEquals(200, f.webhook(payload(media("wamid.blank", "document",
				Maps.of("id", "90006", "mime_type", "", "caption", " ", "filename", "")))).status());
			AMap<AString, ACell> message = RT.ensureMap(agent.inputs.poll(8, TimeUnit.SECONDS).get(Fields.MESSAGE));
			assertEquals("", RT.getIn(message, "text").toString(), "a blank caption is no text");
			String name = RT.getIn(firstAttachment(message), "fileName").toString();
			assertEquals(MessagingMedia.fallbackName("main", "90006", "application/pdf"), name,
				"the type comes from Meta's lookup, the name from the binding");
		}
	}

	@Test void operationBindingsStayTextOnly_andMediaOperationNeedsAnAgent() throws Exception {
		AMap<AString, ACell> operation = agentSpec(null).dissoc(MessagingSettings.key("agent"))
			.assoc(MessagingSettings.key("operation"), Strings.create("v/test/ops/echo"));
		try (Fixture f = new Fixture(operation)) {
			f.api.media.put("90005", new Media(VOICE, "audio/ogg"));
			assertEquals(200, f.webhook(payload(media("wamid.op-voice", "audio", Maps.of("id", "90005", "mime_type", "audio/ogg")))).status());
			assertNull(f.bot().inbox().get("wamid.op-voice"), "nothing is accepted");
			assertNull(f.api.gets.poll(200, TimeUnit.MILLISECONDS), "nothing is fetched");
		}
		assertEquals(MessagingMedia.AGENT_ONLY, assertThrows(IllegalArgumentException.class, () -> BotSpec.parse("main",
			operation.assoc(MessagingSettings.key("mediaOperation"), Strings.create("v/test/ops/echo")), true)).getMessage());
	}

	static AMap<AString, ACell> firstAttachment(AMap<AString, ACell> message) {
		return RT.ensureMap(RT.ensureVector(message.get(MessagingMedia.K_ATTACHMENTS)).get(0));
	}

	/** Records each turn and answers it. */
	static final class Agent extends AAdapter {
		final LinkedBlockingQueue<AMap<AString, ACell>> inputs = new LinkedBlockingQueue<>();
		@Override public String getName() { return "agent"; }
		@Override public String getDescription() { return "Media turn fixture"; }
		@Override protected void installAssets() { installAsset("agent/chat", Maps.of("name", "Fixture chat", "operation", Maps.of("adapter", "agent:chat"))); }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			inputs.add(MessagingSettings.object(input));
			return CompletableFuture.completedFuture(Maps.of(Fields.SESSION_ID, "media-session", Fields.RESPONSE, "heard you"));
		}
	}

	/** A media hook: records its input and returns a transcription. */
	static final class Transcriber extends AAdapter {
		final LinkedBlockingQueue<AMap<AString, ACell>> inputs = new LinkedBlockingQueue<>();
		@Override public String getName() { return "transcriber"; }
		@Override public String getDescription() { return "Transcription fixture"; }
		@Override protected void installAssets() {
			installAsset("transcriber/run", Maps.of("name", "Fixture transcription", "operation", Maps.of("adapter", "transcriber:run")));
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			inputs.add(MessagingSettings.object(input));
			return CompletableFuture.completedFuture(Maps.of("text", "transcribed words", "language", "en"));
		}
	}
}
