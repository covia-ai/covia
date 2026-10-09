package covia.adapter.messaging;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.venue.AdapterWorkspace;

/** Session mappings in a binding's private workspace, with optional legacy migration. */
public final class ConversationSessions {
	private final AdapterWorkspace workspace;
	private final String root;
	private final Function<String, String> legacyRead;
	private final Consumer<String> legacyDelete;
	private final Object mutationLock;
	private final BooleanSupplier writable;

	public ConversationSessions(AdapterWorkspace workspace, String root) {
		this(workspace, root, key -> null, key -> { });
	}

	public ConversationSessions(AdapterWorkspace workspace, String root,
			Function<String, String> legacyRead, Consumer<String> legacyDelete) {
		this(workspace, root, legacyRead, legacyDelete, new Object(), () -> true);
	}

	/** Coordinate session writes with a binding's stop/delete lifecycle. */
	public ConversationSessions(AdapterWorkspace workspace, String root, Object mutationLock, BooleanSupplier writable) {
		this(workspace, root, key -> null, key -> { }, mutationLock, writable);
	}

	private ConversationSessions(AdapterWorkspace workspace, String root,
			Function<String, String> legacyRead, Consumer<String> legacyDelete, Object mutationLock, BooleanSupplier writable) {
		this.workspace = Objects.requireNonNull(workspace);
		workspace.path(root + "/check");
		this.root = root;
		this.legacyRead = Objects.requireNonNull(legacyRead);
		this.legacyDelete = Objects.requireNonNull(legacyDelete);
		this.mutationLock = Objects.requireNonNull(mutationLock);
		this.writable = Objects.requireNonNull(writable);
	}

	public String get(String conversation) {
		AString value = RT.ensureString(workspace.read(path(conversation)));
		if (value != null && !value.isEmpty()) return value.toString();
		String legacy = legacyRead.apply(conversation);
		if (legacy != null && !legacy.isEmpty()) {
			put(conversation, legacy);
			return legacy;
		}
		return null;
	}

	public void put(String conversation, String session) {
		synchronized (mutationLock) {
			if (writable.getAsBoolean()) workspace.write(path(conversation), Strings.create(Objects.requireNonNull(session)));
		}
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
