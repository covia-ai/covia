package covia.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import covia.exception.InvalidInputException;

class HTTPURLsTest {

	@Test
	void dotSegmentsNameTheActualDestination() {
		for (String path : new String[] {"/api/../admin", "/api/%2e%2E/admin", "/api/.%2e/admin", "/api/%2E./admin"}) {
			assertEquals("https://example.com/admin", HTTPURLs.normalize("https://example.com" + path).toString());
		}
	}

	@Test
	void preservesDataAndIsIdempotent() {
		String url = "https://example.com/a//b;param/%7eitem%20x?q=%2F%252e+z#frag";
		var normalized = HTTPURLs.normalize(url);
		assertEquals("https://example.com/a//b;param/~item%20x?q=%2F%252e+z#frag", normalized.toString());
		assertEquals(normalized, HTTPURLs.normalize(normalized));
		assertEquals("https://example.com/", HTTPURLs.normalize("https://example.com").toString());
	}

	@Test
	void refusesAmbiguousPaths() {
		for (String path : new String[] {"/api%2f..%2fadmin", "/api/%252e%252e/admin", "/api/%5c../admin", "/api/..;p/admin", "/api/..%3bp/admin"}) {
			assertEquals(HTTPURLs.AMBIGUOUS_PATH, assertThrows(InvalidInputException.class,
				() -> HTTPURLs.normalize("https://example.com" + path)).getMessage());
		}
	}

	@Test
	void malformedURLsFailWithoutEchoingCredentials() {
		for (String url : new String[] {"https://example.com/../../admin", "/api/../admin", "file:///api", "https://example.com:70000/",
		"https://example.com/api/%zz?token=private", "https://first@second@third/"}) {
			var error = assertThrows(InvalidInputException.class, () -> HTTPURLs.normalize(url));
			assertFalse(error.getMessage().contains("private"));
			assertNull(error.getCause(), "a parser cause could contain an inline credential");
		}
	}
}
