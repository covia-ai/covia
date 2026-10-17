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
import covia.adapter.messaging.MessagingMedia;

/**
 * Telegram media selection; wire metadata stays provider-specific. Storing,
 * naming and the media hook's result are the shared {@link MessagingMedia}.
 */
final class TelegramMedia {
	static final int MAX_BYTES = 20 * 1024 * 1024;
	static final String TOO_LARGE = "Telegram attachment exceeds the 20 MiB transfer limit";
	static final String DOWNLOAD_FAILED = "Telegram attachment download failed";
	static final String INVALID_PATH = "Telegram returned an invalid file path";
	static final String INVALID_DESTINATION = MessagingMedia.INVALID_DESTINATION;
	static final String UNSUPPORTED_DESTINATION = MessagingMedia.UNSUPPORTED_DESTINATION;
	static final String INVALID_FILE_NAME = MessagingMedia.INVALID_FILE_NAME;
	static final String NAME_CONFLICT = MessagingMedia.NAME_CONFLICT;
	static final String PROCESSOR_RESULT = MessagingMedia.PROCESSOR_RESULT;

	private TelegramMedia() {}

	static boolean safeFileName(String name) { return MessagingMedia.safeFileName(name); }

	static String collisionName(String name, int attempt) { return MessagingMedia.collisionName(name, attempt); }

	static AMap<AString, ACell> processed(AMap<AString, ACell> attachment, ACell result) {
		return MessagingMedia.processed(attachment, result);
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
