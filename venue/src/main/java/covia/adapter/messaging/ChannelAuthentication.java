package covia.adapter.messaging;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.venue.Engine;
import covia.venue.auth.AuthResult;

/**
 * Channel authentication (COG-3 §7, AUTH.md §5.3). A messaging binding is the
 * authentication method for the messages it receives: the provider identifies
 * the sender, the adapter verifies delivery and builds the {@link Channel}
 * from the verified payload, and {@link #authenticate} identifies the
 * principal the message comes from.
 */
public final class ChannelAuthentication {
	private ChannelAuthentication() {}

	/** Delivery verified by the provider's signature over each callback. */
	public static final AString SIGNED_CALLBACK = Strings.intern("signed-callback");
	/** Delivery over the binding's own authenticated connection to the provider. */
	public static final AString PROVIDER_SESSION = Strings.intern("provider-session");

	/** Chat kinds. */
	public static final AString DIRECT = Strings.intern("direct");
	public static final AString GROUP = Strings.intern("group");
	public static final AString CHANNEL = Strings.intern("channel");

	public static final AString K_SENDER = Strings.intern("sender");
	public static final AString K_CHAT = Strings.intern("chat");
	public static final AString K_THREAD = Strings.intern("thread");
	public static final AString K_SPACE = Strings.intern("space");
	public static final AString K_MESSAGE = Strings.intern("message");
	public static final AString K_ID = Strings.intern("id");
	public static final AString K_HANDLE = Strings.intern("handle");
	public static final AString K_NAME = Strings.intern("name");
	public static final AString K_KIND = Strings.intern("kind");
	private static final AString K_METHOD = Strings.intern("method");
	private static final AString K_CREDENTIAL = Strings.intern("credential");
	private static final AString K_IDENTITY = Strings.intern("identity");
	private static final AString K_DETAILS = Strings.intern("details");

	/** Why a verified sender's message creates no work. */
	public static final String NOT_ADMITTED = "The binding neither lists this sender nor is open";
	/** A channel is built from a verified sender id, and a chat id when it has a chat. */
	public static final String INCOMPLETE = "A channel needs the sender's id, and the chat's id when it has a chat";

	/**
	 * The channel a verified message came through: the platform (the adapter's
	 * name), how delivery was verified, the sender's provider-scoped identity and
	 * the standard details. Built only from a verified payload, never from
	 * message text.
	 */
	public record Channel(AString method, AString credential, AString identity, AMap<AString, ACell> details) {
		public Channel {
			Objects.requireNonNull(method, "method");
			Objects.requireNonNull(credential, "credential");
			Objects.requireNonNull(identity, "identity");
			Objects.requireNonNull(details, "details");
		}

		/**
		 * A channel with its sender ({@link ChannelAuthentication#sender}) and the chat the
		 * message was sent in ({@link ChannelAuthentication#chat}); null for an event in
		 * no chat, such as an inline query.
		 */
		public static Channel of(String method, AString credential, AMap<AString, ACell> sender, AMap<AString, ACell> chat) {
			AString id = RT.ensureString(sender.get(K_ID));
			if (id == null || (chat != null && chat.get(K_ID) == null)) throw new IllegalArgumentException(INCOMPLETE);
			AMap<AString, ACell> details = Maps.of(K_SENDER, sender);
			return new Channel(Strings.create(method), credential, Strings.create(method + ":" + id),
				chat == null ? details : details.assoc(K_CHAT, chat));
		}

		/** The thread within the chat; null for none. */
		public Channel thread(String thread) {
			return thread == null ? this : with(K_THREAD, Strings.create(thread));
		}

		/**
		 * The provider account enclosing the chat: a workspace, a server, a business
		 * number. With {@code scopesSenders} the provider's sender ids are unique only
		 * within it, so the identity is qualified as {@code <method>:<space>/<id>}.
		 */
		public Channel space(String id, String name, boolean scopesSenders) {
			if (id == null) return this;
			Channel withSpace = with(K_SPACE, party(id, null, name));
			if (!scopesSenders) return withSpace;
			return new Channel(method, credential, Strings.create(method + ":" + id + "/" + senderId()), withSpace.details);
		}

		/** The provider's identifier for this message; null for none. */
		public Channel message(String id) {
			return id == null ? this : with(K_MESSAGE, Strings.create(id));
		}

		/** Provider-specific fields, under the platform's name. */
		public Channel extras(AMap<AString, ACell> extras) {
			return extras == null || extras.isEmpty() ? this : with(method, extras);
		}

		public AMap<AString, ACell> sender() { return RT.ensureMap(details.get(K_SENDER)); }
		public String senderId() { return RT.ensureString(sender().get(K_ID)).toString(); }

		private Channel with(AString key, ACell value) {
			return new Channel(method, credential, identity, details.assoc(key, value));
		}

		/** The channel as data, for a binding's private inbox. */
		public AMap<AString, ACell> toData() {
			return Maps.of(K_METHOD, method, K_CREDENTIAL, credential, K_IDENTITY, identity, K_DETAILS, details);
		}

		/** A channel the adapter recorded with {@link #toData()} in its own private state; null for none. */
		public static Channel fromData(ACell data) {
			if (!(data instanceof AMap<?, ?>)) return null;
			AMap<AString, ACell> map = RT.ensureMap(data);
			return new Channel(RT.ensureString(map.get(K_METHOD)), RT.ensureString(map.get(K_CREDENTIAL)),
				RT.ensureString(map.get(K_IDENTITY)), RT.ensureMap(map.get(K_DETAILS)));
		}
	}

	/** A sender: the provider's id, a handle whose characters the provider restricts, a display name. */
	public static AMap<AString, ACell> sender(String id, String handle, String name) {
		return party(id, handle, name);
	}

	/** A chat: its id, its {@code kind} ({@link #DIRECT}, {@link #GROUP}, {@link #CHANNEL}) and its title. */
	public static AMap<AString, ACell> chat(String id, AString kind, String name) {
		AMap<AString, ACell> chat = party(id, null, name);
		return kind == null ? chat : chat.assoc(K_KIND, kind);
	}

	private static AMap<AString, ACell> party(String id, String handle, String name) {
		AMap<AString, ACell> party = Maps.of(K_ID, Strings.create(Objects.requireNonNull(id, "id")));
		if (handle != null && !handle.isBlank()) party = party.assoc(K_HANDLE, Strings.create(handle));
		if (name != null && !name.isBlank()) party = party.assoc(K_NAME, Strings.create(name));
		return party;
	}

	/**
	 * Identifies who a verified message comes from and returns the method's
	 * result: the binding's principal for a sender it lists, the venue's public
	 * principal for any other sender on an open binding.
	 *
	 * @param principal the binding's principal: its user, or the agent that created it
	 * @param listed whether the binding lists the channel's sender
	 * @throws AuthException with {@link #NOT_ADMITTED} when the binding neither
	 *         lists the sender nor is open
	 */
	public static AuthResult authenticate(Engine engine, MessagingBotSpec spec, AString principal,
			boolean listed, Channel channel) {
		AString user;
		if (listed) user = principal;
		else if (spec.open()) user = engine.publicPrincipal();
		else throw new AuthException(NOT_ADMITTED);
		AString binding = Strings.create(spec.userDID(engine) + "/" + channel.method() + "/" + spec.name());
		return new AuthResult(user, channel.identity(), new AuthResult.Credential(channel.credential(), null, null),
			new AuthResult.Authentication(channel.method(), binding, null, Sets.empty(), channel.details()), null, null);
	}
}
