package covia.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.DoubleBuffer;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;

/** Pure-Java tests for the canonical buffer format; no CPython is loaded. */
class PythonBuffersTest {
	@Test
	void float64BulkFormatIsCanonicalLittleEndian() {
		Blob packed = PythonRuntime.packFloat64(new double[] { 1.0, -2.5 });
		assertEquals(Blob.fromHex("000000000000f03f00000000000004c0"), packed);
		assertEquals(List.of(1.0, -2.5), Arrays.stream(
			PythonRuntime.unpackFloat64(packed)).boxed().toList());
	}

	@Test
	void float64BufferPackingPreservesPositionAndRawValues() {
		DoubleBuffer source = DoubleBuffer.wrap(new double[] {
			99.0, -0.0, Double.POSITIVE_INFINITY,
			Double.longBitsToDouble(0x7ff8000000000001L)
		});
		source.position(1);
		Blob packed = PythonRuntime.packFloat64(source);
		assertEquals(1, source.position());

		double[] unpacked = PythonRuntime.unpackFloat64(packed);
		assertEquals(3, unpacked.length);
		assertEquals(Double.doubleToRawLongBits(-0.0),
			Double.doubleToRawLongBits(unpacked[0]));
		assertEquals(Double.POSITIVE_INFINITY, unpacked[1]);
		assertEquals(0x7ff8000000000001L,
			Double.doubleToRawLongBits(unpacked[2]));
	}

	@Test
	void float64BulkFormatRejectsMalformedPayloads() {
		assertThrows(IllegalArgumentException.class,
			() -> PythonRuntime.packFloat64((double[]) null));
		assertThrows(IllegalArgumentException.class,
			() -> PythonRuntime.unpackFloat64(null));
		IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
			() -> PythonRuntime.unpackFloat64(Blob.wrap(new byte[9])));
		assertTrue(malformed.getMessage().contains("multiple of 8"));
	}
}
