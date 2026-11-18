package covia.adapter.claudecode;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.exception.ConfigurationException;
import covia.venue.Config;
import covia.venue.Engine;

/**
 * A project's {@code user} may name a user of the venue in short form,
 * {@code u:<name>}, expanded when it is used (AUTHORITY.md, "Where names
 * resolve"); one that names no principal here is a configuration error.
 */
public class ProjectSpecTest {

	private static Engine engine;
	@TempDir private static Path dir;

	@BeforeAll
	static void start() {
		engine = Engine.createTemp(Maps.of(Config.HOSTNAME, Strings.create("projects.test.covia.example")));
	}

	@AfterAll
	static void stop() {
		engine.close();
	}

	private static ProjectSpec project(String user) {
		return ProjectSpec.parse("x", Maps.of("path", dir.toString(), "user", user), true, ProjectSpec.Managed.CONFIG, "venue");
	}

	@Test
	void aProjectsUserMayBeAUserOfTheVenueInShortForm() {
		assertEquals(engine.managedUserDID(Strings.create("bob")), project("u:bob").userDID(engine));
		assertThrows(IllegalArgumentException.class, () -> project("alice"), "anything else is still refused");

		ConfigurationException refused = assertThrows(ConfigurationException.class, () -> project("u:b@d").userDID(engine));
		assertEquals(Engine.CONFIGURED_PRINCIPAL.formatted("project 'x' user", Engine.NOT_A_SHORT_FORM + "u:b@d"),
			refused.getMessage());
	}
}
