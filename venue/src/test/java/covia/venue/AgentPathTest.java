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
import convex.core.data.Path;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import convex.lattice.NoSuchPathException;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.lattice.ACoviaComponent;
import covia.lattice.CoviaPath;
import covia.lattice.Entry;
import covia.lattice.Namespace;

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

	private ALatticeComponent<?> resolve(String path) {
		return user.resolve(CoviaPath.parse(path));
	}

	private static ACell read(ALatticeComponent<?> component) {
		return ACoviaComponent.read(component);
	}

	/** Writes at the entry a path resolves to. */
	private void write(String path, ACell value) {
		Entry.of(resolve(path)).write(value);
	}

	@Test
	public void testAPrefixOfAPathEndsWhereThePrefixEnds() {
		String notes = "g/" + AGENT + "/n/notes";
		write(notes, CVMLong.ONE);
		Path path = CoviaPath.parse(notes);

		ALatticeComponent<?> record = user.resolve(path.slice(0, 2));
		assertEquals(agent.getRecord(), read(record));
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, () -> Entry.of(record)).getMessage());

		ALatticeComponent<?> area = user.resolve(path.slice(0, 3));
		assertEquals(Maps.of("notes", CVMLong.ONE), read(area));
		assertTrue(Entry.of(area).write(Maps.of("notes", CVMLong.create(2))), "the area is the agent's workspace");
		assertEquals(CVMLong.create(2), read(resolve(notes)));
	}

	@Test
	public void testTheAgentsWorkspaceIsItsN() {
		write("g/" + AGENT + "/n/notes", CVMLong.ONE);
		assertEquals(Maps.of("notes", CVMLong.ONE), agent.getRecord().get(N));
		assertEquals(CVMLong.ONE, agent.workspace().read(CoviaPath.parse("notes")));
		assertEquals(CVMLong.ONE, read(resolve("g/" + AGENT + "/n/notes")));
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
		assertEquals(CVMLong.ONE, read(resolve(session + "/c/draft")));
	}

	@Test
	public void testTheRestOfTheRecordIsReadOnly() {
		ALatticeComponent<?> status = resolve("g/" + AGENT + "/status");
		assertEquals(AgentState.SLEEPING, read(status));
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, () -> Entry.of(status)).getMessage());

		ALatticeComponent<?> meta = resolve(session + "/meta");
		assertEquals(RT.getIn(agent.getSession(sid), "meta"), read(meta));
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, () -> Entry.of(meta)).getMessage());
	}

	@Test
	public void testAMissingAgentOrSessionIsAbsentAndTakesNoChange() {
		String missingSession = "g/" + AGENT + "/sessions/" + Blob.fromHex("ff").toHexString();
		for (String path : new String[] {"g/nobody", missingSession}) {
			ALatticeComponent<?> record = resolve(path);
			assertNull(read(record), path);
			assertFalse(record.exists(), path);
			assertEquals(ACoviaComponent.READ_ONLY,
				assertThrows(AuthException.class, () -> Entry.of(record), path).getMessage());
		}
		for (String path : new String[] {"g/nobody/n/notes", missingSession + "/c/draft"}) {
			ALatticeComponent<?> entry = resolve(path);
			assertNull(read(entry), path);
			assertFalse(entry.exists(), path);
			assertThrows(InvalidInputException.class, () -> Entry.of(entry).write(CVMLong.ONE), path);
		}
		assertNull(user.agent("nobody"), "resolving a path creates no agent");
		assertNull(agent.getSession(Blob.fromHex("ff")), "resolving a path creates no session");
	}

	@Test
	public void testASessionIdThatIsNotHexNamesNoSession() {
		NoSuchPathException e = assertThrows(NoSuchPathException.class,
			() -> resolve("g/" + AGENT + "/sessions/not-hex/c/draft"));
		String path = "/g/" + AGENT + "/sessions/not-hex";
		assertEquals(String.format(ACoviaComponent.NO_SUCH_PATH, path + "/c/draft", path),
			ACoviaComponent.noSuchPath(e), "the refusal says where the path stops naming a place");
	}

	@Test
	public void testAPathSeesAnAgentItsTransactionCreates() {
		AString later = Strings.intern("later-agent");
		user.execute(draft -> {
			ALatticeComponent<?> notes = draft.resolve(CoviaPath.parse("g/" + later + "/n/notes"));
			assertFalse(notes.exists(), "the agent does not exist yet");
			draft.ensureAgent(later, Maps.empty(), null).workspace().write(CoviaPath.parse("notes"), CVMLong.ONE);
			assertTrue(notes.exists(), "the resolved entry sees the agent the draft created");
			assertEquals(CVMLong.ONE, read(notes));
		});
		assertEquals(CVMLong.ONE, read(resolve("g/" + later + "/n/notes")));
	}
}
