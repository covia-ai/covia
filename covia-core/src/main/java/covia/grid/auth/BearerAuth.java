package covia.grid.auth;

import java.net.http.HttpRequest;

/**
 * Static bearer token authentication.
 * Adds {@code Authorization: Bearer <token>} to every request.
 */
class BearerAuth extends VenueAuth {

	private final String token;

	BearerAuth(String token) {
		if (token == null || token.isEmpty()) {
			throw new IllegalArgumentException("Bearer token must not be null or empty");
		}
		this.token = token;
	}

	@Override
	public void apply(HttpRequest.Builder builder) {
		builder.header("Authorization", "Bearer " + token);
	}
}
