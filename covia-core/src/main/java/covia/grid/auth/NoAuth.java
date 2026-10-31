package covia.grid.auth;

import java.net.http.HttpRequest;
import java.util.function.Supplier;

/**
 * No-op authentication provider. Sends requests without credentials.
 */
class NoAuth extends VenueAuth {

	static final NoAuth INSTANCE = new NoAuth();

	private NoAuth() {}

	@Override
	public void apply(HttpRequest.Builder builder, Supplier<String> venueDID) {
		// No authentication headers added
	}
}
