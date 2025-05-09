package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSONUtils;
import convex.core.util.Utils;

@TestInstance(Lifecycle.PER_CLASS)
public class VenueTest {

	public Venue v;
	
	@BeforeAll
	public void setupVenue() throws IOException {
		v=new Venue();
	}

	
	@Test
	public void testTempVenue() throws IOException {
		
		AString EMPTY_META=Strings.create("{}");
		
		
		Blob content=Blob.EMPTY;
		
		Hash id=v.storeAsset(EMPTY_META,content);
		
		assertEquals(id,EMPTY_META.toBlob().getContentHash());
		
	}
	
	@Test public void testOp() throws IOException {
		String ms=Utils.readResourceAsString("/samples/meta/op1.json");
		assertNotNull(ms);
		
		ACell md=JSONUtils.parse(ms);
		assertEquals("random",RT.getIn(md, "name").toString());
	}
}
