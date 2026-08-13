package covia.grid.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpRequest;

import org.junit.jupiter.api.Test;

/** Focused contract tests for the non-signing authentication strategies. */
public class VenueAuthTest {

	private static HttpRequest request(VenueAuth auth) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://venue.example/status"));
		auth.apply(builder);
		return builder.GET().build();
	}

	@Test
	public void noAuthIsSingletonAndAddsNoCredentials() {
		VenueAuth auth = VenueAuth.none();
		assertSame(auth, VenueAuth.none());
		assertFalse(request(auth).headers().firstValue("Authorization").isPresent());
		assertNull(auth.getDID());
		assertThrows(UnsupportedOperationException.class, auth::mintToken);
	}

	@Test
	public void bearerAddsExactlyOneAuthorizationHeader() {
		VenueAuth auth = VenueAuth.bearer("opaque-token");
		HttpRequest request = request(auth);
		assertEquals("Bearer opaque-token",
			request.headers().firstValue("Authorization").orElseThrow());
		assertEquals(1, request.headers().allValues("Authorization").size());
		assertNull(auth.getDID());
		assertThrows(UnsupportedOperationException.class, auth::mintToken);
	}

	@Test
	public void bearerRejectsMissingCredentials() {
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.bearer(null));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.bearer(""));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.bearer("   "));
	}

	@Test
	public void localCarriesIdentityWithoutHttpCredentials() {
		String did = "did:web:venue.example:u:alice";
		VenueAuth auth = VenueAuth.local(did);
		assertEquals(did, auth.getDID());
		assertFalse(request(auth).headers().firstValue("Authorization").isPresent());
		assertThrows(UnsupportedOperationException.class, auth::mintToken);
	}

	@Test
	public void localRejectsMissingIdentity() {
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.local(null));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.local(""));
		assertThrows(IllegalArgumentException.class, () -> VenueAuth.local("   "));
	}
}
