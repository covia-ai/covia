package covia.adapter;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * Adapter designed for pluggable operations for arbitrary JVM code
 */
public class JVMAdapter extends AAdapter {

	private static final AString K_FIRST = Strings.intern("first");
	private static final AString K_SECOND = Strings.intern("second");
	private static final AString K_SEPARATOR = Strings.intern("separator");
	private static final AString K_INPUT_COUNT = Strings.intern("inputCount");
	private static final AString K_TOTAL_LENGTH = Strings.intern("totalLength");
	private static final AString K_ORIGINAL_LENGTH = Strings.intern("originalLength");
	private static final AString K_ENCODED_LENGTH = Strings.intern("encodedLength");
	private static final AString K_DECODED_LENGTH = Strings.intern("decodedLength");
	private static final AString EMPTY = Strings.intern("");

	@Override
	public String getName() {
		return "jvm";
	}
	
	@Override
	public String getDescription() {
		return "Provides utility functions executed on the venue's Java Virtual Machine. " +
			   "Supports string concatenation, URL encoding/decoding, and other common. " +
			   "Ideal for data processing, text manipulation, and integration with Java-based systems and libraries.";
	}
	
	@Override
	protected void installAssets() {
		installAsset("jvm/string-concat", "/adapters/jvm/stringConcat.json");
		installAsset("jvm/url-encode",    "/adapters/jvm/urlEncode.json");
		installAsset("jvm/url-decode",    "/adapters/jvm/urlDecode.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String jvmOp = getSubOperation(meta);

		switch (jvmOp) {
			case "stringConcat":
				return handleStringConcat(input);
			case "urlEncode":
				return handleUrlEncode(input);
			case "urlDecode":
				return handleUrlDecode(input);
			default:
				return CompletableFuture.failedFuture(
					new IllegalArgumentException("Unknown JVM operation: " + jvmOp)
				);
		}
	}

	private CompletableFuture<ACell> handleStringConcat(ACell input) {
		try {
			// Extract input parameters, defaulting nulls to empty strings
			AString first = RT.ensureString(RT.getIn(input, K_FIRST));
			AString second = RT.ensureString(RT.getIn(input, K_SECOND));
			AString separator = RT.ensureString(RT.getIn(input, K_SEPARATOR));
			if (first == null) first = EMPTY;
			if (second == null) second = EMPTY;
			if (separator == null) separator = EMPTY;

			// Perform string concatenation using AString.append
			AString result = first.append(separator).append(second);

			// Create output
			AMap<AString, ACell> output = Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(K_INPUT_COUNT, CVMLong.create(2)); // always 2 inputs
			output = output.assoc(K_TOTAL_LENGTH, CVMLong.create(result.count()));

			return CompletableFuture.completedFuture(output);

		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private CompletableFuture<ACell> handleUrlEncode(ACell input) {
		try {
			AString inputString = RT.ensureString(RT.getIn(input, Fields.INPUT));
			if (inputString == null) inputString = EMPTY;

			// Perform URL encoding (Charset overload — no checked exception)
			String encoded = URLEncoder.encode(inputString.toString(), StandardCharsets.UTF_8);
			AString result = Strings.create(encoded);

			// Create output
			AMap<AString, ACell> output = Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(K_ORIGINAL_LENGTH, CVMLong.create(inputString.count()));
			output = output.assoc(K_ENCODED_LENGTH, CVMLong.create(result.count()));

			return CompletableFuture.completedFuture(output);

		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private CompletableFuture<ACell> handleUrlDecode(ACell input) {
		try {
			AString inputString = RT.ensureString(RT.getIn(input, Fields.INPUT));
			if (inputString == null) inputString = EMPTY;

			// Perform URL decoding (Charset overload — no checked exception)
			String decoded = URLDecoder.decode(inputString.toString(), StandardCharsets.UTF_8);
			AString result = Strings.create(decoded);

			// Create output
			AMap<AString, ACell> output = Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(K_ORIGINAL_LENGTH, CVMLong.create(inputString.count()));
			output = output.assoc(K_DECODED_LENGTH, CVMLong.create(result.count()));

			return CompletableFuture.completedFuture(output);

		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
}
