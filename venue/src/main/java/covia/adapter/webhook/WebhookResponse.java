package covia.adapter.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Provider-owned acknowledgement or challenge response. No automatic success response exists. */
public record WebhookResponse(int status, String contentType, Map<String, String> headers, byte[] body) {
	public static final String INVALID_STATUS = "Webhook response must have a final HTTP status (200–599)";
	public WebhookResponse {
		if (status < 200 || status > 599) throw new IllegalArgumentException(INVALID_STATUS);
		headers = Map.copyOf(headers);
		body = body.clone();
	}
	@Override public byte[] body() { return body.clone(); }
	public static WebhookResponse text(int status, String text) {
		return new WebhookResponse(status, "text/plain; charset=utf-8", Map.of(), text.getBytes(StandardCharsets.UTF_8));
	}
	@Override public String toString() { return "WebhookResponse[" + status + ", " + body.length + " bytes]"; }
}
