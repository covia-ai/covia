package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import convex.core.crypto.Hashing;
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
	
	AString EMPTY_META=Strings.create("{}");
	
	@BeforeAll
	public void setupVenue() throws IOException {
		v=new Venue();
		Venue.addDemoAssets(v);
	}

	
	@Test
	public void testTempVenue() throws IOException {
		
		
		
		
		Blob content=Blob.EMPTY;
		
		Hash id=v.storeAsset(EMPTY_META,content);
		
		assertEquals(id,Hashing.sha256(EMPTY_META));
		
		ACell md=v.getMetadata(id);
		assertEquals(EMPTY_META,md);
		
	}
	
	@Test public void testDemoAssets() throws IOException {
		AString emptyMeta=v.getMetadata(Hash.parse("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"));
		assertEquals(EMPTY_META,emptyMeta);
	}
	
	@Test public void testOp() throws IOException {
		String ms=Utils.readResourceAsString("/samples/meta/op1.json");
		assertNotNull(ms);
		
		ACell md=JSONUtils.parse(ms);
		assertEquals("random",RT.getIn(md, "name").toString());
	}
}
