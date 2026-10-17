package covia.adapter.whatsapp;

import static covia.adapter.whatsapp.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.messaging.MessagingMedia;
import covia.adapter.messaging.MessagingSettings;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.RequestContext;

/**
 * {@code whatsapp:download}: a message's media by its media id, fetched with the
 * binding's token (lookup, then the short-lived link), stored by default in the
 * owner's private {@code whatsapp} drive or, with the caller's own authority, in a
 * directory the caller names; gated on {@code whatsapp/download}, which
 * {@code whatsapp/send} does not grant.
 */
class WhatsAppDownloadTest {

	static final String OP = "v/ops/whatsapp/download";
	static final byte[] VOICE = "OggS-downloaded-voice-note".getBytes(StandardCharsets.UTF_8);

	/** The failed job's error message, so a refusal is compared with its constant. */
	static String refusal(Fixture f, RequestContext ctx, ACell input) {
		Job job = f.engine.jobs().invokeOperation(OP, input, ctx);
		try { job.awaitResult(10_000); } catch (Exception expected) { }
		assertEquals(Status.FAILED, job.getStatus(), "expected a refusal of " + input);
		return job.getErrorMessage();
	}

	static byte[] content(Fixture f, ACell saved) throws Exception {
		return f.engine.resolveContent(RT.ensureString(RT.getIn(saved, "ref")), RequestContext.of(OWNER)).content().getInputStream().readAllBytes();
	}

	static String text(ACell saved, String field) { return RT.getIn(saved, field).toString(); }

	@Test void savesToTheOwnersPrivateDriveByDefault_neverOverwriting() throws Exception {
		try (Fixture f = new Fixture(WhatsAppAdapterTest.spec())) {
			f.api.media.put("91001", new Media(VOICE, "audio/ogg"));
			ACell first = f.run("download", Maps.of("media_id", "91001"));
			String name = MessagingMedia.fallbackName("main", "91001", "audio/ogg");
			assertEquals(MessagingMedia.ownerDirectory(OWNER, WhatsAppAdapter.NAME) + name, text(first, "ref"));
			assertEquals(name, text(first, "fileName"));
			assertEquals("91001", text(first, "media_id"));
			assertEquals("audio/ogg", text(first, "contentType"));
			assertEquals(VOICE.length, RT.ensureLong(RT.getIn(first, "size")).longValue());
			assertArrayEquals(VOICE, content(f, first));

			Sent lookup = f.api.gets.poll(1, TimeUnit.SECONDS);
			Sent download = f.api.gets.poll(1, TimeUnit.SECONDS);
			assertEquals("/v25.0/91001", lookup.path());
			assertTrue(download.path().startsWith("/media/91001"), download.path());
			assertEquals("Bearer " + TOKEN, lookup.authorization());
			assertEquals("Bearer " + TOKEN, download.authorization());

			ACell second = f.run("download", Maps.of("media_id", "91001"));
			assertEquals(MessagingMedia.collisionName(name, 1), text(second, "fileName"), "a second copy never replaces the first");
			assertArrayEquals(VOICE, content(f, first));
		}
	}

	@Test void anExplicitDestinationIsWrittenWithTheCallersAuthority() throws Exception {
		try (Fixture f = new Fixture(WhatsAppAdapterTest.spec())) {
			f.api.media.put("91002", new Media(VOICE, "audio/ogg"));
			ACell saved = f.run("download", Maps.of("media_id", "91002", "destination", "dlfs/imports", "fileName", "note.ogg"));
			assertEquals("dlfs/imports/note.ogg", text(saved, "ref"));
			assertEquals("note.ogg", text(saved, "fileName"));
			assertArrayEquals(VOICE, content(f, saved));

			// The binding's owner authority never stands in for the caller's at a named destination.
			refusal(f, RequestContext.of(OWNER), Maps.of("media_id", "91002", "destination", OTHER + "/dlfs/imports", "fileName", "note.ogg"));
			assertFalse(String.valueOf(run(f.engine, OTHER, "v/ops/dlfs/list-drives", Maps.empty())).contains("imports"),
				"nothing is written to another user's drive");
			assertEquals(MessagingMedia.INVALID_DESTINATION, refusal(f, RequestContext.of(OWNER), Maps.of("media_id", "91002", "destination", " ")));
		}
	}

	@Test void anUnsafeFileNameOrMissingIdIsRefusedBeforeAnythingIsFetched() throws Exception {
		try (Fixture f = new Fixture(WhatsAppAdapterTest.spec())) {
			f.api.media.put("91003", new Media(VOICE, "audio/ogg"));
			for (String unsafe : new String[] { "../escape.ogg", "nested/note.ogg", "CON.ogg" }) {
				assertEquals(MessagingMedia.INVALID_FILE_NAME, refusal(f, RequestContext.of(OWNER), Maps.of("media_id", "91003", "fileName", unsafe)), unsafe);
			}
			assertEquals(MessagingSettings.REQUIRED.formatted("media_id"), refusal(f, RequestContext.of(OWNER), Maps.of("fileName", "note.ogg")));
			assertNull(f.api.gets.poll(100, TimeUnit.MILLISECONDS), "nothing is fetched");
		}
	}

	@Test void downloadingNeedsItsOwnAbility_whichSendingDoesNotGrant() throws Exception {
		try (Fixture f = new Fixture(WhatsAppAdapterTest.spec())) {
			f.api.media.put("91004", new Media(VOICE, "audio/ogg"));
			String ability = WhatsAppAdapter.ABILITY_DOWNLOAD.toString();
			String denied = refusal(f, RequestContext.of(OTHER), Maps.of("bot", "main", "media_id", "91004"));
			assertTrue(denied.contains(ability), denied);

			AString binding = Strings.create(OWNER + "/whatsapp/main");
			ACell invoke = Capability.create(Strings.create(OP), Strings.create("invoke"));
			RequestContext sendOnly = RequestContext.of(OWNER).withCaps(Vectors.of(invoke, Capability.create(binding, Strings.create("whatsapp/send"))));
			denied = refusal(f, sendOnly, Maps.of("media_id", "91004"));
			assertTrue(denied.contains(ability), denied);
			assertNull(f.api.gets.poll(100, TimeUnit.MILLISECONDS), "a refused caller fetches nothing");

			RequestContext downloadOnly = RequestContext.of(OWNER).withCaps(Vectors.of(invoke, Capability.create(binding, WhatsAppAdapter.ABILITY_DOWNLOAD)));
			ACell saved = f.engine.jobs().invokeOperation(OP, Maps.of("media_id", "91004"), downloadOnly).awaitResult(10_000);
			assertArrayEquals(VOICE, content(f, saved));
		}
	}

	@Test void mediaOverTheLimitIsRefusedBeforeItsBytesAreRequested() throws Exception {
		try (Fixture f = new Fixture(WhatsAppAdapterTest.spec())) {
			f.api.media.put("91005", new Media(VOICE, "audio/ogg", MessagingMedia.MAX_BYTES + 1L));
			assertEquals(MessagingMedia.TOO_LARGE, refusal(f, RequestContext.of(OWNER), Maps.of("media_id", "91005")));
			assertEquals("/v25.0/91005", f.api.gets.poll(1, TimeUnit.SECONDS).path(), "the lookup reports the size");
			assertNull(f.api.gets.poll(100, TimeUnit.MILLISECONDS), "the bytes are never requested");
		}
	}
}
