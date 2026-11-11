package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.DLFSAdapter;
import covia.api.Fields;
import covia.exception.InvalidDefinitionException;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.test.IntegrationTest;

/**
 * The attachment layer every messaging provider shares: names never become paths,
 * stores never overwrite, the media hook cannot replace transport metadata, and only
 * images become image blocks.
 */
class MessagingMediaTest {

	@Test void namesAreSafeAndDerivedFromTheMediaType() {
		assertTrue(MessagingMedia.safeFileName("report.pdf"));
		for (String unsafe : new String[] {"../escape.pdf", "a/b.pdf", "CON.txt", " padded", "trailing.", ""}) {
			assertFalse(MessagingMedia.safeFileName(unsafe), unsafe);
		}
		assertEquals(".ogg", MessagingMedia.extension("audio/ogg; codecs=opus"));
		assertEquals(".jpg", MessagingMedia.extension("IMAGE/JPEG"));
		assertEquals(".bin", MessagingMedia.extension("application/x-unknown"));
		assertEquals(".bin", MessagingMedia.extension(null));
		String fallback = MessagingMedia.fallbackName("x".repeat(100), "90001", "audio/ogg");
		assertTrue(fallback.startsWith("x".repeat(64) + "-") && fallback.endsWith(".ogg"), fallback);
		assertEquals("report(2).pdf", MessagingMedia.collisionName("report.pdf", 2));
	}

	@IntegrationTest @Test void storesNeverOverwrite(TestInfo info) throws Exception {
		AString owner = TestEngine.uniqueDID(info);
		String directory = MessagingMedia.ensureOwnerDirectory(TestEngine.ENGINE, owner, "media-test", RequestContext.of(owner));
		byte[] first = "first".getBytes(StandardCharsets.UTF_8), second = "second".getBytes(StandardCharsets.UTF_8);
		AMap<AString, ACell> a = MessagingMedia.store(TestEngine.ENGINE, first, "audio/ogg", directory, "note.ogg", RequestContext.of(owner));
		AMap<AString, ACell> b = MessagingMedia.store(TestEngine.ENGINE, second, "audio/ogg", directory, "note.ogg", RequestContext.of(owner));
		assertEquals("note.ogg", RT.getIn(a, Fields.FILE_NAME).toString());
		assertEquals("note(1).ogg", RT.getIn(b, Fields.FILE_NAME).toString());
		assertArrayEquals(first, TestEngine.ENGINE.resolveContent(RT.ensureString(a.get(Fields.REF)), RequestContext.of(owner))
			.content().getInputStream().readAllBytes(), "the first is untouched");
		assertEquals(MessagingMedia.INVALID_DESTINATION, assertThrows(IllegalArgumentException.class,
			() -> MessagingMedia.store(TestEngine.ENGINE, first, null, " ", "x.bin", RequestContext.of(owner))).getMessage());
	}

	@IntegrationTest @Test void theOwnersMediaDriveNeedsWriteOverTheDrive(TestInfo info) throws Exception {
		// A provider creates the owner's media drive when its writer may write over
		// that drive, dlfs/<drive>/; a grant over one file in it is not enough.
		AString owner = TestEngine.uniqueDID(info);
		DLFSAdapter dlfs = (DLFSAdapter) TestEngine.ENGINE.getAdapter("dlfs");
		RequestContext fileWriter = RequestContext.of(owner).withCaps(Vectors.of(
			Capability.create(Strings.create("dlfs/media-grant/note.ogg"), Capability.CRUD_WRITE)));
		assertThrows(Exception.class,
			() -> MessagingMedia.ensureOwnerDirectory(TestEngine.ENGINE, owner, "media-grant", fileWriter));
		assertEquals(0, RT.ensureVector(RT.getIn(dlfs.listDrives(RequestContext.of(owner)), "drives")).count());

		RequestContext driveWriter = RequestContext.of(owner).withCaps(Vectors.of(
			Capability.create(Strings.create("dlfs/media-grant/"), Capability.CRUD_WRITE)));
		MessagingMedia.ensureOwnerDirectory(TestEngine.ENGINE, owner, "media-grant", driveWriter);
		assertEquals(Vectors.of("media-grant"), RT.getIn(dlfs.listDrives(RequestContext.of(owner)), "drives"));
	}

	@Test void theMediaHookAddsTextButNeverReplacesTheReference() {
		AMap<AString, ACell> attachment = RT.castMap(Maps.of("ref", "dlfs/whatsapp/note.ogg", "kind", "audio", "mediaType", "audio/ogg"));
		assertEquals(attachment, MessagingMedia.processed(attachment, Maps.empty()), "{} passes it through");
		AMap<AString, ACell> heard = MessagingMedia.processed(attachment, Maps.of("text", "hello", "ref", "elsewhere"));
		assertEquals("hello", RT.getIn(heard, "text").toString());
		assertEquals("dlfs/whatsapp/note.ogg", RT.getIn(heard, "ref").toString(), "the stored reference stays");
		assertEquals("elsewhere", RT.getIn(heard, "result", "ref").toString(), "the hook's own output is kept apart");
		for (ACell invalid : new ACell[] {null, Maps.of("text", ""), Maps.of("text", 42)}) {
			assertEquals(MessagingMedia.PROCESSOR_RESULT, assertThrows(InvalidDefinitionException.class,
				() -> MessagingMedia.processed(attachment, invalid)).getMessage());
		}
		assertNull(MessagingMedia.imageBlock(attachment), "audio is no image block");
		AMap<AString, ACell> image = MessagingMedia.imageBlock(attachment.assoc(MessagingMedia.K_MEDIA_TYPE, convex.core.data.Strings.create("image/png")));
		assertEquals("image", RT.getIn(image, "type").toString());
		assertEquals("dlfs/whatsapp/note.ogg", RT.getIn(image, "source", "ref").toString());
	}
}
