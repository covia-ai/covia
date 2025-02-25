package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Strings;

public class VenueTest {

	
	@Test
	public void testTempVenue() throws IOException {
		Venue v=new Venue();
		
		AString EMPTY_META=Strings.create("{}");
		
		
		Blob content=Blob.EMPTY;
		
		Hash id=v.storeAsset(EMPTY_META,content);
		
		assertEquals(id,EMPTY_META.toBlob().getContentHash());
		
	}
}
