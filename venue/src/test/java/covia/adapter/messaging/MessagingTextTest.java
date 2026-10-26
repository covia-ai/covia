package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The shared splitter keeps every chunk within the limit, prefers paragraph, line and
 * word breaks, and never separates a surrogate pair.
 */
class MessagingTextTest {

	@Test void chunksStayWithinTheLimitAndPreferNaturalBreaks() {
		assertEquals(List.of("aaaa\nbbbb", "cccc"), MessagingText.split("aaaa\nbbbb\ncccc", 10));
		assertEquals(List.of("abcdefghij", "klm"), MessagingText.split("abcdefghijklm", 10));
		assertEquals(List.of("abc", "def"), MessagingText.split("abc def", 3));
		assertEquals(List.of("one two", "three"), MessagingText.split("one two\n\nthree", 12), "a paragraph break wins");
		assertEquals(List.of("short"), MessagingText.split("short", 10));
		assertEquals(List.of(""), MessagingText.split("", 10));
		assertEquals(List.of(), MessagingText.split(null, 10));
		assertEquals(List.of("abc"), MessagingText.split("abc\n", 3), "no empty trailing chunk");
		for (String chunk : MessagingText.split("a ".repeat(3_000), 4096)) assertTrue(chunk.length() <= 4096);
		assertEquals(2, MessagingText.split("a ".repeat(3_000), 4096).size());
	}

	@Test void aSurrogatePairIsNeverSplit() {
		String emoji = "😀";   // one code point, two UTF-16 units
		List<String> chunks = MessagingText.split("a" + emoji.repeat(4), 4);
		assertEquals(String.join("", chunks), "a" + emoji.repeat(4));
		for (String chunk : chunks) {
			assertTrue(chunk.length() <= 4);
			assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)), chunk);
			assertFalse(Character.isLowSurrogate(chunk.charAt(0)), chunk);
		}
		assertThrows(IllegalArgumentException.class, () -> MessagingText.split("abc", 1));
	}
}
