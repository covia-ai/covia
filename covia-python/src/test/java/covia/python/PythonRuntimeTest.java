package covia.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

@Tag("integration")
@EnabledIfSystemProperty(named = "covia.tests.integration", matches = "true",
	disabledReason = "embeds native CPython; enable with -Dcovia.tests.integration=true")
@Execution(ExecutionMode.SAME_THREAD)
class PythonRuntimeTest {
	@BeforeAll
	static void requirePython() {
		var availability = PythonRuntime.availability();
		Assumptions.assumeTrue(availability.available(), availability.detail());
	}

	@Test
	void availabilityAlwaysExplainsItsResult() {
		PythonAvailability availability = PythonRuntime.availability();
		assertTrue(availability.available());
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

	@Test
	void scriptCallsAcceptZeroOrMultipleArguments() {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		PythonRuntime runtime = PythonRuntime.open();
		try (PythonScript script = runtime.load("""
			def no_args():
			    return 42
			def combine(left, right):
			    return {"sum": left + right}
			""", "arguments.py")) {
			assertEquals(CVMLong.create(42), script.call("no_args", List.of()));
			assertEquals(Maps.of("sum", 12L), script.call("combine",
				List.of(CVMLong.create(5), CVMLong.create(7))));
		}
	}

	@Test
	void nativePythonBufferProtocolRoundTripsCanonicalFloat64() {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		PythonRuntime runtime = PythonRuntime.open();
		try (PythonScript script = runtime.load("""
			import struct
			def scale(payload):
			    values = struct.unpack("<" + "d" * (len(payload) // 8), memoryview(payload))
			    return struct.pack("<" + "d" * len(values), *(value * 2 for value in values))
			""", "float64.py")) {
			ABlob output = (ABlob) script.call("scale",
				PythonRuntime.packFloat64(new double[] { 1.25, -3.0, 8.5 }));
			assertEquals(List.of(2.5, -6.0, 17.0), Arrays.stream(
				PythonRuntime.unpackFloat64(output)).boxed().toList());
		}
	}

	@Test
	void activePythonCallCanBeInterruptedBestEffort() throws Exception {
		Assumptions.assumeTrue(PythonRuntime.availability().available(),
			PythonRuntime.availability().detail());
		PythonRuntime runtime = PythonRuntime.open();
		assertFalse(runtime.interruptCurrentCall(), "idle runtime has no call to interrupt");

		PythonScript script = runtime.load("""
			def spin():
			    while True:
			        pass
			def healthy():
			    return 42
			""", "interrupt.py");
		CompletableFuture<Throwable> outcome = new CompletableFuture<>();
		Thread.ofPlatform().daemon(true).name("covia-python-interrupt-test").start(() -> {
			try {
				script.call("spin", List.of());
				outcome.complete(null);
			} catch (Throwable t) {
				outcome.complete(t);
			}
		});

		boolean scheduled = false;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!scheduled && System.nanoTime() < deadline) {
			scheduled = runtime.interruptCurrentCall();
			if (!scheduled) Thread.sleep(5);
		}
		assertTrue(scheduled, "CPython call never became interruptible");

		Throwable interrupted = outcome.get(5, TimeUnit.SECONDS);
		assertTrue(interrupted instanceof PythonException,
			() -> "expected PythonException, got " + interrupted);
		assertTrue(interrupted.getMessage().contains("KeyboardInterrupt"),
			interrupted::getMessage);
		assertEquals(CVMLong.create(42), script.call("healthy", List.of()),
			"runtime must remain usable after a cooperative interrupt");
		script.close();
	}
}
