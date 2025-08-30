package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.grid.client.VenueHTTP;
import covia.venue.TestServer;

public class JVMTest {
	
	@Test public void testStringConcat() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test basic string concatenation
		Job result = covia.invokeSync("jvm:stringConcat", Maps.of(
			"first", "Hello",
			"second", "World"
		));
		
		// Debug output to see what happened
		if (result.getStatus() == Status.FAILED) {
			System.err.println("Job failed with error: " + result.getErrorMessage());
			System.err.println("Job output: " + result.getOutput());
		}
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result - with empty separator, should be "HelloWorld"
		String concatenated = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("HelloWorld", concatenated, "Strings should be concatenated correctly without separator");
		
		// Verify metadata
		long inputCount = RT.ensureLong(RT.getIn(result.getOutput(), "inputCount")).longValue();
		assertEquals(2, inputCount, "Should have processed 2 input strings");
		
		long totalLength = RT.ensureLong(RT.getIn(result.getOutput(), "totalLength")).longValue();
		assertEquals(10, totalLength, "Total length should be 10 characters");
	}
	
	@Test public void testStringConcatWithSeparator() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test string concatenation with separator
		Job result = covia.invokeSync("jvm:stringConcat", Maps.of(
			"first", "apple",
			"second", "banana",
			"separator", "|"
		));
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result
		String concatenated = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("apple|banana", concatenated, "Strings should be concatenated with separator");
		
		// Verify metadata
		long inputCount = RT.ensureLong(RT.getIn(result.getOutput(), "inputCount")).longValue();
		assertEquals(2, inputCount, "Should have processed 2 input strings");
		
		long totalLength = RT.ensureLong(RT.getIn(result.getOutput(), "totalLength")).longValue();
		assertEquals(12, totalLength, "Total length should be 12 characters");
	}
	
	@Test public void testStringConcatEmptyStrings() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test string concatenation with empty strings
		Job result = covia.invokeSync("jvm:stringConcat", Maps.of(
			"first", "",
			"second", ""
		));
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result - empty strings with empty separator should result in empty string
		String concatenated = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("", concatenated, "Empty strings with empty separator should result in empty string");
		
		// Verify metadata
		long inputCount = RT.ensureLong(RT.getIn(result.getOutput(), "inputCount")).longValue();
		assertEquals(2, inputCount, "Should have processed 2 input strings");
		
		long totalLength = RT.ensureLong(RT.getIn(result.getOutput(), "totalLength")).longValue();
		assertEquals(0, totalLength, "Total length should be 0 characters");
	}
	
	@Test public void testStringConcatWithNullInputs() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test string concatenation with null inputs (should default to empty strings)
		Job result = covia.invokeSync("jvm:stringConcat", Maps.of());
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result - null inputs should default to empty strings with empty separator
		String concatenated = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("", concatenated, "Null inputs should default to empty strings resulting in empty string");
		
		// Verify metadata
		long inputCount = RT.ensureLong(RT.getIn(result.getOutput(), "inputCount")).longValue();
		assertEquals(2, inputCount, "Should have processed 2 input strings");
		
		long totalLength = RT.ensureLong(RT.getIn(result.getOutput(), "totalLength")).longValue();
		assertEquals(0, totalLength, "Total length should be 0 characters");
	}
	
	@Test public void testUrlEncode() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test URL encoding with special characters
		Job result = covia.invokeSync("jvm:urlEncode", Maps.of(
			"input", "Hello World! & Co."
		));
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result
		String encoded = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("Hello+World%21+%26+Co.", encoded, "String should be properly URL encoded");
		
		// Verify metadata
		long originalLength = RT.ensureLong(RT.getIn(result.getOutput(), "originalLength")).longValue();
		assertEquals(18, originalLength, "Original length should be 18 characters");
		
		long encodedLength = RT.ensureLong(RT.getIn(result.getOutput(), "encodedLength")).longValue();
		assertEquals(22, encodedLength, "Encoded length should be 22 characters");
	}
	
	@Test public void testUrlEncodeEmptyString() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test URL encoding with empty string
		Job result = covia.invokeSync("jvm:urlEncode", Maps.of(
			"input", ""
		));
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result
		String encoded = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("", encoded, "Empty string should remain empty when encoded");
		
		// Verify metadata
		long originalLength = RT.ensureLong(RT.getIn(result.getOutput(), "originalLength")).longValue();
		assertEquals(0, originalLength, "Original length should be 0 characters");
		
		long encodedLength = RT.ensureLong(RT.getIn(result.getOutput(), "encodedLength")).longValue();
		assertEquals(0, encodedLength, "Encoded length should be 0 characters");
	}
	
	@Test public void testUrlEncodeNullInput() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test URL encoding with null input (should default to empty string)
		Job result = covia.invokeSync("jvm:urlEncode", Maps.of());
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result
		String encoded = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("", encoded, "Null input should default to empty string when encoded");
		
		// Verify metadata
		long originalLength = RT.ensureLong(RT.getIn(result.getOutput(), "originalLength")).longValue();
		assertEquals(0, originalLength, "Original length should be 0 characters");
		
		long encodedLength = RT.ensureLong(RT.getIn(result.getOutput(), "encodedLength")).longValue();
		assertEquals(0, encodedLength, "Encoded length should be 0 characters");
	}
	
	@Test public void testUrlDecode() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test URL decoding with encoded string
		Job result = covia.invokeSync("jvm:urlDecode", Maps.of(
			"input", "Hello+World%21+%26+Co."
		));
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result
		String decoded = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("Hello World! & Co.", decoded, "String should be properly URL decoded");
		
		// Verify metadata
		long originalLength = RT.ensureLong(RT.getIn(result.getOutput(), "originalLength")).longValue();
		assertEquals(22, originalLength, "Original length should be 22 characters");
		
		long decodedLength = RT.ensureLong(RT.getIn(result.getOutput(), "decodedLength")).longValue();
		assertEquals(18, decodedLength, "Decoded length should be 18 characters");
	}
	
	@Test public void testUrlDecodeEmptyString() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test URL decoding with empty string
		Job result = covia.invokeSync("jvm:urlDecode", Maps.of(
			"input", ""
		));
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result
		String decoded = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("", decoded, "Empty string should remain empty when decoded");
		
		// Verify metadata
		long originalLength = RT.ensureLong(RT.getIn(result.getOutput(), "originalLength")).longValue();
		assertEquals(0, originalLength, "Original length should be 0 characters");
		
		long decodedLength = RT.ensureLong(RT.getIn(result.getOutput(), "decodedLength")).longValue();
		assertEquals(0, decodedLength, "Decoded length should be 0 characters");
	}
	
	@Test public void testUrlDecodeNullInput() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		// Test URL decoding with null input (should default to empty string)
		Job result = covia.invokeSync("jvm:urlDecode", Maps.of());
		
		assertEquals(Status.COMPLETE, result.getStatus(), "Job should complete successfully");
		
		// Verify the result
		String decoded = RT.ensureString(RT.getIn(result.getOutput(), "result")).toString();
		assertEquals("", decoded, "Null input should default to empty string when decoded");
		
		// Verify metadata
		long originalLength = RT.ensureLong(RT.getIn(result.getOutput(), "originalLength")).longValue();
		assertEquals(0, originalLength, "Original length should be 0 characters");
		
		long decodedLength = RT.ensureLong(RT.getIn(result.getOutput(), "decodedLength")).longValue();
		assertEquals(0, decodedLength, "Decoded length should be 0 characters");
	}
	
	@Test public void testUrlEncodeDecodeRoundTrip() throws InterruptedException, ExecutionException, TimeoutException {
		VenueHTTP covia = TestServer.COVIA;
		
		String original = "Hello World! & Co. + Special/Chars?";
		
		// First encode
		Job encodeResult = covia.invokeSync("jvm:urlEncode", Maps.of(
			"input", original
		));
		
		assertEquals(Status.COMPLETE, encodeResult.getStatus(), "Encode job should complete successfully");
		String encoded = RT.ensureString(RT.getIn(encodeResult.getOutput(), "result")).toString();
		
		// Then decode
		Job decodeResult = covia.invokeSync("jvm:urlDecode", Maps.of(
			"input", encoded
		));
		
		assertEquals(Status.COMPLETE, decodeResult.getStatus(), "Decode job should complete successfully");
		String decoded = RT.ensureString(RT.getIn(decodeResult.getOutput(), "result")).toString();
		
		// Verify round-trip works
		assertEquals(original, decoded, "Encode-decode round-trip should preserve the original string");
	}
} 