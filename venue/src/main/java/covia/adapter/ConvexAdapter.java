package covia.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
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
import convex.core.exceptions.ParseException;
import convex.core.exceptions.PartialMessageException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.cvm.Address;
import convex.net.Transports;
import covia.api.Fields;
import covia.exception.ConfigurationException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
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

	/** Why a call fails when no connection to its peer opens; the peer follows. */
	public static final String PEER_UNREACHABLE = "Could not connect to the Convex peer ";

	/** Why a call ends when its thread is interrupted while connecting; the peer follows. */
	public static final String CONNECT_INTERRUPTED = "Interrupted connecting to the Convex peer ";

	/** Why a setting adapters.convex does not have is refused; the setting follows. */
	public static final String UNKNOWN_SETTING = "adapters.convex: unknown setting ";

	/** Why an anyPeer setting that is not a boolean is refused; the value follows. */
	public static final String ANY_PEER_NOT_BOOLEAN = "adapters.convex.anyPeer must be a boolean, got: ";

	/** Why an operation this adapter does not have is refused; its name follows. */
	public static final String UNSUPPORTED_OPERATION = "Unsupported Convex operation: ";

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

	/** Why a call fails when its peer has not answered within the backstop deadline. */
	public static final String PEER_SILENT = "The Convex peer did not answer within " + CONVEX_CALL_TIMEOUT_MS + "ms";

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
					throw new ConfigurationException(UNKNOWN_SETTING + "'" + key + "'");
				}
			}
		}
		ACell rawPeer = config.get(K_PEER);
		URI peer;
		try {
			peer = peerEndpoint((rawPeer != null) ? rawPeer : Strings.create(DEFAULT_PEER), "adapters.convex.peer");
		} catch (InvalidInputException e) {
			throw new ConfigurationException(e.getMessage(), e);
		}
		ACell rawAny = config.get(K_ANY_PEER);
		if (rawAny != null && !(rawAny instanceof CVMBool)) {
			throw new ConfigurationException(ANY_PEER_NOT_BOOLEAN + rawAny);
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
		String op = getSubOperation(meta);
		return switch (op) {
			case "query" -> invokeQuery(ctx, meta, input);
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
				new NotSupportedException(UNSUPPORTED_OPERATION + op));
		};
	}

	private CompletableFuture<ACell> invokeQuery(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		final Query request;
		try {
			request = query(input);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		return withConvexClient(ctx, meta, input, convex -> executeQuery(convex, request));
	}

	private CompletableFuture<ACell> invokeTransact(RequestContext ctx,
			AMap<AString, ACell> meta, ACell input) {
		final Transaction request;
		try {
			request = transaction(ctx, input);
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
		return withConvexClient(ctx, meta, input, convex -> executeTransact(convex, request));
	}

	private record Query(Address address, ACell code) {}
	private record Transaction(Address address, AKeyPair keyPair, ACell code) {}

	/** Why a query or transaction without its source is refused. */
	public static final String NO_SOURCE = "source must be a string of Convex Lisp";

	/** Why a source that does not read as one Convex Lisp form is refused; the reader's reason follows. */
	public static final String UNREADABLE_SOURCE = "source must be one Convex Lisp form: ";

	/** Why an address that names no account, or a transaction without one, is refused. */
	public static final String NOT_AN_ADDRESS = "address must be a Convex account address, such as #13";

	/** A query without an address is sent without one: Convex runs it as the genesis account, and a query changes nothing either way. */
	private Query query(ACell input) {
		return new Query(address(input), readSource(input));
	}

	private Transaction transaction(RequestContext ctx, ACell input) {
		Address address = address(input);
		if (address == null) throw new InvalidInputException(NOT_AN_ADDRESS);
		ACell code = readSource(input);
		return new Transaction(address, transactionKeyPair(ctx, input), code);
	}

	/** The account a call's {@code address} names, or null when it gives none. */
	private static Address address(ACell input) {
		ACell named = RT.getIn(input, Fields.ADDRESS);
		if (named == null) return null;
		Address address = Address.parse(named);
		if (address == null) throw new InvalidInputException(NOT_AN_ADDRESS + ", got: " + named);
		return address;
	}

	/**
	 * The form a call's {@code source} reads as. A source of {@code nil} reads
	 * as null, the nil form, and is sent like any other; Convex's reader itself
	 * refuses empty, blank and malformed text.
	 */
	private static ACell readSource(ACell input) {
		AString source = RT.ensureString(RT.getIn(input, Fields.SOURCE));
		if (source == null) throw new InvalidInputException(NO_SOURCE);
		try {
			return Reader.read(source);
		} catch (ParseException e) {
			throw new InvalidInputException(UNREADABLE_SOURCE + e.getMessage(), e);
		}
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

	public static final String NAME_REQUIRED = "name is required";
	public static final String OVERWRITE_NOT_BOOLEAN = "overwrite must be a boolean";
	public static final String MESSAGE_REQUIRED = "message is required";
	public static final String SIGN_ENCODING = "encoding must be 'utf8' or 'hex'";

	private ACell generateKey(RequestContext ctx, ACell input) {
		AString name = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (name == null || name.toString().isBlank()) {
			throw new InvalidInputException(NAME_REQUIRED);
		}
		ACell overwriteValue = RT.getIn(input, Fields.OVERWRITE);
		if (overwriteValue != null && !(overwriteValue instanceof CVMBool)) {
			throw new InvalidInputException(OVERWRITE_NOT_BOOLEAN);
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
		if (message == null) throw new InvalidInputException(MESSAGE_REQUIRED);
		AString encodingValue = RT.ensureString(RT.getIn(input, K_ENCODING));
		String encoding = (encodingValue != null) ? encodingValue.toString() : "utf8";
		Blob bytes = switch (encoding) {
			case "utf8" -> Blob.wrap(message.toString().getBytes(StandardCharsets.UTF_8));
			case "hex" -> parseBlob(message, "message");
			default -> throw new InvalidInputException(SIGN_ENCODING);
		};
		AKeyPair keyPair = resolveKeyPair(ctx, input, true);
		ASignature signature = keyPair.sign(bytes);
		return Maps.of(
			K_ACCOUNT_KEY, Strings.create(keyPair.getAccountKey().toHexString()),
			K_SIGNATURE, Strings.create(signature.toHexString()));
	}

	public static final String CAD3_TOO_LARGE = "CAD3 encoding exceeds the maximum message size of "
		+ CPoSConstants.MAX_MESSAGE_LENGTH + " bytes";
	public static final String NOT_CAD3 = "encoding must be a hexadecimal CAD3 blob";
	/** Why an encoding that refers to a cell it does not carry is refused; the cell's hash follows. */
	public static final String CAD3_INCOMPLETE = "Incomplete CAD3 encoding; missing referenced cell ";
	/** Why an encoding Convex cannot decode is refused; Convex's reason follows. */
	public static final String CAD3_INVALID = "Invalid CAD3 encoding: ";
	public static final String NO_VALUE = "encode-cad3 requires a 'value' field";
	public static final String NO_ROUND_TRIP = "CAD3 value did not round-trip exactly";
	public static final String VALUE_TOO_LARGE = "Value exceeds the maximum complete CAD3 message size";

	private ACell decodeCAD3(ACell input) {
		ACell supplied = RT.getIn(input, K_ENCODING);
		if (supplied instanceof AString text
				&& text.count() > (CPoSConstants.MAX_MESSAGE_LENGTH * 2L + 2L)) {
			throw new InvalidInputException(CAD3_TOO_LARGE);
		}
		ABlob parsed = Blobs.parse(supplied);
		if (parsed == null) {
			throw new InvalidInputException(NOT_CAD3);
		}
		if (parsed.count() > CPoSConstants.MAX_MESSAGE_LENGTH) {
			throw new InvalidInputException(CAD3_TOO_LARGE);
		}
		try {
			// Storeless multi-cell decoding is deliberate: every referenced child must
			// be carried by this encoding rather than resolved from ambient venue state.
			return CVMEncoder.INSTANCE.decodeMultiCell(parsed.toFlatBlob());
		} catch (PartialMessageException e) {
			throw new InvalidInputException(CAD3_INCOMPLETE + e.getMissingHash(), e);
		} catch (BadFormatException e) {
			throw new InvalidInputException(CAD3_INVALID + e.getMessage(), e);
		}
	}

	private ACell encodeCAD3(ACell input) {
		if (!(input instanceof AMap<?, ?> map) || !map.containsKey(Fields.VALUE)) {
			throw new InvalidInputException(NO_VALUE);
		}
		ACell value = map.get(Fields.VALUE);
		Blob encoding = Format.encodeMultiCell(value, true);
		try {
			// Format's size limit may stop traversal before every branch is included.
			// Never return a purported complete encoding that cannot stand alone.
			ACell roundTrip = CVMEncoder.INSTANCE.decodeMultiCell(encoding);
			if (!Cells.equals(value, roundTrip)) {
				throw new InvalidInputException(NO_ROUND_TRIP);
			}
		} catch (PartialMessageException e) {
			throw new InvalidInputException(VALUE_TOO_LARGE, e);
		} catch (BadFormatException e) {
			throw new IllegalStateException("Generated invalid CAD3 encoding", e);
		}
		return Maps.of(K_ENCODING, Strings.create("0x" + encoding.toHexString()));
	}

	public static final String KEY_OR_SEED = "Give either seed or key, not both";

	/** Why a transaction with neither a seed nor a key is refused. */
	public static final String NO_SIGNER = "Give seed or key to sign the transaction";

	/** Why a signing call without a seed is refused. */
	public static final String NO_SEED = "seed is required: an s/<name> secret reference holding an Ed25519 seed";

	/** Why a seed reference that resolves to no secret is refused; the reference follows. */
	public static final String SEED_UNAVAILABLE = "No secret is available for the seed reference ";

	public static final String SEED_REF_REQUIRED = "seed must be an s/<name> secret reference";
	public static final String WRONG_SEED_LENGTH = "Ed25519 seed must be exactly " + AKeyPair.SEED_LENGTH + " bytes";

	/** Why a value that is not hexadecimal is refused; follows the field's name. */
	public static final String NOT_HEX = "must be a hexadecimal blob";

	/** A transaction's signer: a held {@code key}, used under {@code key/sign/convex}, or its {@code seed}. */
	private AKeyPair transactionKeyPair(RequestContext ctx, ACell input) {
		ACell key = RT.getIn(input, Fields.KEY);
		ACell seed = RT.getIn(input, Fields.SEED);
		if (key == null && seed == null) throw new InvalidInputException(NO_SIGNER);
		if (key == null) return resolveKeyPair(ctx, input, false);
		if (seed != null) throw new InvalidInputException(KEY_OR_SEED);
		return CryptoAdapter.of(engine).heldKeyPair(ctx, key, covia.api.Abilities.KEY_SIGN_CONVEX);
	}

	/** Resolves a transaction/signing seed. Transactions retain literal-seed
	 * compatibility, but agent-facing signing accepts only a secret reference so
	 * raw private material never needs to enter its job input. */
	private AKeyPair resolveKeyPair(RequestContext ctx, ACell input, boolean requireSecretRef) {
		AString supplied = RT.ensureString(RT.getIn(input, Fields.SEED));
		if (supplied == null) throw new InvalidInputException(NO_SEED);
		String value = supplied.toString();
		boolean secretRef = Engine.isSecretRef(value);
		if (requireSecretRef && !secretRef) {
			throw new InvalidInputException(SEED_REF_REQUIRED);
		}
		if (secretRef) {
			String resolved = engine.resolveSecret(value, ctx);
			if (resolved == null) throw new NotFoundException(SEED_UNAVAILABLE + value, value);
			value = resolved;
		}
		Blob seed = parseBlob(Strings.create(value), "seed");
		if (seed.count() != AKeyPair.SEED_LENGTH) {
			throw new InvalidInputException(WRONG_SEED_LENGTH);
		}
		return AKeyPair.create(seed);
	}

	private static Blob parseBlob(AString value, String field) {
		ABlob parsed = Blobs.parse(value);
		if (parsed == null) throw new InvalidInputException(field + " " + NOT_HEX);
		return parsed.toFlatBlob();
	}

	private CompletableFuture<ACell> withConvexClient(RequestContext ctx, AMap<AString, ACell> meta, ACell input,
			Function<Convex, CompletableFuture<ACell>> action) {

		return CompletableFuture.supplyAsync(() -> {
			// Outside the connect wrapper: a refusal is the venue's answer, not a connection failure
			URI endpoint = permittedPeer(ctx, meta, input);
			try {
				return Convex.connect(endpoint);
			} catch (IOException | TimeoutException e) {
				// The client's own text names local sockets and hosts, not the peer's answer
				throw new UnavailableException(PEER_UNREACHABLE + endpoint, e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new CancellationException(CONNECT_INTERRUPTED + endpoint);
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
		.orTimeout(CONVEX_CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
		.exceptionallyCompose(e -> CompletableFuture.failedFuture(
			(unwrap(e) instanceof TimeoutException) ? new UnavailableException(PEER_SILENT, e) : e));
	}

	/**
	 * The peer this call may reach: the venue's own, unless the caller names
	 * another in its input or in its operation's metadata (operations are
	 * user-creatable assets, so both are the caller's choice). Another peer is a
	 * relay, refused unless the operator sets {@code anyPeer}; then its host
	 * passes the venue's outbound address guard like any caller-chosen target.
	 */
	private URI permittedPeer(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		PeerPolicy p = policy;
		ACell named = RT.getIn(input, Fields.PEER);
		if (named == null && meta != null) named = RT.getIn(meta, Fields.OPERATION, Fields.PEER);
		if (named == null) return p.peer();
		URI target = peerEndpoint(named, "peer");
		if (target.equals(p.peer())) return target;
		if (!p.anyPeer()) throw new InvalidInputException("Peer " + target + " " + OTHER_PEER);
		engine.requireSafeHost(ctx, target.getHost());
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

	private static InvalidInputException notAPeer(String field, ACell value) {
		return new InvalidInputException(field + " " + NOT_A_PEER + ", got: " + value);
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
