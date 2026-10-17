package covia.adapter.messaging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.util.Locale;
import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Attachments as every messaging provider hands them to an agent. The provider
 * fetches an item's bytes with its own credentials; this class stores them with a
 * create-only write (by default in the binding owner's private DLFS drive named for
 * the provider), describes them, offers images to a vision-capable model, and applies
 * a binding's {@code mediaOperation} result. An agent receives references, never
 * token-bearing URLs or bytes.
 */
public final class MessagingMedia {
	/** The largest attachment fetched for an agent. */
	public static final int MAX_BYTES = 20 * 1024 * 1024;
	public static final String TOO_LARGE = "Attachment exceeds the 20 MiB limit";
	public static final String INVALID_DESTINATION = "destination must be a non-empty content directory reference";
	public static final String UNSUPPORTED_DESTINATION = "No content provider supports create-only writes at destination";
	public static final String INVALID_FILE_NAME = "fileName must be a safe single filename (at most 200 UTF-8 bytes)";
	public static final String NAME_CONFLICT = "Could not choose an unused filename after 1000 attempts";
	public static final String PROCESSOR_RESULT = "mediaOperation must return an object; text, when present, must be a non-empty string";
	public static final String NOT_FETCHED = "This provider does not fetch attachments";
	public static final String AGENT_ONLY = "mediaOperation applies only to agent bindings";

	/** An event's attachments as the provider describes them: {@code [{id, kind, mediaType?, fileName?, ...}]}. */
	public static final AString K_MEDIA = Strings.intern("media");
	public static final AString K_ATTACHMENTS = Strings.intern("attachments");
	public static final AString K_CAPTION = Strings.intern("caption");
	public static final AString K_MEDIA_TYPE = Strings.intern("mediaType");

	/** Bytes a provider fetched for one attachment, and their type when known. */
	public record Fetched(byte[] bytes, String contentType) {}

	private static final Map<String, String> EXTENSIONS = Map.ofEntries(
		Map.entry("audio/ogg", ".ogg"), Map.entry("audio/opus", ".opus"), Map.entry("audio/mpeg", ".mp3"),
		Map.entry("audio/mp4", ".m4a"), Map.entry("audio/aac", ".aac"), Map.entry("audio/amr", ".amr"),
		Map.entry("image/jpeg", ".jpg"), Map.entry("image/png", ".png"), Map.entry("image/webp", ".webp"),
		Map.entry("image/gif", ".gif"), Map.entry("video/mp4", ".mp4"), Map.entry("video/3gpp", ".3gp"),
		Map.entry("application/pdf", ".pdf"), Map.entry("text/plain", ".txt"));

	private MessagingMedia() { }

	/** Conservative portable basename: never interpret sender metadata as a path or URL component. */
	public static boolean safeFileName(String name) {
		if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
				|| !name.equals(name.strip()) || name.endsWith(".")
				|| name.getBytes(StandardCharsets.UTF_8).length > 200) return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isISOControl(c) || "/\\\\<>:\"|?*#%".indexOf(c) >= 0) return false;
		}
		String stem = name.split("\\.", 2)[0];
		return !stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])");
	}

	/** The name to try on a given attempt: {@code report.pdf}, then {@code report(1).pdf}. */
	public static String collisionName(String name, int attempt) {
		if (attempt == 0) return name;
		int dot = name.lastIndexOf('.');
		if (dot <= 0) dot = name.length();
		return name.substring(0, dot) + "(" + attempt + ")" + name.substring(dot);
	}

	/** A file extension for a media type, {@code .bin} when unknown; parameters are ignored. */
	public static String extension(String mediaType) {
		if (mediaType == null) return ".bin";
		String base = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
		return EXTENSIONS.getOrDefault(base, ".bin");
	}

	/** A name derived from a provider's item id, for media that carries no safe name of its own. */
	public static String fallbackName(String prefix, String id, String mediaType) {
		String stem = prefix.substring(0, Math.min(64, prefix.length()));
		return stem + "-" + Strings.create(id).getHash().toHexString() + extension(mediaType);
	}

	/** A binding owner's private directory for a provider's attachments: {@code <owner>/dlfs/<drive>/}. */
	public static String ownerDirectory(AString owner, String drive) {
		return owner + "/dlfs/" + drive + "/";
	}

	/**
	 * Store bytes under a directory without overwriting: the name is tried as given,
	 * then with {@code (1)}, {@code (2)} and so on.
	 *
	 * @return {@code {ref, size, fileName, contentType?}}
	 */
	public static AMap<AString, ACell> store(Engine engine, byte[] bytes, String contentType, String directory,
			String name, RequestContext ctx) throws IOException {
		if (directory == null || directory.isBlank()) throw new IllegalArgumentException(INVALID_DESTINATION);
		if (!directory.endsWith("/")) directory += "/";
		for (int attempt = 0; attempt < 1000; attempt++) {
			String actualName = collisionName(name, attempt);
			// DID references are URLs; encode the filename while preserving its stored spelling.
			String referenceName = directory.startsWith("did:")
				? URLEncoder.encode(actualName, StandardCharsets.UTF_8).replace("+", "%20")
				: actualName;
			AString ref = Strings.create(directory + referenceName);
			try {
				if (!engine.createContent(ref, new ByteArrayInputStream(bytes), contentType, ctx)) {
					throw new IllegalArgumentException(UNSUPPORTED_DESTINATION + ": " + directory);
				}
			} catch (FileAlreadyExistsException conflict) {
				continue;
			}
			engine.flush();
			AMap<AString, ACell> result = Maps.of(Fields.REF, ref, "size", bytes.length, Fields.FILE_NAME, actualName);
			if (contentType != null) result = result.assoc(Fields.CONTENT_TYPE, Strings.create(contentType));
			return result;
		}
		throw new IllegalStateException(NAME_CONFLICT);
	}

	/** Retain a {@code mediaOperation} result without letting it overwrite transport metadata. */
	public static AMap<AString, ACell> processed(AMap<AString, ACell> attachment, ACell result) {
		if (!(result instanceof AMap<?, ?> given)) throw new IllegalArgumentException(PROCESSOR_RESULT);
		AMap<AString, ACell> map = RT.castMap(given);
		ACell text = map.get(Fields.TEXT);
		if (map.containsKey(Fields.TEXT)) {
			if (!(text instanceof AString string) || string.toString().isBlank()) {
				throw new IllegalArgumentException(PROCESSOR_RESULT);
			}
			attachment = attachment.assoc(Fields.TEXT, text);
		}
		return map.isEmpty() ? attachment : attachment.assoc(Fields.RESULT, result);
	}

	/** An image block for a vision-capable model when the attachment is an image; null otherwise. */
	public static AMap<AString, ACell> imageBlock(AMap<AString, ACell> attachment) {
		AString type = RT.ensureString(attachment.get(K_MEDIA_TYPE));
		ACell ref = attachment.get(Fields.REF);
		if (type == null || ref == null || !type.toString().startsWith("image/")) return null;
		return Maps.of("type", "image", "source", Maps.of("type", "asset", "ref", ref, "mediaType", type));
	}
}
