package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.adapter.CoviaAdapter;
import covia.lattice.CoviaPath;

/**
 * The path grammar of GRID_LATTICE_DESIGN.md §3: where a path starts, and
 * which paths name no place, with a refusal that says where the path stops
 * naming one.
 */
public class AddressingTest {

	final Engine engine = TestEngine.ENGINE;
	final CoviaAdapter covia = (CoviaAdapter) engine.getAdapter("covia");

	AString alice;
	RequestContext ALICE;

	@BeforeEach
	void setup(TestInfo info) {
		alice = TestEngine.uniqueDID(info);
		ALICE = RequestContext.of(alice);
		engine.getVenueState().users().ensure(alice).workspace().write(CoviaPath.parse("notes"), Strings.create("mine"));
	}

	private ACell read(ACell path) {
		return covia.resolve(ALICE, path).read();
	}

	private ACell read(String path) {
		return read(Strings.create(path));
	}

	/** The refusal for {@code path}, which must name no place. */
	private String noPlace(String path) {
		String reason = covia.resolve(ALICE, Strings.create(path)).reasonInvalid();
		assertNotNull(reason, path + " names a place");
		return reason;
	}

	@Test
	public void testEveryWayOfWritingAPathNamesTheSamePlace() {
		assertEquals(Strings.create("mine"), read("w/notes"));
		assertEquals(Strings.create("mine"), read("/w/notes"), "one leading slash names the request's root");
		assertEquals(Strings.create("mine"), read(alice + "/w/notes"));
		assertEquals(Strings.create("mine"), read(Vectors.of(alice, "w", "notes")), "an array starting with a DID is that DID URL");
		assertEquals(Strings.create("mine"), read(Vectors.of("w", "notes")));
	}

	@Test
	public void testADIDStartsAPathOnlyAsItsFirstElement() {
		String slashed = "/" + alice + "/w/notes";
		assertEquals(String.format(CoviaPath.NO_SUCH_PATH, slashed, "/" + alice), noPlace(slashed));
		assertNull(engine.resolvePath(Strings.create(slashed), ALICE));
	}

	@Test
	public void testAContextRootedNamespaceAfterADIDNamesNoPlace() {
		for (String namespace : new String[] {"n", "c", "t", "v"}) {
			String path = alice + "/" + namespace + "/x";
			assertEquals(String.format(CoviaPath.NO_SUCH_PATH, "/" + path, "/" + alice + "/" + namespace),
				noPlace(path), path);
		}
	}

	@Test
	public void testARefusalSaysWhereThePathStopsNamingAPlace() {
		assertEquals(String.format(CoviaPath.NO_SUCH_PATH, "/wrok/notes/today", "/wrok"), noPlace("wrok/notes/today"));
		assertEquals(String.format(CoviaPath.NO_SUCH_PATH, "/j/not-hex/temp", "/j/not-hex"), noPlace("j/not-hex/temp"));
		assertNull(covia.resolve(ALICE, Strings.create("w/no/such/value")).reasonInvalid(),
			"a missing value is a place with nothing there");
	}

	@Test
	public void testADidWebDIDKeepsItsColonsAndEncodedPort() {
		AString web = Strings.create("did:web:example.com%3A8080:u:alice");
		Engine.UserPathTarget target = engine.resolveUserPath(ALICE, Strings.create(web + "/w/notes"), false);
		assertEquals(web, target.ownerDID());
		assertEquals(CoviaPath.parse("w/notes"), target.path());
	}
}
