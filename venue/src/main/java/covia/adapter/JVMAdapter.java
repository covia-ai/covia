package covia.adapter;

import java.io.UnsupportedEncodingException;
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
		installAsset("/adapters/jvm/stringConcat.json");
		installAsset("/adapters/jvm/urlEncode.json");
		installAsset("/adapters/jvm/urlDecode.json");
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
			// Extract input parameters
			AString first = RT.ensureString(RT.getIn(input, Strings.create("first")));
			AString second = RT.ensureString(RT.getIn(input, Strings.create("second")));
			AString separator = RT.ensureString(RT.getIn(input, Strings.create("separator")));
			
			// Handle null values - default to empty strings
			if (first == null) {
				first = Strings.create("");
			}
			if (second == null) {
				second = Strings.create("");
			}
			if (separator == null) {
				separator = Strings.create("");
			}
			
			// Perform string concatenation using AString.append
			AString result = first.append(separator).append(second);
			int count = 2; // We always have 2 input strings
			
			// Create output
			AMap<AString, ACell> output = Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(Strings.create("inputCount"), CVMLong.create(count));
			output = output.assoc(Strings.create("totalLength"), CVMLong.create(result.count()));
			
			return CompletableFuture.completedFuture(output);
			
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private CompletableFuture<ACell> handleUrlEncode(ACell input) {
		try {
			// Extract input parameter
			AString inputString = RT.ensureString(RT.getIn(input, Fields.INPUT));
			
			// Handle null value - default to empty string
			if (inputString == null) {
				inputString = Strings.create("");
			}
			
			// Perform URL encoding
			String javaString = inputString.toString();
			String encoded = URLEncoder.encode(javaString, StandardCharsets.UTF_8.name());
			AString result = Strings.create(encoded);
			
			// Create output
			AMap<AString, ACell> output = Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(Strings.create("originalLength"), CVMLong.create(inputString.count()));
			output = output.assoc(Strings.create("encodedLength"), CVMLong.create(result.count()));
			
			return CompletableFuture.completedFuture(output);
			
		} catch (UnsupportedEncodingException e) {
			return CompletableFuture.failedFuture(e);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private CompletableFuture<ACell> handleUrlDecode(ACell input) {
		try {
			// Extract input parameter
			AString inputString = RT.ensureString(RT.getIn(input, Fields.INPUT));
			
			// Handle null value - default to empty string
			if (inputString == null) {
				inputString = Strings.create("");
			}
			
			// Perform URL decoding
			String javaString = inputString.toString();
			String decoded = URLDecoder.decode(javaString, StandardCharsets.UTF_8.name());
			AString result = Strings.create(decoded);
			
			// Create output
			AMap<AString, ACell> output = Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(Strings.create("originalLength"), CVMLong.create(inputString.count()));
			output = output.assoc(Strings.create("decodedLength"), CVMLong.create(result.count()));
			
			return CompletableFuture.completedFuture(output);
			
		} catch (UnsupportedEncodingException e) {
			return CompletableFuture.failedFuture(e);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
}
