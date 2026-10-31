package covia.grid;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Agent sub-principal naming and the trust relation between principals.
 */
public class PrincipalsTest {

	private static final AString ALICE_KEY = Strings.create("did:key:z6MkAlice");
	private static final AString ALICE_WEB = Strings.create("did:web:venue.example:u:alice");
	private static final AString BOB_WEB   = Strings.create("did:web:venue.example:u:bob");
	private static final AString CAROL     = Strings.create("carol");

	@Test
	public void testMintAndDestructure() {
		AString agent = Principals.agentDID(ALICE_WEB, CAROL);
		assertEquals("did:web:venue.example:u:alice:g:carol", agent.toString());
		assertTrue(Principals.isAgentDID(agent));
		assertEquals(ALICE_WEB, Principals.userOf(agent));
		assertEquals(CAROL, Principals.agentIdOf(agent));
	}

	@Test
	public void testMethodAgnostic() {
		// The suffix convention composes over any DID method, exactly as the
		// existing <venueDID>:public principal does.
		AString agent = Principals.agentDID(ALICE_KEY, CAROL);
		assertEquals("did:key:z6MkAlice:g:carol", agent.toString());
		assertEquals(ALICE_KEY, Principals.userOf(agent));
	}

	@Test
	public void testPlainPrincipalIsItsOwnUser() {
		assertFalse(Principals.isAgentDID(ALICE_WEB));
		assertEquals(ALICE_WEB, Principals.userOf(ALICE_WEB));
		assertNull(Principals.agentIdOf(ALICE_WEB));
		assertNull(Principals.userOf(null));
	}

	@Test
	public void testPublicPrincipalIsUnaffected() {
		// <venueDID>:public must not be mistaken for an agent.
		AString pub = Strings.create("did:key:z6MkVenue:public");
		assertFalse(Principals.isAgentDID(pub));
		assertEquals(pub, Principals.userOf(pub));
	}

	@Test
	public void testOwnerEndingInAGSegmentStillDestructures() {
		// A user literally named "g" puts a ":g:" in the owner DID too. Parsing
		// on the LAST separator still recovers the owner it was minted from.
		AString owner = Strings.create("did:web:venue.example:u:g");
		AString agent = Principals.agentDID(owner, CAROL);
		assertEquals("did:web:venue.example:u:g:g:carol", agent.toString());
		assertEquals(owner, Principals.userOf(agent));
		assertEquals(CAROL, Principals.agentIdOf(agent));
	}

	@Test
	public void testAgentIdWithColonRejected() {
		// Barring ':' is what makes the last-separator rule sound — otherwise an
		// id could smuggle in a separator and destructure to a different owner.
		assertThrows(IllegalArgumentException.class,
			() -> Principals.agentDID(ALICE_WEB, Strings.create("a:g:b")));
		assertThrows(IllegalArgumentException.class,
			() -> Principals.agentDID(ALICE_WEB, Strings.create("x:y")));
	}

	@Test
	public void testMintRequiresBothParts() {
		assertThrows(IllegalArgumentException.class,
			() -> Principals.agentDID(null, CAROL));
		assertThrows(IllegalArgumentException.class,
			() -> Principals.agentDID(ALICE_WEB, null));
		assertThrows(IllegalArgumentException.class,
			() -> Principals.agentDID(ALICE_WEB, Strings.create("")));
	}

	@Test
	public void testMalformedSuffixIsNotAnAgent() {
		// A trailing or leading separator leaves no usable half either side.
		assertFalse(Principals.isAgentDID(Strings.create("did:web:h:u:alice:g:")));
		assertFalse(Principals.isAgentDID(Strings.create(":g:carol")));
	}

	@Test
	public void testRelations() {
		AString carol = Principals.agentDID(ALICE_WEB, CAROL);
		AString dave  = Principals.agentDID(ALICE_WEB, Strings.create("dave"));
		AString bobcarol = Principals.agentDID(BOB_WEB, CAROL);

		// From the agent's point of view.
		assertEquals(Principals.Relation.SELF,      Principals.relate(carol, ALICE_WEB, carol));
		assertEquals(Principals.Relation.OWNER,     Principals.relate(carol, ALICE_WEB, ALICE_WEB));
		assertEquals(Principals.Relation.SAME_USER, Principals.relate(carol, ALICE_WEB, dave));
		assertEquals(Principals.Relation.FOREIGN,   Principals.relate(carol, ALICE_WEB, BOB_WEB));
		assertEquals(Principals.Relation.FOREIGN,   Principals.relate(carol, ALICE_WEB, bobcarol));

		// From the user's point of view: its own agent is same-user, not owner.
		assertEquals(Principals.Relation.SELF,      Principals.relate(ALICE_WEB, ALICE_WEB, ALICE_WEB));
		assertEquals(Principals.Relation.SAME_USER, Principals.relate(ALICE_WEB, ALICE_WEB, carol));
		assertEquals(Principals.Relation.FOREIGN,   Principals.relate(ALICE_WEB, ALICE_WEB, bobcarol));
	}

	@Test
	public void testRelateFallsBackToParsing() {
		AString carol = Principals.agentDID(ALICE_WEB, CAROL);
		// Without an authoritative user, the owner is recovered from the name.
		assertEquals(Principals.Relation.OWNER, Principals.relate(carol, ALICE_WEB));
		assertEquals(Principals.Relation.FOREIGN, Principals.relate(carol, BOB_WEB));
	}

	@Test
	public void testNullsAreForeign() {
		assertEquals(Principals.Relation.FOREIGN, Principals.relate(null, ALICE_WEB, ALICE_WEB));
		assertEquals(Principals.Relation.FOREIGN, Principals.relate(ALICE_WEB, ALICE_WEB, null));
	}

	@Test
	public void testAuthorityCarriesUserSeparately() {
		Authority a = Authority.ofAgent(ALICE_WEB, CAROL);
		assertEquals(Principals.agentDID(ALICE_WEB, CAROL), a.getDID());
		assertEquals(ALICE_WEB, a.getUserDID());
		assertTrue(a.isSubPrincipal());

		// The namespace survives every augmentation — losing it mid-chain would
		// silently relocate the agent's bare paths.
		assertEquals(ALICE_WEB, a.withGrantScope(convex.core.data.Vectors.empty()).getUserDID());
		assertEquals(ALICE_WEB, a.withProofs(convex.core.data.Vectors.empty()).getUserDID());
	}

	@Test
	public void testAnAgentShapedNameIsItsOwnNamespace() {
		// Anyone may present a DID shaped like an agent's, so the name alone never
		// places it in the owner's namespace; only ofAgent, built by the venue
		// that runs the agent, does.
		AString agent = Principals.agentDID(ALICE_WEB, CAROL);
		Authority named = Authority.of(agent);
		assertEquals(agent, named.getDID());
		assertEquals(agent, named.getUserDID());
		assertFalse(named.isSubPrincipal());
		assertNotEquals(Authority.ofAgent(ALICE_WEB, CAROL), named);
		assertEquals(agent, Authority.of(agent, convex.core.data.Vectors.empty()).getUserDID());
		// The relationship is stated, never parsed.
		assertEquals(Authority.ofAgent(ALICE_WEB, CAROL), named.onBehalfOf(ALICE_WEB).withGrantScope(null));
	}

	@Test
	public void testPlainAuthorityIsItsOwnUser() {
		Authority a = Authority.of(ALICE_WEB);
		assertEquals(ALICE_WEB, a.getUserDID());
		assertFalse(a.isSubPrincipal());
		assertEquals(Authority.of(ALICE_WEB), a);
	}
}
