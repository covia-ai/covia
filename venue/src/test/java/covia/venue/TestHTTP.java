package covia.venue;

import java.net.http.HttpClient;
import java.time.Duration;

/** Shared HTTP transport for the test JVM. */
public final class TestHTTP {

	public static final HttpClient CLIENT = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.build();

	private TestHTTP() {
	}
}
