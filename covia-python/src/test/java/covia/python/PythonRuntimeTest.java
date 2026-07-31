package covia.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

class PythonRuntimeTest {
	@Test
	void availabilityAlwaysExplainsItsResult() {
		PythonAvailability availability = PythonRuntime.availability();
		assertFalse(availability.detail().isBlank());
	}

	@Test
	void nativeScriptRoundTripsConvexStructures() {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		PythonRuntime runtime = PythonRuntime.open();
		AMap<ACell, ACell> input = Maps.of(
			Strings.create("numbers"), Vectors.of(CVMLong.create(2), CVMLong.create(5)),
			Strings.create("payload"), Blob.wrap(new byte[] { 1, 2, 3 }),
			Strings.create("large"), CVMBigInteger.wrap(
				new BigInteger("123456789012345678901234567890")));

		String source = """
			def main(value):
			    return {
			        "sum": value["numbers"][0] + value["numbers"][1],
			        "payload": value["payload"],
			        "large": value["large"] + 1,
			        "ok": True,
			    }
			""";
		try (PythonScript script = runtime.load(source, "roundtrip.py")) {
			ACell raw = script.call("main", input);
			AMap<ACell, ACell> result = (AMap<ACell, ACell>) raw;
			assertEquals(CVMLong.create(7), result.get(Strings.create("sum")));
			assertEquals(Blob.wrap(new byte[] { 1, 2, 3 }),
				result.get(Strings.create("payload")));
			assertEquals(CVMBool.TRUE, result.get(Strings.create("ok")));
			assertEquals(CVMBigInteger.wrap(
				new BigInteger("123456789012345678901234567891")),
				result.get(Strings.create("large")));
		}
	}

	@Test
	void nativeErrorsAreActionableAndReferencesCanOutliveAliases() {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		PythonRuntime runtime = PythonRuntime.open();
		try (PythonRef original = runtime.evaluate("'still alive'")) {
			PythonRef retained = original.retain();
			original.close();
			try (retained) {
				assertEquals(Strings.create("still alive"), retained.toConvex());
			}
		}

		PythonException error = assertThrows(PythonException.class,
			() -> runtime.evaluate("1 / 0"));
		assertTrue(error.getMessage().contains("division by zero"), error::getMessage);

		try (PythonRef cycle = runtime.evaluate(
				"(lambda x: (x.append(x), x)[1])([])")) {
			PythonException cyclic = assertThrows(PythonException.class, cycle::toConvex);
			assertTrue(cyclic.getMessage().contains("Cyclic"), cyclic::getMessage);
		}
	}
}
