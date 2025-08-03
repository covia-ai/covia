package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import convex.core.data.Blobs;
import covia.grid.impl.BlobContent;

public class ContentTest {

	
	@Test public void testBlobContent() throws IOException {
		BlobContent bc=BlobContent.of(Blobs.empty());
		
		InputStream bis=bc.getInputStream();
		assertEquals(-1,bis.read());
		
		assertSame(Blobs.empty(),bc.getBlob());
	}
	
	@Test public void testFileContent() {
		
	}
}
