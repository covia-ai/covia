package covia.grid.auth;

import java.net.http.HttpRequest;

/**
 * No-op authentication provider. Sends requests without credentials.
 */
class NoAuth extends VenueAuth {

	static final NoAuth INSTANCE = new NoAuth();

	private NoAuth() {}

	@Override
	public void apply(HttpRequest.Builder builder) {
		// No authentication headers added
	}
}
