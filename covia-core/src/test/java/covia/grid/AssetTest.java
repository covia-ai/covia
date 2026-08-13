package covia.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

public class AssetTest {
	private static final String EMPTY_OBJECT = "{}";

	@Test public void testEmpty() {
		Asset a1=Asset.forString(EMPTY_OBJECT);
		Asset a2=Asset.forString(Strings.create(EMPTY_OBJECT));
		
		assertEquals(EMPTY_OBJECT,a2.getMetadata().toString());
		
		assertEquals(a1,a2);
	}
	
	@Test public void testMeta() {
		AMap<AString,ACell> meta=Maps.of("name","My Asset");
		Asset a=Asset.fromMeta(meta);
		
		assertEquals(a,Asset.fromMeta(meta));
		assertEquals(meta,a.meta());
	}

	@Test public void testMetaConcurrentAccessReturnsOneImmutableValue() {
		Asset asset = Asset.forString("{\"name\":\"Concurrent Asset\",\"rank\":7}");
		var reads = IntStream.range(0, 200)
			.mapToObj(i -> CompletableFuture.supplyAsync(asset::meta))
			.toList();

		AMap<AString, ACell> first = reads.get(0).join();
		for (CompletableFuture<AMap<AString, ACell>> read : reads) {
			assertSame(first, read.join(), "metadata should be parsed and cached once");
		}
		assertEquals("Concurrent Asset", first.get(Strings.create("name")).toString());
	}
}
