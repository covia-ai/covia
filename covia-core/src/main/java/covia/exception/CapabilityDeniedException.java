package covia.exception;

import convex.core.data.AString;

/**
 * A request's authority does not cover what it tried to do. The message says
 * so for a reader; the capability it lacked and the authority refused are kept
 * for callers that diagnose or act on the denial.
 */
@SuppressWarnings("serial")
public class CapabilityDeniedException extends AuthException {

	private final AString resource;
	private final AString ability;
	private final AString authority;

	public CapabilityDeniedException(String message, AString resource, AString ability, AString authority) {
		super(message);
		this.resource = resource;
		this.ability = ability;
		this.authority = authority;
	}

	/** The resource required (a capability's {@code with}), as the request resolved it; null if the check named none. */
	public AString getResource() {
		return resource;
	}

	/** The ability required (a capability's {@code can}); null if the check named none. */
	public AString getAbility() {
		return ability;
	}

	/** The DID of the authority refused; null for a request with no identity. */
	public AString getAuthority() {
		return authority;
	}
}
