package covia.adapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.core.Constants;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.PartialMessageException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.Address;
import convex.net.Transports;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Adapter for interacting with the Convex network.
 *
 * Provides network queries and transactions plus local CAD3 encoding, decoding,
 * Ed25519 key generation and signing. Private seeds remain in the caller's
 * encrypted secret store.
 *
 * <p><b>Peers.</b> Queries and transactions go to the operator's peer
 * ({@code adapters.convex.peer}, default {@value #DEFAULT_PEER}). A call that
 * names another peer, in its input or its operation's metadata, asks the venue
 * to relay: refused unless the operator sets {@code adapters.convex.anyPeer},
 * and then the peer's host passes the venue's outbound address guard
 * ({@link Engine#requireSafeHost}).</p>
 */
public class ConvexAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(ConvexAdapter.class);

	// Operator configuration keys (adapters.convex.*)
	static final AString K_PEER = Fields.PEER;
	static final AString K_ANY_PEER = Strings.intern("anyPeer");
	private static final Set<AString> SETTINGS = Set.of(Config.ENABLED, K_PEER, K_ANY_PEER);

	/** The peer a venue talks to when its operator names none. */
	public static final String DEFAULT_PEER = "peer.convex.live:18888";

	/** Why a call naming another peer is refused while relaying is off. */
	public static final String OTHER_PEER =
		"is not this venue's Convex peer, and the operator has not enabled relaying to other peers (adapters.convex.anyPeer)";

	/** Why a peer value is refused before any policy applies. */
	public static final String NOT_A_PEER = "must be a Convex peer endpoint: host:port, tcp://host:port or tls://host:port";

	/** The operator's peer settings, replaced whole on every {@link #configure}. */
	private record PeerPolicy(URI peer, boolean anyPeer) {}

	private volatile PeerPolicy policy =
		new PeerPolicy(peerEndpoint(Strings.create(DEFAULT_PEER), "adapters.convex.peer"), false);

	/**
	 * Backstop deadline for a Convex peer call (connect + query/transact). The
	 * Convex client configures no request timeout, so without this a connected-
	 * but-non-responsive peer leaves the future — and the Job — pending forever.
	 */
	private static final long CONVEX_CALL_TIMEOUT_MS = 60_000L;

	private static final AString K_ACCOUNT_KEY = Strings.intern("accountKey");
	private static final AString K_ENCODING = Strings.intern("encoding");
	private static final AString K_SECRET = Strings.intern("secret");
	private static final AString K_SIGNATURE = Strings.intern("signature");

	private Hash QUERY_OPERATION;
	private Hash TRANSACT_OPERATION;
	private Hash GENERATE_KEY_OPERATION;
	private Hash SIGN_OPERATION;
	private Hash DECODE_CAD3_OPERATION;
	private Hash ENCODE_CAD3_OPERATION;

	@Override
	public String getName() {
		return "convex";
	}

	@Override
	public String getDescription() {
		return "Convex network access, CAD3 data conversion, and secret-backed Ed25519 signing";
	}

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) config = Maps.empty();
		if (strict) {
			for (long i = 0; i < config.count(); i++) {
				AString key = config.entryAt(i).getKey();
				if (!SETTINGS.contains(key)) {
					throw new IllegalArgumentException("adapters.convex: unknown setting '" + key + "'");
				}
			}
		}
		ACell rawPeer = config.get(K_PEER);
		URI peer = peerEndpoint((rawPeer != null) ? rawPeer : Strings.create(DEFAULT_PEER), "adapters.convex.peer");
		ACell rawAny = config.get(K_ANY_PEER);
		if (rawAny != null && !(rawAny instanceof CVMBool)) {
			throw new IllegalArgumentException("adapters.convex.anyPeer must be a boolean, got: " + rawAny);
		}
		policy = new PeerPolicy(peer, CVMBool.TRUE.equals(rawAny));
		return true;
	}

	@Override
	protected void installAssets() {
		// The adapter's own skill: v/skills/convex lives and dies with this adapter.
		installSkill("convex/convex", "/skills/convex.json");
		QUERY_OPERATION        = installAsset("convex/query",        "/adapters/convex/query.json");
		TRANSACT_OPERATION     = installAsset("convex/transact",     "/adapters/convex/transact.json");
		GENERATE_KEY_OPERATION = installAsset("convex/generate-key", "/adapters/convex/generateKey.json");
		SIGN_OPERATION         = installAsset("convex/sign",         "/adapters/convex/sign.json");
		DECODE_CAD3_OPERATION  = installAsset("convex/decode-cad3",  "/adapters/convex/decodeCad3.json");
		ENCODE_CAD3_OPERATION  = installAsset("convex/encode-cad3",  "/adapters/convex/encodeCad3.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String op = getSubOperation(meta);
		return switch (op) {
			case "query" -> invokeQuery(meta, input);
			case "transact" -> invokeTransact(ctx, meta, input);
			case "generate-key" -> CompletableFuture.supplyAsync(
				() -> generateKey(ctx, input), VIRTUAL_EXECUTOR);
			case "sign" -> CompletableFuture.supplyAsync(
				() -> sign(ctx, input), VIRTUAL_EXECUTOR);
			case "decode-cad3" -> CompletableFuture.supplyAsync(
				() -> decodeCAD3(input), VIRTUAL_EXECUTOR);
			case "encode-cad3" -> CompletableFuture.supplyAsync(
				() -> encodeCAD3(input), VIRTUAL_EXECUTOR);
			default -> CompletableFuture.failedFuture(
				new UnsupportedOperationException("Unsupported Convex operation: " + op));
		};
	}

	private CompletableFuture<ACell> invokeQuery(AMap<AString, ACell> meta, ACell input) {
		final Query request;
		try {
			request = query(input);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		return withConvexClient(meta, input, convex -> executeQuery(convex, request));
	}

	private CompletableFuture<ACell> invokeTransact(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		final Transaction request;
		try {
			request = transaction(ctx, input);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		return withConvexClient(meta, input, convex -> executeTransact(convex, request));
	}

	private record Query(Address address, ACell code) {}
	private record Transaction(Address address, AKeyPair keyPair, ACell code) {}

	private Query query(ACell input) {
		Address address = Address.parse(RT.getIn(input, Fields.ADDRESS));
		AString source = RT.ensureString(RT.getIn(input, Fields.SOURCE));
		if (source == null) {
			throw new JobFailedException("No query source provided");
		}
		ACell code = Reader.read(source.toString());
		if (code == null) throw new JobFailedException("Query source is empty");
		return new Query(address, code);
	}

	private Transaction transaction(RequestContext ctx, ACell input) {
		Address address = Address.parse(RT.getIn(input, Fields.ADDRESS));
		if (address == null) throw new JobFailedException("No address provided");
		AString source = RT.ensureString(RT.getIn(input, Fields.SOURCE));
		if (source == null) throw new JobFailedException("No transaction source provided");
		ACell code = Reader.read(source.toString());
		if (code == null) throw new JobFailedException("Transaction source is empty");
		return new Transaction(address, resolveKeyPair(ctx, input, false), code);
	}

	private CompletableFuture<ACell> executeQuery(Convex convex, Query request) {
		return convex.query(request.code(), request.address())
			.thenApply(ConvexAdapter::resultCell);
	}

	private CompletableFuture<ACell> executeTransact(Convex convex, Transaction request) {
		convex.setAddress(request.address());
		convex.setKeyPair(request.keyPair());
		return convex.transact(request.code()).thenApply(ConvexAdapter::resultCell);
	}

	private static ACell resultCell(Result result) {
		return RT.cvm(result.toJSON());
	}

	private ACell generateKey(RequestContext ctx, ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || name.toString().isBlank()) {
			throw new IllegalArgumentException("name is required");
		}
		ACell overwriteValue = RT.getIn(input, Fields.OVERWRITE);
		if (overwriteValue != null && !(overwriteValue instanceof CVMBool)) {
			throw new IllegalArgumentException("overwrite must be a boolean");
		}

		AKeyPair keyPair = AKeyPair.generate();
		SecretAdapter.storeSecret(engine, ctx, name,
			Strings.create(keyPair.getSeed().toHexString()),
			CVMBool.TRUE.equals(overwriteValue));
		return Maps.of(
			K_SECRET, Strings.create("s/" + name),
			K_ACCOUNT_KEY, Strings.create(keyPair.getAccountKey().toHexString()));
	}

	private ACell sign(RequestContext ctx, ACell input) {
		AString message = RT.ensureString(RT.getIn(input, Fields.MESSAGE));
		if (message == null) throw new IllegalArgumentException("message is required");
		AString encodingValue = RT.ensureString(RT.getIn(input, K_ENCODING));
		String encoding = (encodingValue != null) ? encodingValue.toString() : "utf8";
		Blob bytes = switch (encoding) {
			case "utf8" -> Blob.wrap(message.toString().getBytes(StandardCharsets.UTF_8));
			case "hex" -> parseBlob(message, "message");
			default -> throw new IllegalArgumentException("encoding must be 'utf8' or 'hex'");
		};
		AKeyPair keyPair = resolveKeyPair(ctx, input, true);
		ASignature signature = keyPair.sign(bytes);
		return Maps.of(
			K_ACCOUNT_KEY, Strings.create(keyPair.getAccountKey().toHexString()),
			K_SIGNATURE, Strings.create(signature.toHexString()));
	}

	private ACell decodeCAD3(ACell input) {
		ACell supplied = RT.getIn(input, K_ENCODING);
		if (supplied instanceof AString text
				&& text.count() > (CPoSConstants.MAX_MESSAGE_LENGTH * 2L + 2L)) {
			throw new IllegalArgumentException("CAD3 encoding exceeds the maximum message size of "
				+ CPoSConstants.MAX_MESSAGE_LENGTH + " bytes");
		}
		ABlob parsed = Blobs.parse(supplied);
		if (parsed == null) {
			throw new IllegalArgumentException("encoding must be a hexadecimal CAD3 blob");
		}
		if (parsed.count() > CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("CAD3 encoding exceeds the maximum message size of "
				+ CPoSConstants.MAX_MESSAGE_LENGTH + " bytes");
		}
		try {
			// Storeless multi-cell decoding is deliberate: every referenced child must
			// be carried by this encoding rather than resolved from ambient venue state.
			return CVMEncoder.INSTANCE.decodeMultiCell(parsed.toFlatBlob());
		} catch (PartialMessageException e) {
			throw new IllegalArgumentException(
				"Incomplete CAD3 encoding; missing referenced cell " + e.getMissingHash(), e);
		} catch (BadFormatException e) {
			throw new IllegalArgumentException("Invalid CAD3 encoding: " + e.getMessage(), e);
		}
	}

	private ACell encodeCAD3(ACell input) {
		if (!(input instanceof AMap<?, ?> map) || !map.containsKey(Fields.VALUE)) {
			throw new IllegalArgumentException("encode-cad3 requires a 'value' field");
		}
		ACell value = map.get(Fields.VALUE);
		Blob encoding = Format.encodeMultiCell(value, true);
		try {
			// Format's size limit may stop traversal before every branch is included.
			// Never return a purported complete encoding that cannot stand alone.
			ACell roundTrip = CVMEncoder.INSTANCE.decodeMultiCell(encoding);
			if (!Cells.equals(value, roundTrip)) {
				throw new IllegalArgumentException("CAD3 value did not round-trip exactly");
			}
		} catch (PartialMessageException e) {
			throw new IllegalArgumentException(
				"Value exceeds the maximum complete CAD3 message size", e);
		} catch (BadFormatException e) {
			throw new IllegalStateException("Generated invalid CAD3 encoding", e);
		}
		return Maps.of(K_ENCODING, Strings.create("0x" + encoding.toHexString()));
	}

	/** Resolves a transaction/signing seed. Transactions retain literal-seed
	 * compatibility, but agent-facing signing accepts only a secret reference so
	 * raw private material never needs to enter its job input. */
	private AKeyPair resolveKeyPair(RequestContext ctx, ACell input, boolean requireSecretRef) {
		AString supplied = RT.ensureString(RT.getIn(input, Fields.SEED));
		if (supplied == null) throw new JobFailedException("No Ed25519 signing seed provided");
		String value = supplied.toString();
		boolean secretRef = Engine.isSecretRef(value);
		if (requireSecretRef && !secretRef) {
			throw new IllegalArgumentException("seed must be an s/<name> secret reference");
		}
		if (secretRef) {
			String resolved = engine.resolveSecret(value, ctx);
			if (resolved == null) throw new JobFailedException(
				"Convex signing seed not found in the caller's secret store: " + value);
			value = resolved;
		}
		Blob seed = parseBlob(Strings.create(value), "seed");
		if (seed.count() != AKeyPair.SEED_LENGTH) {
			throw new IllegalArgumentException("Ed25519 seed must be exactly "
				+ AKeyPair.SEED_LENGTH + " bytes");
		}
		return AKeyPair.create(seed);
	}

	private static Blob parseBlob(AString value, String field) {
		ABlob parsed = Blobs.parse(value);
		if (parsed == null) throw new IllegalArgumentException(
			field + " must be a hexadecimal blob");
		return parsed.toFlatBlob();
	}

	private CompletableFuture<ACell> withConvexClient(AMap<AString, ACell> meta, ACell input,
			Function<Convex, CompletableFuture<ACell>> action) {

		return CompletableFuture.supplyAsync(() -> {
			// Outside the connect wrapper: a refusal is the venue's answer, not a connection failure
			URI endpoint = permittedPeer(meta, input);
			try {
				return Convex.connect(endpoint);
			} catch (Exception e) {
				throw new CompletionException(
						new JobFailedException("Failed to connect to Convex peer: " + e.getMessage()));
			}
		}, VIRTUAL_EXECUTOR).thenCompose(convex -> {
			CompletableFuture<ACell> resultFuture;
			try {
				resultFuture = action.apply(convex);
			} catch (Exception e) {
				resultFuture = CompletableFuture.failedFuture(e);
			}

			return resultFuture.whenComplete((result, error) -> closeQuietly(convex));
		})
		// Backstop so a non-responsive Convex peer cannot leave the Job pending
		// forever (the Convex client sets no request timeout of its own).
		.orTimeout(CONVEX_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	/**
	 * The peer this call may reach: the venue's own, unless the caller names
	 * another in its input or in its operation's metadata (operations are
	 * user-creatable assets, so both are the caller's choice). Another peer is a
	 * relay, refused unless the operator sets {@code anyPeer}; then its host
	 * passes the venue's outbound address guard like any caller-chosen target.
	 */
	private URI permittedPeer(AMap<AString, ACell> meta, ACell input) {
		PeerPolicy p = policy;
		ACell named = RT.getIn(input, Fields.PEER);
		if (named == null && meta != null) named = RT.getIn(meta, Fields.OPERATION, Fields.PEER);
		if (named == null) return p.peer();
		URI target = peerEndpoint(named, "peer");
		if (target.equals(p.peer())) return target;
		if (!p.anyPeer()) throw new IllegalArgumentException("Peer " + target + " " + OTHER_PEER);
		engine.requireSafeHost(target.getHost());
		return target;
	}

	/**
	 * A peer endpoint in one form, so two spellings of one peer compare equal:
	 * scheme {@code tcp} or {@code tls} and an explicit port ({@link URI#equals}
	 * ignores the case of scheme and host). Parsed by Convex's own
	 * {@link Transports#endpoint}, without DNS.
	 */
	static URI peerEndpoint(ACell value, String field) {
		AString text = RT.ensureString(value);
		if (text == null) throw notAPeer(field, value);
		URI uri;
		try {
			uri = Transports.endpoint(text.toString());
		} catch (IllegalArgumentException e) {
			throw notAPeer(field, value);
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("tcp") && !scheme.equals("tls")) throw notAPeer(field, value);
		if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty()) || uri.getRawQuery() != null) {
			throw notAPeer(field, value);
		}
		int port = (uri.getPort() < 0) ? Constants.DEFAULT_PEER_PORT : uri.getPort();
		try {
			return new URI(scheme, null, uri.getHost(), port, null, null, null);
		} catch (URISyntaxException e) {
			throw notAPeer(field, value);
		}
	}

	private static IllegalArgumentException notAPeer(String field, ACell value) {
		return new IllegalArgumentException(field + " " + NOT_A_PEER + ", got: " + value);
	}

	private void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) return;
		try {
			closeable.close();
		} catch (Exception e) {
			log.warn("Failed to close Convex client: {}", e.getMessage());
		}
	}

	public Hash getQueryOperation() {
		return QUERY_OPERATION;
	}

	public Hash getTransactOperation() {
		return TRANSACT_OPERATION;
	}

	public Hash getGenerateKeyOperation() {
		return GENERATE_KEY_OPERATION;
	}

	public Hash getSignOperation() {
		return SIGN_OPERATION;
	}

	public Hash getDecodeCAD3Operation() {
		return DECODE_CAD3_OPERATION;
	}

	public Hash getEncodeCAD3Operation() {
		return ENCODE_CAD3_OPERATION;
	}
}
