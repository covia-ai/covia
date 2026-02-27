package covia.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.AgentState;
import covia.venue.SecretStore;
import covia.venue.User;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * LLM-backed transition function for agents.
 *
 * <p>Invoked by {@code agent:run} as a transition operation ({@code llmagent:chat}).
 * Maintains conversation history in the agent's {@code state} field and calls an
 * LLM to generate responses.</p>
 *
 * <h3>State structure</h3>
 * <pre>{@code
 * { "config": {
 *     "provider": "openai",
 *     "model": "gpt-4o-mini",
 *     "systemPrompt": "You are..."
 *   },
 *   "history": [
 *     { "role": "system",    "content": "You are..." },
 *     { "role": "user",      "content": "Hello" },
 *     { "role": "assistant", "content": "Hi there!" }
 *   ]
 * }}</pre>
 *
 * <h3>LLM configuration</h3>
 * <p>Read from {@code state.config} — set at agent creation via initial state,
 * preserved across runs by the transition function. The agent record's framework
 * {@code config} is not used; LLM settings belong to the transition function's
 * domain. This allows an agent to switch transition functions without config
 * conflicts.</p>
 * <ul>
 *   <li>{@code provider} — {@code "openai"} | {@code "ollama"} | {@code "test"} (default: {@code "openai"})</li>
 *   <li>{@code model} — model name (default: {@code "gpt-4o-mini"})</li>
 *   <li>{@code systemPrompt} — system message</li>
 *   <li>{@code url} — base URL override</li>
 * </ul>
 *
 * <h3>API key resolution</h3>
 * <ol>
 *   <li>Optional {@code apiKey} in the transition function input (testing only — plaintext in results!)</li>
 *   <li>User's secret store, using the name from operation metadata {@code operation.secretKey}
 *       (default: {@code "OPENAI_API_KEY"})</li>
 * </ol>
 */
public class LLMAgentAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(LLMAgentAdapter.class);

	/** IO timeout for LLM API calls */
	private static final Duration IO_TIMEOUT = Duration.ofSeconds(120);

	private static final AString DEFAULT_SYSTEM_PROMPT = Strings.create(
		"You are a helpful AI assistant on the Covia platform. "
		+ "Give concise, clear and accurate responses.");

	// Config field keys (read from state.config, set at agent creation)
	private static final AString K_CONFIG        = Strings.intern("config");
	private static final AString K_PROVIDER      = Strings.intern("provider");
	private static final AString K_MODEL         = Strings.intern("model");
	private static final AString K_SYSTEM_PROMPT = Strings.intern("systemPrompt");
	private static final AString K_URL           = Strings.intern("url");

	// Operation metadata key for the secret name
	private static final AString K_SECRET_KEY    = Strings.intern("secretKey");

	// Input key for optional testing override
	private static final AString K_API_KEY       = Strings.intern("apiKey");

	// History field keys
	private static final AString K_HISTORY  = Strings.intern("history");
	private static final AString K_ROLE     = Strings.intern("role");
	private static final AString K_CONTENT  = Strings.intern("content");
	private static final AString K_RESPONSE = Strings.intern("response");

	// Role values
	private static final AString ROLE_SYSTEM    = Strings.intern("system");
	private static final AString ROLE_USER      = Strings.intern("user");
	private static final AString ROLE_ASSISTANT = Strings.intern("assistant");

	@Override
	public String getName() {
		return "llmagent";
	}

	@Override
	public String getDescription() {
		return "LLM-backed transition function for agents. Maintains conversation "
			+ "history and LLM configuration in agent state (state.config), processes "
			+ "inbox messages as user turns, and calls an LLM to generate responses. "
			+ "API key resolved from the user's secret store using the name in "
			+ "operation metadata.";
	}

	@Override
	protected void installAssets() {
		installAsset("/adapters/llmagent/chat.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		throw new UnsupportedOperationException(
			"LLMAgentAdapter requires caller DID — use invoke(Job, ...) path");
	}

	@Override
	public void invoke(Job job, String operation, ACell meta, ACell input) {
		job.setStatus(covia.grid.Status.STARTED);

		CompletableFuture.supplyAsync(() -> {
			AString callerDID = RT.ensureString(job.getData().get(Fields.CALLER));
			return processChat(callerDID, meta, input);
		}, VIRTUAL_EXECUTOR)
		.thenAccept(job::completeWith)
		.exceptionally(e -> {
			Throwable cause = (e instanceof java.util.concurrent.CompletionException && e.getCause() != null)
				? e.getCause() : e;
			job.fail(cause.getMessage());
			return null;
		});
	}

	/**
	 * Core transition function logic.
	 *
	 * <p>LLM configuration is read from {@code state.config} (set at agent creation,
	 * preserved across runs). The agent record's framework config is not used.</p>
	 *
	 * @param callerDID Caller DID for secret store access
	 * @param meta Operation metadata (from chat.json) — contains secretKey
	 * @param input Transition function contract: { agentId, state, messages, apiKey? }
	 * @return Transition function output: { state, result }
	 */
	@SuppressWarnings("unchecked")
	ACell processChat(AString callerDID, ACell meta, ACell input) {
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		AVector<ACell> messages = (AVector<ACell>) RT.getIn(input, Fields.MESSAGES);

		// Look up user for secret access
		User user = resolveUser(callerDID);

		// Read LLM config from state (set at creation, preserved across runs)
		AMap<AString, ACell> config = extractConfig(state);

		// Extract LLM settings from config
		String provider = getConfigString(config, K_PROVIDER, "openai");
		String model = getConfigString(config, K_MODEL, "gpt-4o-mini");
		String systemPrompt = getConfigString(config, K_SYSTEM_PROMPT, null);
		String apiKey = resolveApiKey(meta, input, user);
		String url = getConfigString(config, K_URL, null);

		// Reconstruct conversation history from state
		AVector<ACell> history = extractHistory(state);

		// If no system prompt in history yet, prepend one
		if (history.count() == 0 || !ROLE_SYSTEM.equals(RT.getIn(history.get(0), K_ROLE))) {
			AString sysContent = (systemPrompt != null)
				? Strings.create(systemPrompt)
				: DEFAULT_SYSTEM_PROMPT;
			AMap<AString, ACell> sysEntry = Maps.of(
				K_ROLE, ROLE_SYSTEM,
				K_CONTENT, sysContent
			);
			history = (AVector<ACell>) Vectors.of(sysEntry).concat(history);
		}

		// Append each inbox message as a user turn
		if (messages != null) {
			for (long i = 0; i < messages.count(); i++) {
				ACell msg = messages.get(i);
				AString content = RT.ensureString(RT.getIn(msg, K_CONTENT));
				if (content == null) {
					// Fall back to treating the whole message as content
					content = RT.ensureString(msg);
				}
				if (content != null) {
					history = history.conj(Maps.of(
						K_ROLE, ROLE_USER,
						K_CONTENT, content
					));
				}
			}
		}

		// Call LLM (or test provider)
		String assistantText;
		if ("test".equals(provider)) {
			// Test provider: echo back the last user message
			assistantText = getLastUserContent(history);
		} else {
			// Build ChatModel and call LLM
			ChatModel chatModel = buildChatModel(provider, model, apiKey, url);
			List<ChatMessage> chatMessages = toChatMessages(history);
			ChatResponse response = chatModel.chat(chatMessages);
			AiMessage reply = response.aiMessage();
			assistantText = stripThinkTags(reply.text());
		}

		// Append assistant response to history
		history = history.conj(Maps.of(
			K_ROLE, ROLE_ASSISTANT,
			K_CONTENT, Strings.create(assistantText)
		));

		// Return transition function output (preserve config in state)
		ACell newState = Maps.of(K_HISTORY, history);
		if (config != null) {
			newState = ((AMap<AString, ACell>) newState).assoc(K_CONFIG, config);
		}
		ACell result = Maps.of(K_RESPONSE, Strings.create(assistantText));
		return Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESULT, result
		);
	}

	// ========== Internal helpers ==========

	/**
	 * Resolves the User from the lattice, or null if not found.
	 */
	private User resolveUser(AString callerDID) {
		if (callerDID == null || engine == null) return null;
		try {
			return engine.getVenueState().users().get(callerDID);
		} catch (Exception e) {
			log.warn("Failed to resolve user {}", callerDID, e);
			return null;
		}
	}

	/**
	 * Extracts the LLM config map from agent state.
	 *
	 * <p>Config is stored at {@code state.config} — set at agent creation as part
	 * of initial state, and preserved across runs by the transition function.</p>
	 */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> extractConfig(ACell state) {
		if (state == null) return null;
		ACell c = RT.getIn(state, K_CONFIG);
		if (c instanceof AMap) return (AMap<AString, ACell>) c;
		return null;
	}

	/**
	 * Resolves the API key from input or the user's secret store.
	 *
	 * <p>Resolution order:</p>
	 * <ol>
	 *   <li>Optional {@code apiKey} in input (testing only — will be plaintext in results)</li>
	 *   <li>User's secret store, name from {@code operation.secretKey} in metadata</li>
	 * </ol>
	 *
	 * @param meta Operation metadata (contains {@code operation.secretKey})
	 * @param input Transition function input (may contain {@code apiKey} override)
	 * @param user User for secret store access
	 * @return API key string, or null if not available
	 */
	private String resolveApiKey(ACell meta, ACell input, User user) {
		// 1. Optional input override (testing)
		AString inputKey = RT.ensureString(RT.getIn(input, K_API_KEY));
		if (inputKey != null) return inputKey.toString();

		// 2. Secret store, name from operation metadata
		AString secretName = RT.ensureString(RT.getIn(meta, "operation", K_SECRET_KEY));
		if (secretName != null) {
			String decrypted = decryptSecret(user, secretName);
			if (decrypted != null) return decrypted;
		}

		return null;
	}

	/**
	 * Decrypts a secret from the user's secret store, or null if unavailable.
	 */
	private String decryptSecret(User user, AString secretName) {
		if (user == null || engine == null) return null;
		try {
			byte[] encKey = SecretStore.deriveKey(engine.getKeyPair());
			AString value = user.secrets().decrypt(secretName, encKey);
			return (value != null) ? value.toString() : null;
		} catch (Exception e) {
			log.debug("Could not decrypt secret '{}': {}", secretName, e.getMessage());
			return null;
		}
	}

	/**
	 * Extracts a string config value with a default.
	 */
	private String getConfigString(AMap<AString, ACell> config, AString key, String defaultValue) {
		if (config == null) return defaultValue;
		AString val = RT.ensureString(config.get(key));
		return (val != null) ? val.toString() : defaultValue;
	}

	/**
	 * Extracts the history vector from the agent state.
	 */
	@SuppressWarnings("unchecked")
	static AVector<ACell> extractHistory(ACell state) {
		if (state == null) return Vectors.empty();
		ACell h = RT.getIn(state, K_HISTORY);
		if (h instanceof AVector) return (AVector<ACell>) h;
		return Vectors.empty();
	}

	/**
	 * Converts history (AVector of AMaps) to LangChain4j ChatMessage list.
	 */
	static List<ChatMessage> toChatMessages(AVector<ACell> history) {
		List<ChatMessage> messages = new ArrayList<>();
		for (long i = 0; i < history.count(); i++) {
			ACell entry = history.get(i);
			AString role = RT.ensureString(RT.getIn(entry, K_ROLE));
			AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
			if (role == null || content == null) continue;

			String roleStr = role.toString();
			String contentStr = content.toString();
			switch (roleStr) {
				case "system":
					messages.add(SystemMessage.from(contentStr));
					break;
				case "user":
					messages.add(UserMessage.from(contentStr));
					break;
				case "assistant":
					messages.add(AiMessage.from(contentStr));
					break;
				default:
					log.warn("Unknown role in history: {}", roleStr);
			}
		}
		return messages;
	}

	/**
	 * Builds a ChatModel from config settings.
	 */
	private ChatModel buildChatModel(String provider, String model, String apiKey, String url) {
		if ("ollama".equals(provider)) {
			String baseUrl = (url != null) ? url : "http://localhost:11434";
			return LangChainAdapter.buildOllamaModel(baseUrl, model, IO_TIMEOUT);
		} else {
			// Default to OpenAI-compatible
			String baseUrl = (url != null) ? url : "https://api.openai.com/v1";
			return LangChainAdapter.buildOpenAiModel(apiKey, baseUrl, model, IO_TIMEOUT);
		}
	}

	/**
	 * Strips {@code <think>...</think>} tags from model output (e.g. qwen, deepseek-r1).
	 */
	static String stripThinkTags(String text) {
		if (text == null) return null;
		if (text.contains("</think>")) {
			int end = text.lastIndexOf("</think>");
			text = text.substring(end + 8).trim();
		}
		return text;
	}

	/**
	 * Gets the content of the last user message in history (for test provider).
	 */
	private String getLastUserContent(AVector<ACell> history) {
		for (long i = history.count() - 1; i >= 0; i--) {
			ACell entry = history.get(i);
			if (ROLE_USER.equals(RT.getIn(entry, K_ROLE))) {
				AString content = RT.ensureString(RT.getIn(entry, K_CONTENT));
				return (content != null) ? content.toString() : "(empty)";
			}
		}
		return "(no user message)";
	}
}
