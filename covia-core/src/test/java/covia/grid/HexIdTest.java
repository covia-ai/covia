package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Strings;

public class HexIdTest {

	private static final Blob ID = Blob.fromHex("00ab12cd");

	@Test
	public void anIdReadsTheSameWithOrWithoutThePrefix() {
		assertEquals(ID, HexId.parse(Strings.create("00ab12cd")));
		assertEquals(ID, HexId.parse(Strings.create("0x00ab12cd")));
	}

	@Test
	public void hexDigitsMayBeEitherCase() {
		assertEquals(ID, HexId.parse(Strings.create("00AB12CD")));
		assertEquals(ID, HexId.parse(Strings.create("0x00AB12CD")));
	}

	@Test
	public void nothingButHexIsAnId() {
		for (String text : List.of(
				" 00ab12cd", "00ab12cd ", "00ab 12cd", "\t00ab12cd", "00ab12cd\n",
				" 0x00ab12cd", "0x 00ab12cd", "0x00ab12cd ",
				"", " ", "0x",
				"00ab12c", "0x00ab12c",
				"00ag12cd", "0x0x00ab12cd", "0X00ab12cd")) {
			assertNull(HexId.parse(Strings.create(text)), "'" + text + "' is not an id");
		}
	}

	@Test
	public void noTextIsNoId() {
		assertNull(HexId.parse(null));
		assertNull(HexId.parseHash(null));
	}

	@Test
	public void aHashIsAWholeHashWithOrWithoutThePrefix() {
		String hex = "ab".repeat(Hash.LENGTH);
		Hash hash = Hash.fromHex(hex);
		assertEquals(hash, HexId.parseHash(Strings.create(hex)));
		assertEquals(hash, HexId.parseHash(Strings.create("0x" + hex)));

		for (String text : List.of(" " + hex, hex + " ", "ab", hex + "ab")) {
			assertNull(HexId.parseHash(Strings.create(text)), "'" + text + "' is not a hash");
		}
	}
}
