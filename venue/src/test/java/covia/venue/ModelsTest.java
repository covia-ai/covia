package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;

/** The profile an agent's config resolves to, through whatever looks up its references. */
public class ModelsTest {

	private static final AString PROVIDER = Strings.create("v/ops/provider");
	private static final AString TINY = Strings.create("o/models/tiny");

	private static AMap<AString, ACell> meta(Object... facet) {
		return Maps.of("operation", Maps.of("adapter", "test:llm"), "model", Maps.of(facet));
	}

	/** A provider whose model "small" cannot call tools, and a definition of "tiny" over it that says the same. */
	private static final Map<AString, AMap<AString, ACell>> CATALOG = Map.of(
		PROVIDER, meta("options", Maps.of("labels", "xml"),
			"byModel", Maps.of("small", Maps.of("options", Maps.of("toolCalling", CVMBool.FALSE)))),
		TINY, meta("id", "tiny", "provider", PROVIDER, "options", Maps.of("toolCalling", CVMBool.FALSE)));

	private static AMap<AString, ACell> profile(AMap<AString, ACell> config) {
		return Models.agentProfile(config, CATALOG::get);
	}

	@Test
	public void testLlmOperationDefaultsWhenTheConfigNamesNone() {
		assertEquals(Strings.create("v/ops/langchain/ollama"),
			Models.llmOperation(Maps.of(Models.K_LLM_OPERATION, "v/ops/langchain/ollama")));
		assertEquals(Models.DEFAULT_LLM_OPERATION, Models.llmOperation(Maps.of(Models.K_MODEL, "gpt-4o")));
		assertEquals(Models.DEFAULT_LLM_OPERATION, Models.llmOperation(null));
	}

	@Test
	public void testProfileLayersProviderModelDefinitionAndAgent() {
		AMap<AString, ACell> provider = profile(Maps.of(Models.K_LLM_OPERATION, PROVIDER));
		assertTrue(Models.toolCalling(provider));
		assertEquals(Strings.create("xml"), RT.getIn(provider, Models.K_OPTIONS, "labels"));

		AMap<AString, ACell> small = profile(Maps.of(Models.K_LLM_OPERATION, PROVIDER, Models.K_MODEL, "small"));
		assertFalse(Models.toolCalling(small), "the model's entry in the provider");
		assertEquals(Strings.create("xml"), RT.getIn(small, Models.K_OPTIONS, "labels"), "what it does not state survives");

		AMap<AString, ACell> tiny = profile(Maps.of(Models.K_LLM_OPERATION, TINY));
		assertFalse(Models.toolCalling(tiny), "the model definition, over its provider");
		assertNull(tiny.get(Models.K_MODEL_ID), "a definition's identity is not part of the profile");

		AMap<AString, ACell> other = profile(Maps.of(Models.K_LLM_OPERATION, TINY, Models.K_MODEL, "large"));
		assertTrue(Models.toolCalling(other), "a definition speaks only for its own model");

		AMap<AString, ACell> agent = profile(Maps.of(Models.K_LLM_OPERATION, TINY,
			Models.K_MODEL_PROFILE, Maps.of("options", Maps.of("toolCalling", CVMBool.TRUE))));
		assertTrue(Models.toolCalling(agent), "the agent's own profile is layered last");
	}

	@Test
	public void testNoProfileWhenAReferenceDoesNotResolve() {
		assertNull(profile(Maps.of(Models.K_LLM_OPERATION, "v/ops/missing")));
		assertNull(profile(Maps.empty()), "the default model is not in this catalogue");
		assertNull(Models.agentProfile(Maps.of(Models.K_LLM_OPERATION, TINY), ref -> TINY.equals(ref) ? CATALOG.get(TINY) : null),
			"a definition whose provider does not resolve");
	}

	@Test
	public void testOperationIsMetadataWithAnOperation() {
		assertNotNull(Models.operation(CATALOG.get(PROVIDER)));
		assertNull(Models.operation(Maps.of("model", Maps.empty())));
		assertNull(Models.operation(Strings.create("text")));
		assertNull(Models.operation(null));
	}
}
