package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.lattice.ACoviaComponent;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;
import covia.lattice.Position;

/** A path into an agent's record reaches the agent, its workspace and its sessions' workspaces. */
public class AgentPathTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final AString AGENT = Strings.intern("path-agent");
	private static final AString N = Strings.intern("n");
	private static final AString C = Strings.intern("c");

	private User user;
	private AgentState agent;
	private Blob sid;
	private String session;

	@BeforeEach
	public void setup(TestInfo info) {
		user = engine.getVenueState().users().ensure(TestEngine.uniqueDID(info));
		agent = user.ensureAgent(AGENT, Maps.empty(), null);
		sid = Blob.fromHex("00112233445566778899aabbccddeeff");
		agent.ensureSession(sid, null);
		session = "g/" + AGENT + "/sessions/" + sid.toHexString();
	}

	private Position resolve(String path) {
		return user.resolve(CoviaPath.parse(path));
	}

	/** Writes at the entry a path resolves to. */
	private void write(String path, ACell value) {
		resolve(path).entry().write(value);
	}

	@Test
	public void testAPrefixOfAPathEndsWhereThePrefixEnds() {
		String notes = "g/" + AGENT + "/n/notes";
		write(notes, CVMLong.ONE);
		CoviaPath path = CoviaPath.parse(notes);

		Position record = user.resolve(path.prefix(2));
		assertEquals(agent.getRecord(), record.read());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, record::entry).getMessage());

		Position area = user.resolve(path.prefix(3));
		assertEquals(Maps.of("notes", CVMLong.ONE), area.read());
		assertTrue(area.entry().write(Maps.of("notes", CVMLong.create(2))), "the area is the agent's workspace");
		assertEquals(CVMLong.create(2), resolve(notes).read());
	}

	@Test
	public void testTheAgentsWorkspaceIsItsN() {
		write("g/" + AGENT + "/n/notes", CVMLong.ONE);
		assertEquals(Maps.of("notes", CVMLong.ONE), agent.getRecord().get(N));
		assertEquals(CVMLong.ONE, agent.workspace().read(CoviaPath.parse("notes")));
		assertEquals(CVMLong.ONE, resolve("g/" + AGENT + "/n/notes").read());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void testAChangeToTheWorkspaceStampsTheAgent() {
		user.cursor().path(Namespace.G, AGENT).updateAndGet(current ->
			((AMap<AString, ACell>) current).assoc(Strings.intern("ts"), CVMLong.ZERO));
		assertEquals(0, agent.getTs());
		write("g/" + AGENT + "/n/notes", CVMLong.ONE);
		assertTrue(agent.getTs() > 0);
	}

	@Test
	public void testASessionsWorkspaceIsItsC() {
		write(session + "/c/draft", CVMLong.ONE);
		assertEquals(Maps.of("draft", CVMLong.ONE), agent.session(sid).getRecord().get(C));
		assertEquals(CVMLong.ONE, agent.session(sid).workspace().read(CoviaPath.parse("draft")));
		assertEquals(CVMLong.ONE, resolve(session + "/c/draft").read());
	}

	@Test
	public void testTheRestOfTheRecordIsReadOnly() {
		Position status = resolve("g/" + AGENT + "/status");
		assertEquals(AgentState.SLEEPING, status.read());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, status::entry).getMessage());

		Position meta = resolve(session + "/meta");
		assertEquals(RT.getIn(agent.getSession(sid), "meta"), meta.read());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, meta::entry).getMessage());
	}

	@Test
	public void testAMissingAgentOrSessionIsAbsentAndTakesNoChange() {
		String missingSession = "g/" + AGENT + "/sessions/" + Blob.fromHex("ff").toHexString();
		for (String path : new String[] {"g/nobody", missingSession}) {
			Position record = resolve(path);
			assertNull(record.read(), path);
			assertFalse(record.exists(), path);
			assertEquals(ACoviaComponent.READ_ONLY,
				assertThrows(AuthException.class, record::entry, path).getMessage());
		}
		for (String path : new String[] {"g/nobody/n/notes", missingSession + "/c/draft"}) {
			Position entry = resolve(path);
			assertNull(entry.read(), path);
			assertFalse(entry.exists(), path);
			assertThrows(InvalidInputException.class, () -> entry.entry().write(CVMLong.ONE), path);
		}
		assertNull(user.agent("nobody"), "resolving a path creates no agent");
		assertNull(agent.getSession(Blob.fromHex("ff")), "resolving a path creates no session");
	}

	@Test
	public void testASessionIdThatIsNotHexNamesNoSession() {
		Position position = resolve("g/" + AGENT + "/sessions/not-hex/c/draft");
		assertFalse(position.isValid());
		String path = "/g/" + AGENT + "/sessions/not-hex";
		assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, path + "/c/draft", path),
			assertThrows(InvalidInputException.class, position::read).getMessage(),
			"the refusal says where the path stops naming a place");
	}

	@Test
	public void testAPositionSeesAnAgentItsTransactionCreates() {
		AString later = Strings.intern("later-agent");
		user.transact(draft -> {
			Position notes = draft.resolve(CoviaPath.parse("g/" + later + "/n/notes"));
			assertTrue(notes.isValid());
			assertFalse(notes.exists(), "the agent does not exist yet");
			draft.ensureAgent(later, Maps.empty(), null).workspace().write(CoviaPath.parse("notes"), CVMLong.ONE);
			assertTrue(notes.exists(), "the position sees the agent the draft created");
			assertEquals(CVMLong.ONE, notes.read());
		});
		assertEquals(CVMLong.ONE, resolve("g/" + later + "/n/notes").read());
	}
}
