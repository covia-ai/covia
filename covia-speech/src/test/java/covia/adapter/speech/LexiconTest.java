package covia.adapter.speech;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import covia.exception.JobFailedException;

/**
 * A lexicon voice is given only words it has: numbers and symbols become words,
 * accents fold, a Latin word it lacks is spelled and named, and anything else
 * fails the request, named. Nothing is passed over in silence.
 */
class LexiconTest {

	static final Lexicon LEXICON = new Lexicon(Set.of("it", "the", "train", "leaves", "at", "four", "pm", "it's", "cafe",
		"is", "open", "sam", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s",
		"t", "u", "v", "w", "x", "y", "z", "costs", "three", "point", "five", "zero", "dollars", "percent", "and", "on",
		"twenty", "first", "hundred", "thousand", "one", "two", "seven"));

	@Test
	void everyWordItSaysIsInItsLexicon() {
		Lexicon.Sayable said = LEXICON.sayable("The train leaves at 4pm; it’s on the 21st.");
		assertEquals("the train leaves at four pm; it's on the twenty first.", said.text());
		assertEquals(List.of(), said.spelled());
		assertEquals("cafe is open.", LEXICON.sayable("Café is open.").text(), "accents fold");
		assertEquals("sam", LEXICON.sayable("Sam's").text(), "a possessive is said as its word");
	}

	@Test
	void numbersCurrencyAndSymbolsAreSaid() {
		assertEquals("it costs three point five zero dollars", LEXICON.sayable("It costs $3.50").text());
		assertEquals("twenty five percent and one hundred and two", LEXICON.sayable("25% & 102").text());
		assertEquals("one thousand two hundred and seven", Lexicon.numbers("1,207").strip());
		assertEquals("zero zero seven", Lexicon.numbers("007").strip(), "a leading zero is read digit by digit");
	}

	@Test
	void aWordItLacksIsSpelledAndNamed() {
		Lexicon.Sayable said = LEXICON.sayable("Sam uses the API at Brightside.");
		assertTrue(said.text().contains("a p i"), said.text());
		assertTrue(said.text().contains("b r i g h t s i d e"), said.text());
		assertEquals(List.of("uses", "API", "Brightside"), said.spelled(), "every spelled word is named");
	}

	@Test
	void whatCannotBeSaidFailsLoudly() {
		JobFailedException e = assertThrows(JobFailedException.class, () -> LEXICON.sayable("The train to 東京 leaves."));
		assertEquals(Lexicon.UNSAYABLE.formatted("東京"), e.getMessage());
	}
}
