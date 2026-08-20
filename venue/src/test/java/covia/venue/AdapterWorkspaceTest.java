package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

class AdapterWorkspaceTest {

	@Test
	void isBoundToTheVenueWorkspaceAndValidatesItsSchemaBoundary() {
		Engine engine=Engine.createTemp(Maps.empty());
		try {
			AdapterWorkspace state=engine.adapterWorkspace("test-store");
			AString user=Strings.create("did:test:alice");
			String relative=state.userPath(user,"preferences/theme");

			assertEquals("w/adapters/test-store",state.rootPath());
			assertEquals("w/adapters/test-store/users/did:test:alice/preferences/theme",state.path(relative));
			state.write(relative,Strings.create("dark"));
			assertEquals(Strings.create("dark"),state.read(relative));
			assertEquals(Strings.create("dark"),engine.resolvePath(Strings.create(state.path(relative)),engine.venueContext()));
			assertNull(engine.resolvePath(Strings.create(state.path(relative)),RequestContext.of(user)),
				"the same path in a user's workspace is distinct from venue-private adapter state");
			assertFalse(state.delete("users/did:test:alice/preferences/missing"));
			state.delete(relative);
			assertNull(state.read(relative));

			assertThrows(IllegalArgumentException.class,()->engine.adapterWorkspace("Bad/Name"));
			assertThrows(IllegalArgumentException.class,()->state.userPath(Strings.create("alice"),"x"));
			assertThrows(IllegalArgumentException.class,()->state.userPath(Strings.create("did:test:a/b"),"x"));
			assertThrows(IllegalArgumentException.class,()->state.write("../escape",Strings.create("x")));
			assertThrows(IllegalArgumentException.class,()->state.write("",Strings.create("x")));
			assertThrows(NullPointerException.class,()->state.write("x",null));
		} finally { engine.close(); }
	}
}
