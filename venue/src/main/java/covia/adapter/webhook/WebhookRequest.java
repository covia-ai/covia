package covia.adapter.webhook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable HTTP request, preserving the exact bytes needed for provider signatures. */
public record WebhookRequest(String method, Map<String, String> headers,
		Map<String, List<String>> query, byte[] body) {
	public WebhookRequest {
		Map<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		names.putAll(headers);
		headers = java.util.Collections.unmodifiableMap(names);
		Map<String, List<String>> params = new LinkedHashMap<>();
		query.forEach((key, values) -> params.put(key, List.copyOf(values)));
		query = Map.copyOf(params);
		body = body.clone();
	}

	@Override public byte[] body() { return body.clone(); }
	public String header(String name) { return headers.get(name); }
	public String queryParam(String name) {
		List<String> values = query.get(name);
		return values == null || values.isEmpty() ? null : values.getFirst();
	}
	@Override public String toString() { return "WebhookRequest[" + method + ", " + body.length + " bytes]"; }
}
