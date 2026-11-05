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
import covia.lattice.ACoviaComponent;
import covia.lattice.Namespace;
import covia.lattice.Position;
import covia.lattice.ValuePath;

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
		return user.resolve(ValuePath.parse(path), 0);
	}

	/** Writes through the workspace a path resolves to. */
	private void write(String path, ACell value) {
		Position target = resolve(path);
		target.workspace().write(target.keys(), target.from(), value);
	}

	@Test
	public void testTheAgentsWorkspaceIsItsN() {
		write("g/" + AGENT + "/n/notes", CVMLong.ONE);
		assertEquals(Maps.of("notes", CVMLong.ONE), agent.getRecord().get(N));
		assertEquals(CVMLong.ONE, agent.workspace().read(ValuePath.parse("notes"), 0));
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
		assertEquals(CVMLong.ONE, agent.session(sid).workspace().read(ValuePath.parse("draft"), 0));
		assertEquals(CVMLong.ONE, resolve(session + "/c/draft").read());
	}

	@Test
	public void testTheRestOfTheRecordIsReadOnly() {
		Position status = resolve("g/" + AGENT + "/status");
		assertEquals(AgentState.SLEEPING, status.read());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, status::workspace).getMessage());

		Position meta = resolve(session + "/meta");
		assertEquals(RT.getIn(agent.getSession(sid), "meta"), meta.read());
		assertEquals(ACoviaComponent.READ_ONLY,
			assertThrows(AuthException.class, meta::workspace).getMessage());
	}

	@Test
	public void testAMissingAgentOrSessionIsAbsentAndReadOnly() {
		String missingSession = "g/" + AGENT + "/sessions/" + Blob.fromHex("ff").toHexString();
		for (String path : new String[] {"g/nobody", "g/nobody/n/notes", missingSession, missingSession + "/c/draft",
				"g/" + AGENT + "/sessions/not-hex/c/draft"}) {
			Position position = resolve(path);
			assertNull(position.read(), path);
			assertFalse(position.contains(), path);
			assertEquals(ACoviaComponent.READ_ONLY,
				assertThrows(AuthException.class, position::workspace, path).getMessage());
		}
		assertNull(user.agent("nobody"), "resolving a path creates no agent");
		assertNull(agent.getSession(Blob.fromHex("ff")), "resolving a path creates no session");
	}
}
