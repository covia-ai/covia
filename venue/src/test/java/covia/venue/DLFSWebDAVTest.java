package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import covia.venue.server.VenueServer;

/**
 * Tests DLFS WebDAV endpoint on the venue server.
 * Uses the shared TestServer with ephemeral port.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class DLFSWebDAVTest {

	static final String BASE_URL = TestServer.BASE_URL;
	VenueServer server;
	HttpClient http;

	@BeforeAll
	public void setup() {
		server = TestServer.SERVER;
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	private HttpResponse<String> request(String method, String path, String body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(BASE_URL + "/dlfs/" + path))
			.timeout(Duration.ofSeconds(5));

		if (body != null) {
			builder.method(method, HttpRequest.BodyPublishers.ofString(body));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<byte[]> requestBytes(String method, String path, byte[] body) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(BASE_URL + "/dlfs/" + path))
			.timeout(Duration.ofSeconds(5));

		if (body != null) {
			builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	@Test
	public void testPutAndGetTextFile() throws Exception {
		// PUT a text file
		HttpResponse<String> putRes = request("PUT", "health-vault/test.txt", "Hello DLFS!");
		assertEquals(201, putRes.statusCode(), "PUT should return 201 Created");

		// GET it back
		HttpResponse<String> getRes = request("GET", "health-vault/test.txt", null);
		assertEquals(200, getRes.statusCode());
		assertEquals("Hello DLFS!", getRes.body());
	}

	@Test
	public void testPutAndGetBinaryFile() throws Exception {
		// PUT binary content
		byte[] binary = new byte[] { 0x00, 0x50, 0x44, 0x46, (byte) 0xFF, (byte) 0xD8, 0x01, 0x02, 0x03 };
		HttpResponse<byte[]> putRes = requestBytes("PUT", "health-vault/binary.bin", binary);
		assertTrue(putRes.statusCode() == 201 || putRes.statusCode() == 204,
			"PUT binary should succeed, got " + putRes.statusCode());

		// GET it back as bytes
		HttpResponse<byte[]> getRes = requestBytes("GET", "health-vault/binary.bin", null);
		assertEquals(200, getRes.statusCode());
		assertArrayEquals(binary, getRes.body());
	}

	@Test
	public void testMkdirAndWriteInDir() throws Exception {
		// Create directory via MKCOL
		HttpResponse<String> mkRes = request("MKCOL", "health-vault/records/", null);
		assertEquals(201, mkRes.statusCode(), "MKCOL should return 201");

		// PUT file in directory
		HttpResponse<String> putRes = request("PUT", "health-vault/records/report.json", "{\"status\": \"ok\"}");
		assertEquals(201, putRes.statusCode());

		// Read it back
		HttpResponse<String> getRes = request("GET", "health-vault/records/report.json", null);
		assertEquals(200, getRes.statusCode());
		assertEquals("{\"status\": \"ok\"}", getRes.body());
	}

	@Test
	public void testDeleteFile() throws Exception {
		request("PUT", "health-vault/to-delete.txt", "temporary");

		HttpResponse<String> delRes = request("DELETE", "health-vault/to-delete.txt", null);
		assertEquals(204, delRes.statusCode());

		// GET should now 404
		HttpResponse<String> getRes = request("GET", "health-vault/to-delete.txt", null);
		assertEquals(404, getRes.statusCode());
	}

	@Test
	public void testOverwrite() throws Exception {
		request("PUT", "health-vault/overwrite.txt", "version 1");
		request("PUT", "health-vault/overwrite.txt", "version 2");

		HttpResponse<String> getRes = request("GET", "health-vault/overwrite.txt", null);
		assertEquals(200, getRes.statusCode());
		assertEquals("version 2", getRes.body());
	}

	@Test
	public void testGetNonexistent() throws Exception {
		HttpResponse<String> res = request("GET", "health-vault/does-not-exist.txt", null);
		assertEquals(404, res.statusCode());
	}
}
