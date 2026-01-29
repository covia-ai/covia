package covia.grid.auth;

import java.net.http.HttpRequest;

/**
 * Local (trusted) authentication that sets a user identity without
 * sending any HTTP credentials. Suitable for in-process venue access
 * via {@link covia.venue.LocalVenue} where the caller is inherently trusted.
 *
 * <p>Does not add any Authorization header. The identity is available
 * via {@link #getDID()} and is automatically set on the Venue via
 * {@link covia.grid.Venue#setUser}.
 */
class LocalAuth extends VenueAuth {

	private final String did;

	LocalAuth(String did) {
		if (did == null || did.isEmpty()) {
			throw new IllegalArgumentException("DID must not be null or empty");
		}
		this.did = did;
	}

	@Override
	public void apply(HttpRequest.Builder builder) {
		// No HTTP credentials — identity is trusted locally
	}

	@Override
	public String getDID() {
		return did;
	}
}
