package covia.adapter;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Adapter designed for pluggable operations for arbitrary JVM code
 */
public class JVMAdapter extends AAdapter {

	@Override
	public String getName() {
		return "jvm";
	}
	
	@Override
	protected void installAssets() {
		installAsset("/adapters/jvm/stringConcat.json", null);
		installAsset("/adapters/jvm/urlEncode.json", null);
		installAsset("/adapters/jvm/urlDecode.json", null);
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		String[] parts = operation.split(":");
		if (parts.length != 2) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Invalid operation format: " + operation)
			);
		}
		
		String jvmOp = parts[1];
		
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
			AString first = RT.ensureString(RT.getIn(input, convex.core.data.Strings.create("first")));
			AString second = RT.ensureString(RT.getIn(input, convex.core.data.Strings.create("second")));
			AString separator = RT.ensureString(RT.getIn(input, convex.core.data.Strings.create("separator")));
			
			// Handle null values - default to empty strings
			if (first == null) {
				first = convex.core.data.Strings.create("");
			}
			if (second == null) {
				second = convex.core.data.Strings.create("");
			}
			if (separator == null) {
				separator = convex.core.data.Strings.create("");
			}
			
			// Perform string concatenation using AString.append
			AString result = first.append(separator).append(second);
			int count = 2; // We always have 2 input strings
			
			// Create output
			AMap<AString, ACell> output = convex.core.data.Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(convex.core.data.Strings.create("inputCount"), CVMLong.create(count));
			output = output.assoc(convex.core.data.Strings.create("totalLength"), CVMLong.create(result.count()));
			
			return CompletableFuture.completedFuture(output);
			
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private CompletableFuture<ACell> handleUrlEncode(ACell input) {
		try {
			// Extract input parameter
			AString inputString = RT.ensureString(RT.getIn(input, convex.core.data.Strings.create("input")));
			
			// Handle null value - default to empty string
			if (inputString == null) {
				inputString = convex.core.data.Strings.create("");
			}
			
			// Perform URL encoding
			String javaString = inputString.toString();
			String encoded = URLEncoder.encode(javaString, StandardCharsets.UTF_8.name());
			AString result = convex.core.data.Strings.create(encoded);
			
			// Create output
			AMap<AString, ACell> output = convex.core.data.Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(convex.core.data.Strings.create("originalLength"), CVMLong.create(inputString.count()));
			output = output.assoc(convex.core.data.Strings.create("encodedLength"), CVMLong.create(result.count()));
			
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
			AString inputString = RT.ensureString(RT.getIn(input, convex.core.data.Strings.create("input")));
			
			// Handle null value - default to empty string
			if (inputString == null) {
				inputString = convex.core.data.Strings.create("");
			}
			
			// Perform URL decoding
			String javaString = inputString.toString();
			String decoded = URLDecoder.decode(javaString, StandardCharsets.UTF_8.name());
			AString result = convex.core.data.Strings.create(decoded);
			
			// Create output
			AMap<AString, ACell> output = convex.core.data.Maps.empty();
			output = output.assoc(Fields.RESULT, result);
			output = output.assoc(convex.core.data.Strings.create("originalLength"), CVMLong.create(inputString.count()));
			output = output.assoc(convex.core.data.Strings.create("decodedLength"), CVMLong.create(result.count()));
			
			return CompletableFuture.completedFuture(output);
			
		} catch (UnsupportedEncodingException e) {
			return CompletableFuture.failedFuture(e);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}
}
