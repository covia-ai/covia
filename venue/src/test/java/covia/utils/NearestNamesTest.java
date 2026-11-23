package covia.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class NearestNamesTest {

	private static final List<String> FIELDS = List.of("kind", "name", "namespace", "labelSelector", "tail");

	@Test
	void aTypingSlipFindsTheNameMeant() {
		assertEquals("namespace", NearestNames.nearest("namespce", FIELDS));
		assertEquals("namespace", NearestNames.nearest("namepsace", FIELDS), "two letters swapped");
		assertEquals("tail", NearestNames.nearest("tial", FIELDS));
		assertEquals("kind", NearestNames.nearest("knd", FIELDS));
		assertEquals("labelSelector", NearestNames.nearest("labelSelecter", FIELDS));
	}

	@Test
	void caseAndSeparatorsDoNotCount() {
		assertEquals("dryRun", NearestNames.nearest("Dry_Run", List.of("force", "dryRun")));
		assertEquals("maxSessions", NearestNames.nearest("max-sessions", List.of("maxSessions")));
		assertEquals("ns", NearestNames.nearest("NS", List.of("ns")), "even a short name, when only its case differs");
	}

	@Test
	void aNameThatIsPartOfAnotherFindsIt() {
		assertEquals("ed25519-seed", NearestNames.nearest("ed25519", List.of("password", "token", "ed25519-seed")));
		assertEquals("labelSelector", NearestNames.nearest("selector", FIELDS));
	}

	@Test
	void nothingRemotelyCloseIsNull() {
		assertNull(NearestNames.nearest("colour", FIELDS));
		assertNull(NearestNames.nearest("replicas", FIELDS));
		assertNull(NearestNames.nearest("ab", List.of("cd", "ef")), "short names are never guessed at");
		assertNull(NearestNames.nearest("x", List.of("y")));
		assertNull(NearestNames.nearest("na", FIELDS), "too short to be part of another");
		assertNull(NearestNames.nearest("anything", List.of()));
		assertNull(NearestNames.nearest(null, FIELDS));
	}

	@Test
	void theClosestWinsAndTiesGoToTheFirst() {
		assertEquals("name", NearestNames.nearest("names", List.of("namespace", "name")));
		assertEquals("name", NearestNames.nearest("names", List.of("name", "namespace")));
		assertEquals("bat", NearestNames.nearest("cat", List.of("bat", "hat")));
		assertEquals("hat", NearestNames.nearest("cat", List.of("hat", "bat")));
	}

	@Test
	void theHintNamesTheNearestOrSaysNothing() {
		assertEquals(NearestNames.DID_YOU_MEAN.formatted("namespace"), NearestNames.hint("namespce", FIELDS));
		assertEquals("", NearestNames.hint("colour", FIELDS));
	}
}
