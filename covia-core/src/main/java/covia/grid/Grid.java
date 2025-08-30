package covia.grid;

import covia.api.DID;

/**
 * Static utility class for interacting with the Covia Grid
 */
public class Grid {

	/**
	 * Connect to a grid venue via a DID
	 * @param did DID e.g. 'did:web:venue-test.covia.ai"
	 * @return Venue instance
	 */
	public static Venue connect(DID did) {
		String method=did.getMethod();
		if (did.getMethod().equals("web")) {
			return Venue.connect(did.getId());
		}
		
		throw new IllegalArgumentException("Unrecognised DID method: "+method);
	}
}
