package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.server.VenueServer;

/**
 * A private venue — public access disabled — still answers {@code /api/v1/status}
 * to strangers: discovery is how a client finds and verifies a venue before it
 * can authenticate at all (a newcomer taking over a desktop venue, a peer
 * checking a DID, a health probe). Ordinary REST stays behind authentication,
 * and a credential that <em>is</em> presented must still be valid.
 */
class StatusDiscoveryTest {

	private static VenueServer server;

	@BeforeAll
	static void launch() {
		server = VenueServer.launch(Maps.of(
			Config.PORT, 0,
			Config.AUTH, Maps.of(Config.PUBLIC, Maps.of(Config.ENABLED, false))));
	}

	@AfterAll
	static void close() {
		if (server != null) server.close();
	}

	@Test
	void statusAnswersStrangersOnAPrivateVenue() throws Exception {
		HttpResponse<String> response = get("/api/v1/status", null);
		assertEquals(200, response.statusCode(), response.body());
		ACell status = JSON.parseJSON5(response.body());
		assertEquals(server.getEngine().getDIDString().toString(),
			String.valueOf(RT.getIn(status, "did")),
			"the status document names the venue's DID");
	}

	@Test
	void ordinaryRestStaysBehindAuthentication() throws Exception {
		assertEquals(401, get("/api/v1/jobs", null).statusCode(),
			"public access disabled still gates the rest of the API");
	}

	@Test
	void aPresentedCredentialMustStillBeValid() throws Exception {
		assertEquals(401, get("/api/v1/status", "not-a-jwt").statusCode(),
			"discovery does not mean a bad bearer is ignored");
	}

	private static HttpResponse<String> get(String path, String bearer) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(
				URI.create("http://localhost:" + server.port() + path))
			.timeout(Duration.ofSeconds(10)).GET();
		if (bearer != null) request.header("Authorization", "Bearer " + bearer);
		return TestHTTP.CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
