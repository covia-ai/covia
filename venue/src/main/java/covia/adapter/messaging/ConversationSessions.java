package covia.adapter.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

import convex.core.data.Blob;
import covia.venue.AdapterState;
import covia.venue.Ids;

/**
 * Session mappings in a binding's private state, with optional legacy
 * migration: each conversation maps to the id of its agent session, a Blob.
 */
public final class ConversationSessions {
	public static final String BINDING_STOPPED = "Conversation binding is stopped";
	private final AdapterState workspace;
	private final String root;
	private final Function<String, Blob> legacyRead;
	private final Consumer<String> legacyDelete;
	private final Object mutationLock;
	private final BooleanSupplier writable;

	public ConversationSessions(AdapterState workspace, String root) {
		this(workspace, root, key -> null, key -> { });
	}

	public ConversationSessions(AdapterState workspace, String root,
			Function<String, Blob> legacyRead, Consumer<String> legacyDelete) {
		this(workspace, root, legacyRead, legacyDelete, new Object(), () -> true);
	}

	/** Coordinate session writes with a binding's stop/delete lifecycle. */
	public ConversationSessions(AdapterState workspace, String root, Object mutationLock, BooleanSupplier writable) {
		this(workspace, root, key -> null, key -> { }, mutationLock, writable);
	}

	public ConversationSessions(AdapterState workspace, String root,
			Function<String, Blob> legacyRead, Consumer<String> legacyDelete, Object mutationLock, BooleanSupplier writable) {
		this.workspace = Objects.requireNonNull(workspace);
		workspace.path(root + "/check");
		this.root = root;
		this.legacyRead = Objects.requireNonNull(legacyRead);
		this.legacyDelete = Objects.requireNonNull(legacyDelete);
		this.mutationLock = Objects.requireNonNull(mutationLock);
		this.writable = Objects.requireNonNull(writable);
	}

	/** The name of the adapter whose bindings these sessions belong to. */
	public String adapterName() {
		return workspace.adapterName();
	}

	/** The session {@code conversation} continues, or null when it has none yet. */
	public Blob get(String conversation) {
		Blob value = Ids.stored(workspace.read(path(conversation)));
		if (value != null) return value;
		Blob legacy = legacyRead.apply(conversation);
		if (legacy != null) {
			put(conversation, legacy);
			return legacy;
		}
		return null;
	}

	void requireCurrentUser() { workspace.requireCurrentUser(); }

	public void put(String conversation, Blob session) {
		synchronized (mutationLock) {
			if (writable.getAsBoolean()) workspace.write(path(conversation), Objects.requireNonNull(session));
		}
	}

	/** Hold the binding lifecycle lock until a transaction has published. */
	<T> T mutate(java.util.function.Supplier<T> work) {
		synchronized (mutationLock) {
			if (!writable.getAsBoolean()) throw new IllegalStateException(BINDING_STOPPED);
			return work.get();
		}
	}

	/** Writes the mapping in the same transaction as agent intake and its receipt. */
	public void put(covia.venue.VenueState draft, String conversation, Blob session) {
		if (!writable.getAsBoolean()) throw new IllegalStateException(BINDING_STOPPED);
		workspace.inVenue(draft).write(path(conversation), Objects.requireNonNull(session));
	}

	public void remove(String conversation) {
		synchronized (mutationLock) {
			if (!writable.getAsBoolean()) return;
			// Clean the old mapping first so a successful removal cannot resurrect it.
			legacyDelete.accept(conversation);
			workspace.delete(path(conversation));
		}
	}

	private String path(String conversation) { return root + "/" + segment(conversation); }

	/** Preserve existing numeric Telegram/Discord paths; safely encode opaque/composite keys. */
	public static String segment(String key) {
		Objects.requireNonNull(key);
		if (key.matches("[A-Za-z0-9_-]+")) return key;
		return "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
	}
}
