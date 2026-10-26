package covia.venue.auth;

import java.util.Map;

import convex.core.data.ACell;
import convex.core.data.AString;
import covia.exception.AuthException;

/**
 * Trusted authentication implementations supplied by an adapter, independently
 * of its public operation assets. Names are local to that adapter.
 *
 * <p>Declarations must be immutable and stable for the installed instance's
 * lifetime. Configuration changes verifier behaviour through the adapter's
 * normal atomic configure hook; changing declarations requires replacement.
 * Inputs are transient Convex values, never automatically recorded as Jobs.</p>
 */
public interface AuthenticationMethods {

	Map<AString, Verifier> authenticationMethods();

	@FunctionalInterface
	interface Verifier {
		/**
		 * Verify method-owned input and return evidence for the selected venue
		 * method id. Throw AuthException on rejection; never return unverified
		 * client claims. This does not admit a user or confer capabilities.
		 */
		AuthResult verify(AString method, ACell input) throws AuthException;
	}
}
