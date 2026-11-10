package covia.grid.auth;

import java.net.http.HttpRequest;
import java.util.function.Supplier;

/**
 * Static bearer token authentication.
 * Adds {@code Authorization: Bearer <token>} to every request.
 */
class BearerAuth extends VenueAuth {

	private final String token;

	BearerAuth(String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("Bearer token must not be blank");
		}
		this.token = token;
	}

	@Override
	public void apply(HttpRequest.Builder builder, Supplier<String> venueDID) {
		builder.header("Authorization", "Bearer " + token);
	}
}
