package covia.adapter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import convex.api.Convex;
import convex.auth.ucan.UCAN;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.store.MemoryStore;
import convex.node.LatticeConnectionManager;
import convex.node.LatticePropagator;
import convex.node.NodeConfig;
import convex.p2p.P2PLattice;
import convex.p2p.P2PNode;
import convex.p2p.P2PUser;
import covia.api.Abilities;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.RequestContext;

/**
 * The venue's node on the Convex P2P network (covia#587): a thin wrapper over
 * {@link P2PNode} that lists the nodes it knows and connects it to a peer.
 *
 * <p>Off by default: the node runs only while {@code adapters.p2p.join} is
 * true. It uses the venue's key, so its identity on the network is the
 * venue's key-derived {@code did:key}, and it publishes the venue's name and
 * any declared DID in its signed {@code :id} record. It has its own memory
 * store, because a node keeps its root in its store's root slot and the
 * venue's own node server already holds the venue store's. It serves only
 * the infrastructure regions ({@code :p2p}, {@code :id}), so no venue state
 * is replicated.</p>
 *
 * <ul>
 *   <li>{@code p2p/peers} — the venue's own entry and every node it knows,
 *       read-only; requires {@code p2p/read} on {@code <venue DID>/p2p}.</li>
 *   <li>{@code p2p/connect} — connect to a node at an address; requires
 *       {@code p2p/manage} on {@code <venue DID>/p2p}. Convex keeps redialling
 *       it until the node stops; durable bootstrap peers belong in
 *       {@code adapters.p2p.peers}.</li>
 * </ul>
 *
 * <p>Two kinds of state are kept apart. What nodes publish, their signed
 * records, arrives by lattice replication, and the adapter tells
 * {@link #subscribe subscribers} as soon as the lattice changes. Whether a
 * peer is connected is this node's own transient state: it is never
 * published, and since Convex announces connection changes no other way,
 * the adapter also rechecks it every {@code pollMs}. The same pass takes the
 * node offline once the adapter is disabled.</p>
 *
 * <p>This is network infrastructure only. Social follows are a social graph
 * between DIDs, application data for the social layer; they do not belong
 * here, which is why the node serves no {@code :social} region.</p>
 */
public class P2PAdapter extends AAdapter implements AutoCloseable {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(P2PAdapter.class);

	static final String RESOURCE = "p2p";
	static final long DEFAULT_POLL_MS = 1000;
	static final long MIN_POLL_MS = 10;

	/** Event types: a node is newly known, gained or lost its route, changed otherwise, or is no longer listed. */
	public static final String APPEARED = "appeared";
	public static final String CONNECTED = "connected";
	public static final String DISCONNECTED = "disconnected";
	public static final String UPDATED = "updated";
	public static final String REMOVED = "removed";

	public static final String NOT_JOINED =
		"This venue has not joined the P2P network (adapters.p2p.join is not true)";
	public static final String SELF_CONNECT = "That is this venue's own node";

	static final AString K_JOIN = Strings.intern("join");
	static final AString K_PEERS = Strings.intern("peers");
	static final AString K_POLL_MS = Strings.intern("pollMs");
	static final AString K_ADDRESS = Strings.intern("address");
	static final AString K_JOINED = Strings.intern("joined");
	static final AString K_SELF = Strings.intern("self");
	static final AString K_CONNECTED = Strings.intern("connected");
	static final AString K_REACH = Strings.intern("reach");
	static final AString K_TRANSPORTS = Strings.intern("transports");
	static final AString K_RELAY = Strings.intern("relay");
	static final AString K_UPDATED = Strings.intern("updated");
	static final AString K_ALSO_KNOWN_AS = Strings.intern("alsoKnownAs");

	/** How a peer is reached: a route this node dialled, one the peer dialled, relays it lists, or nothing yet. */
	public static final AString OUTBOUND = Strings.intern("outbound");
	public static final AString INBOUND = Strings.intern("inbound");
	public static final AString VIA_RELAY = Strings.intern("relay");
	public static final AString NONE = Strings.intern("none");

	private static final Keyword ID_DID = Keyword.intern("did");

	private static final Set<AString> SETTINGS = Set.of(Config.ENABLED, K_JOIN, K_PEERS, K_POLL_MS,
		NodeConfig.PORT, NodeConfig.URL, NodeConfig.ALLOW_PRIVATE_URL);

	/** A change in what the venue's node knows; {@code peer} is the entry as {@code p2p/peers} lists it. */
	public record PeerEvent(String type, AMap<AString, ACell> peer) {
		public AString did() {
			return RT.ensureString(peer.get(Fields.DID));
		}
	}

	/** An event subscription; closing it stops delivery. */
	public interface Subscription extends AutoCloseable {
		@Override
		void close();
	}

	/** A node to connect to. */
	record Peer(AccountKey key, String host, int port) {}

	/** Effective settings: whether to join, the node's Convex configuration, bootstrap peers, poll interval. */
	record Settings(boolean join, boolean listens, NodeConfig node, List<Peer> peers, long pollMs) {}

	/** One launched node and the peer list last reported for it. */
	private static final class Running {
		final P2PNode node;
		final long pollMs;
		volatile boolean closed;
		Thread watcher;
		Map<String, AMap<AString, ACell>> reported = Map.of();

		Running(P2PNode node, long pollMs) {
			this.node = node;
			this.pollMs = pollMs;
		}
	}

	private volatile Settings settings = parse(null, false);
	private volatile Running running;
	private final List<Consumer<PeerEvent>> listeners = new CopyOnWriteArrayList<>();

	@Override
	public String getName() {
		return "p2p";
	}

	@Override
	public String getDescription() {
		return "The venue's node on the Convex P2P network: list the nodes it knows, with their DIDs, "
			+ "names and whether they are connected, and connect it to a peer. Off unless the operator "
			+ "joins the network. Venue-owned.";
	}

	@Override
	protected void installAssets() {
		installAsset("p2p/peers", "/adapters/p2p/peers.json");
		installAsset("p2p/connect", "/adapters/p2p/connect.json");
	}

	@Override
	public synchronized boolean configure(AMap<AString, ACell> config, boolean strict) {
		Settings next = parse(config, strict);
		if (running != null) halt();
		if (next.join() && isActive() && engine.isStarted()) {
			try {
				launch(next);
			} catch (IllegalStateException e) {
				throw new IllegalArgumentException(e.getMessage(), e);
			}
		}
		settings = next;
		return true;
	}

	@Override
	public synchronized void start() {
		Settings s = settings;
		if (running == null && s.join()) launch(s);
	}

	@Override
	public synchronized void close() {
		halt();
	}

	/**
	 * Delivers peer changes to {@code listener} on the adapter's own thread
	 * until the subscription is closed. Listeners must return promptly.
	 *
	 * @param listener receives each change
	 * @return the subscription
	 */
	public Subscription subscribe(Consumer<PeerEvent> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		try {
			requireInvoke(ctx);
			String sub = getSubOperation(meta);
			switch (sub) {
				case "peers":
					engine.requireVenueAuthority(ctx, RESOURCE, Abilities.P2P_READ);
					return CompletableFuture.completedFuture(peers());
				case "connect":
					engine.requireVenueAuthority(ctx, RESOURCE, Abilities.P2P_MANAGE);
					return connect(input);
				default:
					throw new IllegalArgumentException("Unknown p2p operation: " + sub);
			}
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ========== Operations ==========

	private ACell peers() {
		Running r = running;
		if (r == null) return Maps.of(K_JOINED, CVMBool.FALSE, K_PEERS, Vectors.empty());
		return Maps.of(K_JOINED, CVMBool.TRUE,
			K_SELF, describe(r.node, engine.getAccountKey()),
			K_PEERS, Vectors.create(list(r.node).values()));
	}

	private CompletableFuture<ACell> connect(ACell input) {
		Running r = running;
		if (r == null) return CompletableFuture.failedFuture(new IllegalStateException(NOT_JOINED));
		Peer peer = peer(input, "input");
		if (peer.key().equals(engine.getAccountKey())) {
			return CompletableFuture.failedFuture(new IllegalArgumentException(SELF_CONNECT));
		}
		return dial(r.node, peer).thenApply(c -> Maps.of(
			Fields.DID, UCAN.toDIDKey(peer.key()), K_CONNECTED, CVMBool.TRUE));
	}

	private static CompletableFuture<Convex> dial(P2PNode node, Peer peer) {
		return CompletableFuture.supplyAsync(() -> new InetSocketAddress(peer.host(), peer.port()), VIRTUAL_EXECUTOR)
			.thenCompose(address -> node.connect(peer.key(), address));
	}

	// ========== Node lifecycle ==========

	private boolean isActive() {
		return engine != null && engine.getAdapter(getName()) == this;
	}

	/** Test hook: the running node, or null when the venue has not joined. */
	P2PNode node() {
		Running r = running;
		return (r == null) ? null : r.node;
	}

	/** Launches a node for {@code s}; caller holds this adapter's lock. */
	private void launch(Settings s) {
		P2PNode node = P2PNode.create(new MemoryStore(), s.node(), engine.getKeyPair(), P2PLattice.ROOT);
		if (s.listens()) node.serveAllInbound();
		try {
			node.launch();
			publishIdentity(node);
		} catch (IOException e) {
			closeQuietly(node);
			throw new IllegalStateException("P2P node failed to launch: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			closeQuietly(node);
			throw new IllegalStateException("Interrupted launching the P2P node", e);
		} catch (RuntimeException e) {
			closeQuietly(node);
			throw e;
		}
		Running r = new Running(node, s.pollMs());
		running = r;
		for (Peer peer : s.peers()) {
			dial(node, peer).exceptionally(e -> {
				log.warn("P2P bootstrap peer {} at {}:{} not reached yet: {}",
					UCAN.toDIDKey(peer.key()), peer.host(), peer.port(), e.getMessage());
				return null;
			});
		}
		r.watcher = Thread.ofVirtual().name("p2p-watcher").start(() -> watch(r));
	}

	/** Publishes the venue's name and declared DID under its own key, so peers can show them. */
	private void publishIdentity(P2PNode node) {
		AccountKey self = engine.getAccountKey();
		AString declared = engine.getDIDString();
		AHashMap<Keyword, ACell> metadata = declared.equals(UCAN.toDIDKey(self)) ? null : Maps.of(ID_DID, declared);
		long timestamp = convex.core.util.Utils.getCurrentTimestamp();
		P2PUser user = node.p2p();
		user.setIdentity(P2PLattice.createIdentity(engine.config().getName(), Vectors.of(self), metadata, timestamp));
		user.sync();
	}

	/** Stops the running node, if any; caller holds this adapter's lock. */
	private void halt() {
		Running r = running;
		running = null;
		if (r != null) shut(r);
	}

	private void shut(Running r) {
		r.closed = true;
		if (r.watcher != null && r.watcher != Thread.currentThread()) r.watcher.interrupt();
		closeQuietly(r.node);
		report(r, Map.of(), true);
	}

	/**
	 * Reports after every lattice change, so published records show as soon
	 * as they replicate, and at least every {@code pollMs} for the local
	 * connection state, which Convex announces no other way.
	 */
	private void watch(Running r) {
		LatticePropagator propagator = r.node.getNodeServer().getPropagators().get(0);
		while (!r.closed) {
			// Taken before the read, so a change during it ends the next wait
			CompletableFuture<ACell> changed = propagator.nextAnnounce();
			if (!isActive()) {
				synchronized (this) {
					if (running != r) return;
					running = null;
				}
				shut(r);
				return;
			}
			try {
				report(r, list(r.node), false);
			} catch (RuntimeException e) {
				log.debug("P2P peer refresh failed", e);
			}
			try {
				changed.get(r.pollMs, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				// No lattice change: recheck the connections
			} catch (InterruptedException e) {
				return;
			} catch (ExecutionException | CancellationException e) {
				// A failed announce must not turn the wait into a spin
				try {
					Thread.sleep(r.pollMs);
				} catch (InterruptedException stop) {
					return;
				}
			}
		}
	}

	/** Records {@code next} as the reported list and tells listeners what changed, outside any lock. */
	private void report(Running r, Map<String, AMap<AString, ACell>> next, boolean last) {
		List<PeerEvent> events = new ArrayList<>();
		synchronized (r) {
			if (r.closed && !last) return;
			Map<String, AMap<AString, ACell>> previous = r.reported;
			for (Map.Entry<String, AMap<AString, ACell>> e : next.entrySet()) {
				AMap<AString, ACell> before = previous.get(e.getKey());
				AMap<AString, ACell> after = e.getValue();
				if (before == null) {
					events.add(new PeerEvent(APPEARED, after));
				} else if (!before.equals(after)) {
					boolean was = CVMBool.TRUE.equals(before.get(K_CONNECTED));
					boolean is = CVMBool.TRUE.equals(after.get(K_CONNECTED));
					events.add(new PeerEvent((was == is) ? UPDATED : (is ? CONNECTED : DISCONNECTED), after));
				}
			}
			for (Map.Entry<String, AMap<AString, ACell>> e : previous.entrySet()) {
				if (!next.containsKey(e.getKey())) events.add(new PeerEvent(REMOVED, e.getValue()));
			}
			r.reported = next;
		}
		for (PeerEvent event : events) {
			for (Consumer<PeerEvent> listener : listeners) {
				try {
					listener.accept(event);
				} catch (RuntimeException e) {
					log.warn("P2P peer listener failed", e);
				}
			}
		}
	}

	private static void closeQuietly(P2PNode node) {
		try {
			node.close();
		} catch (IOException | RuntimeException e) {
			log.debug("Closing P2P node failed", e);
		}
	}

	// ========== Peer list ==========

	/** Every node known to {@code node} except the venue itself, by DID. */
	private Map<String, AMap<AString, ACell>> list(P2PNode node) {
		AccountKey self = engine.getAccountKey();
		LatticeConnectionManager links = node.getNodeServer().getPropagators().get(0).getConnectionManager();
		Set<AccountKey> routes = links.getAuthenticatedRouteKeys();
		Set<AccountKey> keys = new HashSet<>(routes);
		AHashMap<ACell, SignedData<ACell>> nodes = owners(node, P2PLattice.KEY_P2P, P2PLattice.KEY_NODES);
		if (nodes != null) {
			// Keys decoded from the network arrive as plain blobs
			for (long i = 0; i < nodes.count(); i++) {
				AccountKey key = RT.ensureAccountKey(nodes.entryAt(i).getKey());
				if (key != null) keys.add(key);
			}
		}
		keys.remove(self);
		Map<String, AMap<AString, ACell>> out = new TreeMap<>();
		for (AccountKey key : keys) {
			AMap<AString, ACell> entry = describe(node, key);
			AString reach;
			if (routes.contains(key)) {
				Convex outbound = links.getConnection(key);
				reach = (outbound != null && outbound.isConnected()) ? OUTBOUND : INBOUND;
			} else {
				AVector<?> pops = RT.ensureVector(RT.getIn(signedValue(nodes, key), Keywords.POPS));
				reach = (pops != null && pops.count() > 0) ? VIA_RELAY : NONE;
			}
			entry = entry.assoc(K_CONNECTED, CVMBool.of(routes.contains(key))).assoc(K_REACH, reach);
			out.put(entry.get(Fields.DID).toString(), entry);
		}
		return out;
	}

	/** One node's entry from its signed NodeInfo and identity records; both come from the network, so types are checked. */
	private static AMap<AString, ACell> describe(P2PNode node, AccountKey key) {
		AString did = UCAN.toDIDKey(key);
		AMap<AString, ACell> entry = Maps.of(Fields.DID, did);
		ACell info = signedValue(owners(node, P2PLattice.KEY_P2P, P2PLattice.KEY_NODES), key);
		if (info != null) {
			AVector<?> transports = RT.ensureVector(RT.getIn(info, Keywords.TRANSPORTS));
			if (transports != null) {
				AVector<AString> urls = Vectors.empty();
				for (long i = 0; i < transports.count(); i++) {
					AString url = RT.ensureString(transports.get(i));
					if (url != null) urls = urls.conj(url);
				}
				entry = entry.assoc(K_TRANSPORTS, urls);
			}
			entry = entry.assoc(K_RELAY, CVMBool.of(CVMBool.TRUE.equals(RT.getIn(info, Keywords.RELAY))));
			AString version = RT.ensureString(RT.getIn(info, Keywords.VERSION));
			if (version != null) entry = entry.assoc(Fields.VERSION, version);
			CVMLong updated = RT.ensureLong(RT.getIn(info, Keywords.TIMESTAMP));
			if (updated != null) entry = entry.assoc(K_UPDATED, updated);
		}
		ACell identity = signedValue(owners(node, P2PLattice.KEY_ID), key);
		if (identity != null) {
			AString name = RT.ensureString(RT.getIn(identity, P2PLattice.ID_NAME));
			if (name != null) entry = entry.assoc(Fields.NAME, name);
			AString declared = RT.ensureString(RT.getIn(identity, P2PLattice.ID_METADATA, ID_DID));
			if (declared != null && !declared.equals(did)) entry = entry.assoc(K_ALSO_KNOWN_AS, Vectors.of(declared));
		}
		return entry;
	}

	@SuppressWarnings("unchecked")
	private static AHashMap<ACell, SignedData<ACell>> owners(P2PNode node, Keyword... path) {
		ACell value = node.getCursor().get(path);
		return (value instanceof AHashMap<?, ?> map) ? (AHashMap<ACell, SignedData<ACell>>) map : null;
	}

	private static ACell signedValue(AHashMap<ACell, SignedData<ACell>> owners, AccountKey key) {
		if (owners == null) return null;
		ACell slot = owners.get(key);
		return (slot instanceof SignedData<?> signed) ? signed.getValue() : null;
	}

	// ========== Configuration ==========

	/**
	 * Validates the whole configuration. {@code port}, {@code url} and
	 * {@code allowPrivateURL} pass to Convex's {@link NodeConfig} as they are;
	 * without {@code port} the node opens no listener and only dials out.
	 */
	static Settings parse(AMap<AString, ACell> config, boolean strict) {
		if (config == null) config = Maps.empty();
		if (strict) {
			for (long i = 0; i < config.count(); i++) {
				AString key = config.entryAt(i).getKey();
				if (!SETTINGS.contains(key)) {
					throw new IllegalArgumentException("adapters.p2p: unknown setting '" + key + "'");
				}
			}
		}
		boolean join = bool(config, K_JOIN);
		boolean allowPrivate = bool(config, NodeConfig.ALLOW_PRIVATE_URL);
		AMap<AString, ACell> node = Maps.of(NodeConfig.PORT, CVMLong.create(-1),
			NodeConfig.PERSIST, CVMBool.FALSE, NodeConfig.RESTORE, CVMBool.FALSE,
			NodeConfig.ALLOW_PRIVATE_URL, CVMBool.of(allowPrivate));
		ACell rawPort = config.get(NodeConfig.PORT);
		boolean listens = rawPort != null;
		if (listens) {
			CVMLong port = RT.ensureLong(rawPort);
			if (port == null || port.longValue() < 0 || port.longValue() > 65535) {
				throw new IllegalArgumentException("adapters.p2p.port must be 0 (any free port) to 65535, got: " + rawPort);
			}
			node = node.assoc(NodeConfig.PORT, port);
		}
		ACell rawUrl = config.get(NodeConfig.URL);
		if (rawUrl != null) {
			AString url = RT.ensureString(rawUrl);
			if (url == null) throw new IllegalArgumentException("adapters.p2p.url must be a string");
			if (!listens) throw new IllegalArgumentException("adapters.p2p.url advertises a listener; set adapters.p2p.port too");
			String reason = NodeConfig.validatePublicURL(url.toString(), allowPrivate);
			if (reason != null) throw new IllegalArgumentException("adapters.p2p.url: " + reason);
			node = node.assoc(NodeConfig.URL, url);
		}
		long pollMs = DEFAULT_POLL_MS;
		ACell rawPoll = config.get(K_POLL_MS);
		if (rawPoll != null) {
			CVMLong poll = RT.ensureLong(rawPoll);
			if (poll == null || poll.longValue() < MIN_POLL_MS) {
				throw new IllegalArgumentException("adapters.p2p.pollMs must be at least " + MIN_POLL_MS + ", got: " + rawPoll);
			}
			pollMs = poll.longValue();
		}
		List<Peer> peers = new ArrayList<>();
		ACell rawPeers = config.get(K_PEERS);
		if (rawPeers != null) {
			AVector<ACell> entries = RT.ensureVector(rawPeers);
			if (entries == null) throw new IllegalArgumentException("adapters.p2p.peers must be an array of {did, address}");
			for (long i = 0; i < entries.count(); i++) peers.add(peer(entries.get(i), "adapters.p2p.peers[" + i + "]"));
		}
		return new Settings(join, listens, NodeConfig.create(node), List.copyOf(peers), pollMs);
	}

	private static boolean bool(AMap<AString, ACell> config, AString key) {
		ACell value = config.get(key);
		if (value == null) return false;
		if (!(value instanceof CVMBool b)) throw new IllegalArgumentException("adapters.p2p." + key + " must be true or false");
		return b.booleanValue();
	}

	/** Parses {@code {did, address}}: a {@code did:key} and {@code host:port} or {@code tcp://host:port}. */
	static Peer peer(ACell raw, String where) {
		AString did = RT.ensureString(RT.getIn(raw, Fields.DID));
		AccountKey key = (did == null) ? null : UCAN.fromDIDKey(did);
		if (key == null) throw new IllegalArgumentException(where + ".did must be a did:key, got: " + did);
		AString address = RT.ensureString(RT.getIn(raw, K_ADDRESS));
		if (address == null) throw new IllegalArgumentException(where + ".address must be host:port");
		String text = address.toString().trim();
		try {
			URI uri = new URI(text.contains("://") ? text : "tcp://" + text);
			if (!"tcp".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() <= 0) {
				throw new IllegalArgumentException(where + ".address must be host:port or tcp://host:port, got: " + text);
			}
			return new Peer(key, uri.getHost(), uri.getPort());
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException(where + ".address must be host:port, got: " + text, e);
		}
	}
}
