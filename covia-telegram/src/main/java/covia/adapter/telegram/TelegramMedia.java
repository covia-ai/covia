package covia.adapter.telegram;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.utility.BotUtils;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.api.Fields;

/** Telegram media selection; wire metadata stays provider-specific. */
final class TelegramMedia {
	static final int MAX_BYTES = 20 * 1024 * 1024;
	static final String TOO_LARGE = "Telegram attachment exceeds the 20 MiB transfer limit";
	static final String DOWNLOAD_FAILED = "Telegram attachment download failed";
	static final String INVALID_PATH = "Telegram returned an invalid file path";
	static final String INVALID_DESTINATION = "destination must be a non-empty content directory reference";
	static final String UNSUPPORTED_DESTINATION = "No content provider supports create-only writes at destination";
	static final String INVALID_FILE_NAME = "fileName must be a safe single filename (at most 200 UTF-8 bytes)";
	static final String NAME_CONFLICT = "Could not choose an unused filename after 1000 attempts";
	static final String PROCESSOR_RESULT = "mediaOperation must return an object; text, when present, must be a non-empty string";

	private TelegramMedia() {}

	/** Conservative portable basename: never interpret sender metadata as a path or URL component. */
	static boolean safeFileName(String name) {
		if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
				|| !name.equals(name.strip()) || name.endsWith(".")
				|| name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200) return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (Character.isISOControl(c) || "/\\\\<>:\"|?*#%".indexOf(c) >= 0) return false;
		}
		String stem = name.split("\\.", 2)[0];
		return !stem.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])");
	}

	static String collisionName(String name, int attempt) {
		if (attempt == 0) return name;
		int dot = name.lastIndexOf('.');
		if (dot <= 0) dot = name.length();
		return name.substring(0, dot) + "(" + attempt + ")" + name.substring(dot);
	}

	/** Retain application output without allowing it to overwrite transport metadata. */
	static AMap<AString, ACell> processed(AMap<AString, ACell> attachment, ACell result) {
		if (!(result instanceof AMap<?, ?> map)) throw new IllegalArgumentException(PROCESSOR_RESULT);
		ACell text = map.get(Fields.TEXT);
		if (map.containsKey(Fields.TEXT)) {
			if (!(text instanceof AString string) || string.toString().isBlank()) {
				throw new IllegalArgumentException(PROCESSOR_RESULT);
			}
			attachment = attachment.assoc(Fields.TEXT, text);
		}
		return map.isEmpty() ? attachment : attachment.assoc(Fields.RESULT, result);
	}

	static List<JsonObject> attachments(Message message) {
		List<JsonObject> result = new ArrayList<>();
		if (message == null) return result;
		JsonObject m = JsonParser.parseString(BotUtils.toJson(message)).getAsJsonObject();
		if (m.has("photo")) {
			JsonArray sizes = m.getAsJsonArray("photo");
			JsonObject largest = null;
			long area = -1;
			for (var size : sizes) {
				JsonObject photo = size.getAsJsonObject();
				long pixels = photo.get("width").getAsLong() * photo.get("height").getAsLong();
				if (pixels > area) { largest = photo; area = pixels; }
			}
			if (largest != null) result.add(descriptor("photo", largest, "image/jpeg"));
		}
		for (String kind : List.of("voice", "audio", "document", "video", "video_note", "animation", "sticker")) {
			// Telegram also supplies document for an animation: retain one attachment.
			if (kind.equals("document") && m.has("animation")) continue;
			if (!m.has(kind)) continue;
			JsonObject media = m.getAsJsonObject(kind);
			String mime = switch (kind) {
				case "voice" -> "audio/ogg";
				case "video", "video_note", "animation" -> "video/mp4";
				default -> "application/octet-stream";
			};
			result.add(descriptor(kind, media, mime));
		}
		return result;
	}

	private static JsonObject descriptor(String kind, JsonObject source, String mime) {
		JsonObject result = source.deepCopy();
		result.remove("thumbnail");
		result.addProperty("kind", kind);
		result.addProperty("mediaType", source.has("mime_type") ? source.get("mime_type").getAsString() : mime);
		return result;
	}
}
