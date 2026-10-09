package covia.adapter.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Raw-byte HMAC comparison; providers supply their signed prefix and replay policy. */
public final class WebhookSignatures {
	private WebhookSignatures() { }
	public static boolean verify(String secret, String header, String headerPrefix, String bodyPrefix, byte[] body) {
		if (secret == null || secret.isBlank() || header == null || !header.startsWith(headerPrefix)) return false;
		String hex = header.substring(headerPrefix.length());
		if (!hex.matches("[a-fA-F0-9]{64}")) return false;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update(bodyPrefix.getBytes(StandardCharsets.UTF_8));
			return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(hex));
		} catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
	}
	public static boolean equal(String expected, String actual) {
		return expected != null && actual != null && MessageDigest.isEqual(
			expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}
}
