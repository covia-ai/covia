package covia.venue.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

class EvidencePolicyTest {
	private static final AString USER = Strings.create("did:example:alice");
	private static final AString FIRST = Strings.create("custom-first");
	private static final AString SECOND = Strings.create("custom-second");
	private static final AString MFA = Strings.create("mfa");
	private static final AString POSSESSION = Strings.create("freshKeyPossession");
	private static final long NOW = 1000;
	private static final EvidencePolicy OPEN = policy(null, Sets.empty(), Sets.empty(), null, null);

	private static EvidencePolicy policy(ASet<AString> methods, ASet<AString> event,
			ASet<AString> proof, Long eventAge, Long proofAge) {
		return new EvidencePolicy(methods, event, proof, eventAge, proofAge);
	}

	private static AuthResult result(AString method, Long time, ASet<AString> properties,
			AuthResult.Proof proof) {
		return new AuthResult(USER, USER, null,
			new AuthResult.Authentication(method, null, time, properties, null), proof, null);
	}

	@Test
	void equivalentEvidenceFromUnrelatedMethodsHasTheSameDecision() {
		// A new method/property needs no evaluator registration or protocol knowledge.
		AString property = Strings.create("example:verified-device");
		var policy = policy(null, Sets.of(property), Sets.empty(), 60L, null);
		for (AString method : new AString[] { FIRST, SECOND }) {
			assertNull(policy.allows(result(method, NOW - 60, Sets.of(property), null), NOW));
			assertEquals(EvidencePolicy.MISSING_PROPERTIES,
				policy.allows(result(method, NOW, Sets.empty(), null), NOW));
		}
	}

	@Test
	void methodRestrictionUsesOriginAndDistinguishesAbsentFromEmpty() {
		var event = new AuthResult.Authentication(FIRST, null, NOW, Sets.empty(), null);
		var session = new AuthResult(USER, USER,
			new AuthResult.Credential(Strings.create("session"), NOW, NOW + 30),
			event, null, Strings.create("session-id"));
		assertNull(policy(Sets.of(FIRST), Sets.empty(), Sets.empty(), null, null).allows(session, NOW));
		assertEquals(EvidencePolicy.METHOD_NOT_ALLOWED,
			policy(Sets.of(Strings.create("session")), Sets.empty(), Sets.empty(), null, null).allows(session, NOW));
		assertNull(OPEN.allows(session, NOW));
		assertEquals(EvidencePolicy.METHOD_NOT_ALLOWED,
			policy(Sets.empty(), Sets.empty(), Sets.empty(), null, null).allows(session, NOW));
		assertEquals(EvidencePolicy.METHOD_NOT_ALLOWED,
			policy(Sets.of(FIRST), Sets.empty(), Sets.empty(), null, null).allows(AuthResult.identity(USER, USER), NOW));
	}

	@Test
	void propertiesNeverCrossEventAndRequestProofBoundaries() {
		var policy = policy(null, Sets.of(MFA), Sets.of(POSSESSION), 60L, 5L);
		assertNull(policy.allows(result(FIRST, NOW, Sets.of(MFA),
			new AuthResult.Proof(NOW, Sets.of(POSSESSION))), NOW));
		assertEquals(EvidencePolicy.MISSING_PROPERTIES,
			policy.allows(result(FIRST, NOW, Sets.empty(), new AuthResult.Proof(NOW, Sets.of(MFA, POSSESSION))), NOW));
		assertEquals(EvidencePolicy.MISSING_PROOF_PROPERTIES,
			policy.allows(result(FIRST, NOW, Sets.of(MFA, POSSESSION), null), NOW));
		assertEquals(EvidencePolicy.MISSING_PROOF_PROPERTIES,
			policy.allows(result(FIRST, NOW, Sets.of(MFA), new AuthResult.Proof(NOW, Sets.of(MFA))), NOW));
	}

	@Test
	void protocolDetailsAndCredentialTimesCannotSupplyMissingEvidence() {
		AMap<AString, ACell> details = Maps.of("amr", Sets.of(MFA), "acr", "hard", "auth_time", NOW);
		var bearer = new AuthResult(USER, USER,
			new AuthResult.Credential(Strings.create("custom-bearer"), NOW, NOW + 60),
			new AuthResult.Authentication(FIRST, null, null, Sets.empty(), details), null, null);
		assertEquals(EvidencePolicy.MISSING_PROPERTIES,
			policy(null, Sets.of(MFA), Sets.empty(), null, null).allows(bearer, NOW));
		assertEquals(EvidencePolicy.AUTHENTICATION_NOT_FRESH,
			policy(null, Sets.empty(), Sets.empty(), 60L, null).allows(bearer, NOW));
		assertEquals(EvidencePolicy.PROOF_NOT_FRESH,
			policy(null, Sets.empty(), Sets.empty(), null, 60L).allows(bearer, NOW));
	}

	@Test
	void eventFreshnessRejectsUnknownFutureNegativeAndStaleTimes() {
		var policy = policy(null, Sets.empty(), Sets.empty(), 60L, null);
		for (Long time : new Long[] {null, -1L, Long.MIN_VALUE, NOW + 1, Long.MAX_VALUE, NOW - 61}) {
			assertEquals(EvidencePolicy.AUTHENTICATION_NOT_FRESH,
				policy.allows(result(FIRST, time, Sets.empty(), null), NOW));
		}
		assertNull(policy.allows(result(FIRST, NOW - 60, Sets.empty(), null), NOW));
		assertNull(policy.allows(result(FIRST, NOW, Sets.empty(), null), NOW));
		var zeroAge = policy(null, Sets.empty(), Sets.empty(), 0L, null);
		assertNull(zeroAge.allows(result(FIRST, NOW, Sets.empty(), null), NOW));
		assertEquals(EvidencePolicy.AUTHENTICATION_NOT_FRESH,
			zeroAge.allows(result(FIRST, NOW - 1, Sets.empty(), null), NOW));
	}

	@Test
	void proofFreshnessIsIndependentOfEventTime() {
		var policy = policy(null, Sets.empty(), Sets.empty(), null, 5L);
		assertEquals(EvidencePolicy.PROOF_NOT_FRESH, policy.allows(result(FIRST, NOW, Sets.empty(), null), NOW));
		for (long time : new long[] {-1, Long.MIN_VALUE, NOW + 1, Long.MAX_VALUE, NOW - 6}) {
			assertEquals(EvidencePolicy.PROOF_NOT_FRESH,
				policy.allows(result(FIRST, NOW, Sets.empty(), new AuthResult.Proof(time, Sets.empty())), NOW));
		}
		assertNull(policy.allows(result(FIRST, null, Sets.empty(), new AuthResult.Proof(NOW - 5, Sets.empty())), NOW));
		assertEquals(EvidencePolicy.AUTHENTICATION_NOT_FRESH,
			policy(null, Sets.empty(), Sets.empty(), 5L, 5L)
				.allows(result(FIRST, NOW - 6, Sets.empty(), new AuthResult.Proof(NOW, Sets.empty())), NOW));
	}

	@Test
	void largeAgesCannotWrapIntoFreshEvidence() {
		var policy = policy(null, Sets.empty(), Sets.empty(), Long.MAX_VALUE, Long.MAX_VALUE);
		assertNull(policy.allows(result(FIRST, 0L, Sets.empty(), new AuthResult.Proof(0, Sets.empty())), Long.MAX_VALUE));
		assertEquals(EvidencePolicy.AUTHENTICATION_NOT_FRESH,
			policy.allows(result(FIRST, Long.MIN_VALUE, Sets.empty(), new AuthResult.Proof(0, Sets.empty())), Long.MAX_VALUE));
	}

	@Test
	void knownCredentialExpiryIsExclusiveAndNotRequiredForDirectMethods() {
		var direct = result(FIRST, NOW, Sets.empty(), null);
		assertNull(OPEN.allows(direct, NOW));
		var credential = new AuthResult.Credential(Strings.create("custom-token"), null, NOW);
		var bearer = new AuthResult(USER, USER, credential, direct.authentication(), null, null);
		assertNull(OPEN.allows(bearer, NOW - 1));
		assertEquals(EvidencePolicy.CREDENTIAL_EXPIRED, OPEN.allows(bearer, NOW));
		assertEquals(EvidencePolicy.CREDENTIAL_EXPIRED, OPEN.allows(bearer, NOW + 1));
		assertEquals(EvidencePolicy.MISSING_RESULT, OPEN.allows(null, NOW));
		// No requirements does not turn identity attribution into a credential.
		assertNull(OPEN.allows(AuthResult.identity(USER, USER), NOW));
	}

	@Test
	void rejectsMalformedRequirementsAndEvaluationTime() {
		for (Long age : new Long[] {-1L, Long.MIN_VALUE}) {
			assertEquals(EvidencePolicy.INVALID_AGE, assertThrows(IllegalArgumentException.class,
				() -> policy(null, Sets.empty(), Sets.empty(), age, null)).getMessage());
			assertEquals(EvidencePolicy.INVALID_AGE, assertThrows(IllegalArgumentException.class,
				() -> policy(null, Sets.empty(), Sets.empty(), null, age)).getMessage());
		}
		assertEquals(EvidencePolicy.INVALID_NAMES, assertThrows(IllegalArgumentException.class,
			() -> policy(null, null, Sets.empty(), null, null)).getMessage());
		assertEquals(EvidencePolicy.INVALID_NAMES, assertThrows(IllegalArgumentException.class,
			() -> policy(null, Sets.empty(), Sets.of(Strings.create(" ")), null, null)).getMessage());
		assertEquals(EvidencePolicy.INVALID_NAMES, assertThrows(IllegalArgumentException.class,
			() -> policy(Sets.of((AString) null), Sets.empty(), Sets.empty(), null, null)).getMessage());
		assertEquals(EvidencePolicy.INVALID_NOW, assertThrows(IllegalArgumentException.class,
			() -> OPEN.allows(result(FIRST, NOW, Sets.empty(), null), -1)).getMessage());
	}

	@Test
	void policyProjectsToImmutableLatticeValuesWithoutLosingEmptyRestriction() {
		assertFalse(OPEN.toData().containsKey(EvidencePolicy.ALLOWED_METHODS));
		assertEquals(Maps.of(EvidencePolicy.ALLOWED_METHODS, Sets.empty(),
			EvidencePolicy.REQUIRED_PROPERTIES, Sets.of(MFA),
			EvidencePolicy.REQUIRED_PROOF_PROPERTIES, Sets.of(POSSESSION),
			EvidencePolicy.MAX_AUTHENTICATION_AGE_SECONDS, CVMLong.create(60),
			EvidencePolicy.MAX_PROOF_AGE_SECONDS, CVMLong.create(5)),
			policy(Sets.empty(), Sets.of(MFA), Sets.of(POSSESSION), 60L, 5L).toData());
	}
}
