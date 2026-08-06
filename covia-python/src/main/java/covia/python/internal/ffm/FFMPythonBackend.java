package covia.python.internal.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import covia.python.PythonException;
import covia.python.internal.PythonBackend;

/** CPython C-API bindings implemented directly with the stable Java FFM API. */
public final class FFMPythonBackend implements PythonBackend {
	private static final int PY_FILE_INPUT = 257;
	private static final int PY_EVAL_INPUT = 258;
	private static final int MAX_CONVERSION_DEPTH = 64;
	private static final Object INIT_LOCK = new Object();
	private static final boolean WINDOWS = System.getProperty("os.name", "")
		.toLowerCase(Locale.ROOT).contains("win");
	private static final MemoryLayout C_UNSIGNED_LONG = WINDOWS ? JAVA_INT : JAVA_LONG;

	private record Library(SymbolLookup symbols, String description) {}

	private final SymbolLookup symbols;
	private final String library;
	private final Linker linker = Linker.nativeLinker();

	private final MethodHandle pyIsInitialized;
	private final MethodHandle pyInitialize;
	private final MethodHandle pyEvalSaveThread;
	private final MethodHandle pyGilEnsure;
	private final MethodHandle pyGilRelease;
	private final MethodHandle pyThreadGetIdent;
	private final MethodHandle pyThreadStateSetAsyncExc;
	private final MethodHandle pyGetVersion;
	private final MethodHandle pyIncRef;
	private final MethodHandle pyDecRef;
	private final MethodHandle pyErrOccurred;
	private final MethodHandle pyErrFetch;
	private final MethodHandle pyErrNormalize;
	private final MethodHandle pyObjectStr;
	private final MethodHandle pyObjectIsInstance;
	private final MethodHandle pyCallableCheck;
	private final MethodHandle pyCompileString;
	private final MethodHandle pyEvalCode;
	private final MethodHandle pyEvalGetBuiltins;
	private final MethodHandle pyDictNew;
	private final MethodHandle pyDictSetItem;
	private final MethodHandle pyDictSetItemString;
	private final MethodHandle pyDictGetItemString;
	private final MethodHandle pyDictNext;
	private final MethodHandle pyTupleNew;
	private final MethodHandle pyTupleSetItem;
	private final MethodHandle pyTupleSize;
	private final MethodHandle pyTupleGetItem;
	private final MethodHandle pyListNew;
	private final MethodHandle pyListSetItem;
	private final MethodHandle pyListSize;
	private final MethodHandle pyListGetItem;
	private final MethodHandle pyObjectCallObject;
	private final MethodHandle pyBoolFromLong;
	private final MethodHandle pyLongFromString;
	private final MethodHandle pyLongAsLongLongOverflow;
	private final MethodHandle pyFloatFromDouble;
	private final MethodHandle pyFloatAsDouble;
	private final MethodHandle pyUnicodeFromStringSize;
	private final MethodHandle pyUnicodeAsUtf8Size;
	private final MethodHandle pyBytesFromStringSize;
	private final MethodHandle pyBytesAsStringSize;

	private final MemorySegment none;
	private final MemorySegment boolType;
	private final MemorySegment longType;
	private final MemorySegment floatType;
	private final MemorySegment unicodeType;
	private final MemorySegment bytesType;
	private final MemorySegment listType;
	private final MemorySegment tupleType;
	private final MemorySegment dictType;
	private final MemorySegment keyboardInterrupt;
	private final ConcurrentLinkedDeque<Long> activeCalls = new ConcurrentLinkedDeque<>();
	private final String description;

	private FFMPythonBackend(Library loaded) {
		this.symbols = loaded.symbols();
		this.library = loaded.description();

		pyIsInitialized = fn("Py_IsInitialized", JAVA_INT);
		pyInitialize = fn("Py_Initialize", null);
		pyEvalSaveThread = fn("PyEval_SaveThread", ADDRESS);
		pyGilEnsure = fn("PyGILState_Ensure", JAVA_INT);
		pyGilRelease = fn("PyGILState_Release", null, JAVA_INT);
		pyThreadGetIdent = fn("PyThread_get_thread_ident", C_UNSIGNED_LONG);
		pyThreadStateSetAsyncExc = fn("PyThreadState_SetAsyncExc", JAVA_INT,
			C_UNSIGNED_LONG, ADDRESS);
		pyGetVersion = fn("Py_GetVersion", ADDRESS);
		pyIncRef = fn("Py_IncRef", null, ADDRESS);
		pyDecRef = fn("Py_DecRef", null, ADDRESS);
		pyErrOccurred = fn("PyErr_Occurred", ADDRESS);
		pyErrFetch = fn("PyErr_Fetch", null, ADDRESS, ADDRESS, ADDRESS);
		pyErrNormalize = fn("PyErr_NormalizeException", null, ADDRESS, ADDRESS, ADDRESS);
		pyObjectStr = fn("PyObject_Str", ADDRESS, ADDRESS);
		pyObjectIsInstance = fn("PyObject_IsInstance", JAVA_INT, ADDRESS, ADDRESS);
		pyCallableCheck = fn("PyCallable_Check", JAVA_INT, ADDRESS);
		pyCompileString = fn("Py_CompileString", ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
		pyEvalCode = fn("PyEval_EvalCode", ADDRESS, ADDRESS, ADDRESS, ADDRESS);
		pyEvalGetBuiltins = fn("PyEval_GetBuiltins", ADDRESS);
		pyDictNew = fn("PyDict_New", ADDRESS);
		pyDictSetItem = fn("PyDict_SetItem", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
		pyDictSetItemString = fn("PyDict_SetItemString", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
		pyDictGetItemString = fn("PyDict_GetItemString", ADDRESS, ADDRESS, ADDRESS);
		pyDictNext = fn("PyDict_Next", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
		pyTupleNew = fn("PyTuple_New", ADDRESS, JAVA_LONG);
		pyTupleSetItem = fn("PyTuple_SetItem", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);
		pyTupleSize = fn("PyTuple_Size", JAVA_LONG, ADDRESS);
		pyTupleGetItem = fn("PyTuple_GetItem", ADDRESS, ADDRESS, JAVA_LONG);
		pyListNew = fn("PyList_New", ADDRESS, JAVA_LONG);
		pyListSetItem = fn("PyList_SetItem", JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);
		pyListSize = fn("PyList_Size", JAVA_LONG, ADDRESS);
		pyListGetItem = fn("PyList_GetItem", ADDRESS, ADDRESS, JAVA_LONG);
		pyObjectCallObject = fn("PyObject_CallObject", ADDRESS, ADDRESS, ADDRESS);
		pyBoolFromLong = fn("PyBool_FromLong", ADDRESS, JAVA_LONG);
		pyLongFromString = fn("PyLong_FromString", ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
		pyLongAsLongLongOverflow = fn("PyLong_AsLongLongAndOverflow", JAVA_LONG, ADDRESS, ADDRESS);
		pyFloatFromDouble = fn("PyFloat_FromDouble", ADDRESS, JAVA_DOUBLE);
		pyFloatAsDouble = fn("PyFloat_AsDouble", JAVA_DOUBLE, ADDRESS);
		pyUnicodeFromStringSize = fn("PyUnicode_FromStringAndSize", ADDRESS, ADDRESS, JAVA_LONG);
		pyUnicodeAsUtf8Size = fn("PyUnicode_AsUTF8AndSize", ADDRESS, ADDRESS, ADDRESS);
		pyBytesFromStringSize = fn("PyBytes_FromStringAndSize", ADDRESS, ADDRESS, JAVA_LONG);
		pyBytesAsStringSize = fn("PyBytes_AsStringAndSize", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

		none = symbol("_Py_NoneStruct");
		boolType = symbol("PyBool_Type");
		longType = symbol("PyLong_Type");
		floatType = symbol("PyFloat_Type");
		unicodeType = symbol("PyUnicode_Type");
		bytesType = symbol("PyBytes_Type");
		listType = symbol("PyList_Type");
		tupleType = symbol("PyTuple_Type");
		dictType = symbol("PyDict_Type");

		initialise();
		keyboardInterrupt = symbol("PyExc_KeyboardInterrupt")
			.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
		String version = withGil(() -> cString(ptr(pyGetVersion)));
		description = "CPython " + version.lines().findFirst().orElse(version)
			+ " via FFM (" + library + ")";
	}

	/** Called reflectively by the Java-21-safe facade. */
	public static PythonBackend open(String library) {
		if (Long.BYTES != 8) {
			throw new PythonException("CPython FFM currently requires a 64-bit JVM");
		}
		return new FFMPythonBackend(findLibrary(library));
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public Object execute(String source, String filename) {
		return withGil(() -> {
			MemorySegment globals = checked(ptr(pyDictNew), "create globals");
			try {
				MemorySegment builtins = ptr(pyEvalGetBuiltins);
				try (Arena arena = Arena.ofConfined()) {
					MemorySegment key = arena.allocateFrom("__builtins__");
					checkStatus(integer(pyDictSetItemString, globals, key, builtins),
						"install builtins");
					MemorySegment fileKey = arena.allocateFrom("__file__");
					MemorySegment file = unicode(filename);
					try {
						checkStatus(integer(pyDictSetItemString, globals, fileKey, file),
							"set __file__");
					} finally {
						decref(file);
					}
				}
				MemorySegment code = compile(source, filename, PY_FILE_INPUT);
				try {
					MemorySegment result = checked(ptr(pyEvalCode, code, globals, globals),
						"execute " + filename);
					decref(result);
				} finally {
					decref(code);
				}
				return globals;
			} catch (RuntimeException e) {
				decref(globals);
				throw e;
			}
		});
	}

	@Override
	public Object evaluate(String expression, Object globalsHandle) {
		return withGil(() -> {
			MemorySegment globals = globalsHandle == null
				? checked(ptr(pyDictNew), "create globals") : segment(globalsHandle);
			boolean ownGlobals = globalsHandle == null;
			try {
				if (ownGlobals) {
					try (Arena arena = Arena.ofConfined()) {
						checkStatus(integer(pyDictSetItemString, globals,
							arena.allocateFrom("__builtins__"), ptr(pyEvalGetBuiltins)),
							"install builtins");
					}
				}
				MemorySegment code = compile(expression, "<covia-eval>", PY_EVAL_INPUT);
				try {
					return checked(ptr(pyEvalCode, code, globals, globals), "evaluate expression");
				} finally {
					decref(code);
				}
			} finally {
				if (ownGlobals) decref(globals);
			}
		});
	}

	@Override
	public Object get(Object container, String name) {
		return withGil(() -> {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment value = ptr(pyDictGetItemString, segment(container),
					arena.allocateFrom(name));
				if (isNull(value)) {
					if (!isNull(ptr(pyErrOccurred))) throw pythonError("look up '" + name + "'");
					throw new PythonException("Python name not found: " + name);
				}
				incref(value); // PyDict_GetItemString returns a borrowed reference
				return value;
			}
		});
	}

	@Override
	public Object call(Object callableHandle, List<Object> arguments) {
		return withGil(() -> {
			long thread = pythonThreadIdent();
			activeCalls.addLast(thread);
			MemorySegment callable = segment(callableHandle);
			try {
				if (integer(pyCallableCheck, callable) == 0) {
					throw new PythonException("Python object is not callable");
				}
				MemorySegment tuple = checked(ptr(pyTupleNew, (long) arguments.size()),
					"allocate arguments");
				try {
					for (int i = 0; i < arguments.size(); i++) {
						MemorySegment value = segment(arguments.get(i));
						incref(value); // tuple steals this additional reference
						if (integer(pyTupleSetItem, tuple, (long) i, value) != 0) {
							throw pythonError("build argument tuple");
						}
					}
					return checked(ptr(pyObjectCallObject, callable, tuple), "call Python function");
				} finally {
					decref(tuple);
				}
			} finally {
				activeCalls.removeLastOccurrence(thread);
			}
		});
	}

	@Override
	public boolean interruptCurrentCall() {
		return withGil(() -> {
			Long thread = activeCalls.peekLast();
			if (thread == null) return false;
			int changed = setAsyncException(thread, keyboardInterrupt);
			if (changed > 1) {
				setAsyncException(thread, MemorySegment.NULL);
				throw new PythonException("CPython interrupt matched multiple thread states");
			}
			return changed == 1;
		});
	}

	@Override
	public Object fromConvex(ACell value) {
		return withGil(() -> fromConvex(value, 0));
	}

	@Override
	public ACell toConvex(Object value) {
		return withGil(() -> toConvex(segment(value), 0, new HashSet<>()));
	}

	@Override
	public void retain(Object value) {
		withGil(() -> { incref(segment(value)); return null; });
	}

	@Override
	public void release(Object value) {
		if (value == null) return;
		withGil(() -> { decref(segment(value)); return null; });
	}

	private MemorySegment fromConvex(ACell value, int depth) {
		checkDepth(depth);
		if (value == null) {
			incref(none);
			return none;
		}
		if (value instanceof CVMBool b) return checked(
			ptr(pyBoolFromLong, b.booleanValue() ? 1L : 0L), "convert boolean");
		if (value instanceof AInteger i) {
			try (Arena arena = Arena.ofConfined()) {
				return checked(ptr(pyLongFromString,
					arena.allocateFrom(i.big().toString()), MemorySegment.NULL, 10),
					"convert integer");
			}
		}
		if (value instanceof CVMDouble d) return checked(
			ptr(pyFloatFromDouble, d.doubleValue()), "convert double");
		if (value instanceof convex.core.data.AString s) return unicode(s.toString());
		if (value instanceof ABlob blob) {
			byte[] bytes = blob.getBytes();
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment data = arena.allocate(bytes.length == 0 ? 1 : bytes.length);
				if (bytes.length > 0) MemorySegment.copy(bytes, 0, data,
					java.lang.foreign.ValueLayout.JAVA_BYTE, 0, bytes.length);
				return checked(ptr(pyBytesFromStringSize, data, (long) bytes.length),
					"convert blob");
			}
		}
		if (value instanceof AMap<?, ?> map) return mapToPython(map, depth + 1);
		if (value instanceof ASequence<?> sequence) return sequenceToPython(sequence, depth + 1);
		if (value instanceof ASet<?> set) return setToPython(set, depth + 1);
		throw new PythonException("Unsupported Convex value type: " + value.getClass().getName());
	}

	private MemorySegment mapToPython(AMap<?, ?> map, int depth) {
		MemorySegment dict = checked(ptr(pyDictNew), "create dictionary");
		try {
			for (long i = 0; i < map.count(); i++) {
				Map.Entry<?, ?> entry = map.entryAt(i);
				MemorySegment key = fromConvex((ACell) entry.getKey(), depth);
				MemorySegment value = fromConvex((ACell) entry.getValue(), depth);
				try {
					checkStatus(integer(pyDictSetItem, dict, key, value), "populate dictionary");
				} finally {
					decref(value);
					decref(key);
				}
			}
			return dict;
		} catch (RuntimeException e) {
			decref(dict);
			throw e;
		}
	}

	private MemorySegment sequenceToPython(ASequence<?> sequence, int depth) {
		MemorySegment list = checked(ptr(pyListNew, sequence.count()), "create list");
		try {
			for (long i = 0; i < sequence.count(); i++) {
				MemorySegment item = fromConvex((ACell) sequence.get(i), depth);
				if (integer(pyListSetItem, list, i, item) != 0) {
					throw pythonError("populate list");
				}
			}
			return list;
		} catch (RuntimeException e) {
			decref(list);
			throw e;
		}
	}

	private MemorySegment setToPython(ASet<?> set, int depth) {
		MemorySegment list = checked(ptr(pyListNew, set.count()), "create list");
		try {
			long index = 0;
			for (Object raw : set) {
				MemorySegment item = fromConvex((ACell) raw, depth);
				if (integer(pyListSetItem, list, index++, item) != 0) {
					throw pythonError("populate list");
				}
			}
			return list;
		} catch (RuntimeException e) {
			decref(list);
			throw e;
		}
	}

	@SuppressWarnings("unchecked")
	private ACell toConvex(MemorySegment value, int depth, Set<Long> active) {
		checkDepth(depth);
		if (value.address() == none.address()) return null;
		if (isInstance(value, boolType)) return CVMBool.create(longValue(value) != 0);
		if (isInstance(value, longType)) {
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment overflow = arena.allocate(JAVA_INT);
				overflow.set(JAVA_INT, 0, 0);
				long n = longNumber(pyLongAsLongLongOverflow, value, overflow);
				int over = overflow.get(JAVA_INT, 0);
				if (over == 0) return CVMLong.create(n);
				String text = objectString(value);
				return CVMBigInteger.wrap(new BigInteger(text));
			}
		}
		if (isInstance(value, floatType)) return CVMDouble.create(doubleNumber(pyFloatAsDouble, value));
		if (isInstance(value, unicodeType)) return Strings.create(unicodeText(value));
		if (isInstance(value, bytesType)) return Blob.wrap(bytes(value));
		if (isInstance(value, listType)) return collection(value, pyListSize, pyListGetItem, depth, active);
		if (isInstance(value, tupleType)) return collection(value, pyTupleSize, pyTupleGetItem, depth, active);
		if (isInstance(value, dictType)) {
			long address = enter(value, active);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment pos = arena.allocate(JAVA_LONG);
				MemorySegment keyOut = arena.allocate(ADDRESS);
				MemorySegment valueOut = arena.allocate(ADDRESS);
				pos.set(JAVA_LONG, 0, 0L);
				AMap<ACell, ACell> result = Maps.empty();
				while (integer(pyDictNext, value, pos, keyOut, valueOut) != 0) {
					ACell key = toConvex(keyOut.get(ADDRESS, 0), depth + 1, active);
					ACell item = toConvex(valueOut.get(ADDRESS, 0), depth + 1, active);
					result = result.assoc(key, item);
				}
				return result;
			} finally {
				active.remove(address);
			}
		}
		throw new PythonException("Unsupported Python value: " + objectString(value));
	}

	private ACell collection(MemorySegment value, MethodHandle sizeFn,
			MethodHandle itemFn, int depth, Set<Long> active) {
		long address = enter(value, active);
		try {
			long size = longNumber(sizeFn, value);
			if (size < 0 || size > Integer.MAX_VALUE) throw pythonError("read collection size");
			ACell[] items = new ACell[(int) size];
			for (int i = 0; i < items.length; i++) {
				MemorySegment item = checked(ptr(itemFn, value, (long) i), "read collection item");
				items[i] = toConvex(item, depth + 1, active);
			}
			return Vectors.create(items);
		} finally {
			active.remove(address);
		}
	}

	private static long enter(MemorySegment value, Set<Long> active) {
		long address = value.address();
		if (!active.add(address)) throw new PythonException(
			"Cyclic Python containers cannot be converted to Convex values");
		return address;
	}

	private void initialise() {
		synchronized (INIT_LOCK) {
			if (integer(pyIsInitialized) != 0) return;
			invoke(pyInitialize);
			if (integer(pyIsInitialized) == 0) {
				throw new PythonException("Py_Initialize did not initialise CPython");
			}
			// Py_Initialize leaves this thread holding the GIL. Release it so
			// venue virtual threads can enter through PyGILState_Ensure.
			checked(ptr(pyEvalSaveThread), "release initial Python thread state");
		}
	}

	private <T> T withGil(Supplier<T> action) {
		int state = integer(pyGilEnsure);
		try {
			return action.get();
		} finally {
			invoke(pyGilRelease, state);
		}
	}

	private long pythonThreadIdent() {
		long thread = WINDOWS
			? Integer.toUnsignedLong(integer(pyThreadGetIdent))
			: longNumber(pyThreadGetIdent);
		if (thread == 0) throw new PythonException("CPython returned no current thread identity");
		return thread;
	}

	private int setAsyncException(long thread, MemorySegment exception) {
		return WINDOWS
			? integer(pyThreadStateSetAsyncExc, (int) thread, exception)
			: integer(pyThreadStateSetAsyncExc, thread, exception);
	}

	private MemorySegment compile(String source, String filename, int mode) {
		try (Arena arena = Arena.ofConfined()) {
			return checked(ptr(pyCompileString, arena.allocateFrom(source),
				arena.allocateFrom(filename), mode), "compile " + filename);
		}
	}

	private MemorySegment unicode(String value) {
		byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment data = arena.allocateFrom(value);
			return checked(ptr(pyUnicodeFromStringSize, data, (long) utf8.length),
				"convert string");
		}
	}

	private String unicodeText(MemorySegment value) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment size = arena.allocate(JAVA_LONG);
			MemorySegment data = checked(ptr(pyUnicodeAsUtf8Size, value, size), "read string");
			long length = size.get(JAVA_LONG, 0);
			return new String(data.reinterpret(length).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
				StandardCharsets.UTF_8);
		}
	}

	private byte[] bytes(MemorySegment value) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment dataOut = arena.allocate(ADDRESS);
			MemorySegment sizeOut = arena.allocate(JAVA_LONG);
			checkStatus(integer(pyBytesAsStringSize, value, dataOut, sizeOut), "read bytes");
			long length = sizeOut.get(JAVA_LONG, 0);
			return dataOut.get(ADDRESS, 0).reinterpret(length)
				.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
		}
	}

	private boolean isInstance(MemorySegment value, MemorySegment type) {
		int result = integer(pyObjectIsInstance, value, type);
		if (result < 0) throw pythonError("check Python type");
		return result != 0;
	}

	private long longValue(MemorySegment value) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment overflow = arena.allocate(JAVA_INT);
			overflow.set(JAVA_INT, 0, 0);
			long result = longNumber(pyLongAsLongLongOverflow, value, overflow);
			if (overflow.get(JAVA_INT, 0) != 0) throw new PythonException("Boolean conversion overflow");
			return result;
		}
	}

	private String objectString(MemorySegment value) {
		MemorySegment text = checked(ptr(pyObjectStr, value), "format Python value");
		try {
			return unicodeText(text);
		} finally {
			decref(text);
		}
	}

	private PythonException pythonError(String action) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment typeOut = arena.allocate(ADDRESS);
			MemorySegment valueOut = arena.allocate(ADDRESS);
			MemorySegment tracebackOut = arena.allocate(ADDRESS);
			typeOut.set(ADDRESS, 0, MemorySegment.NULL);
			valueOut.set(ADDRESS, 0, MemorySegment.NULL);
			tracebackOut.set(ADDRESS, 0, MemorySegment.NULL);
			invoke(pyErrFetch, typeOut, valueOut, tracebackOut);
			invoke(pyErrNormalize, typeOut, valueOut, tracebackOut);
			MemorySegment type = typeOut.get(ADDRESS, 0);
			MemorySegment value = valueOut.get(ADDRESS, 0);
			MemorySegment traceback = tracebackOut.get(ADDRESS, 0);
			try {
				String detail = !isNull(value) ? objectString(value) : "";
				if (detail.isBlank() && !isNull(type)) detail = objectString(type);
				if (detail.isBlank()) detail = "unknown Python error";
				return new PythonException(action + ": " + detail);
			} finally {
				decrefNullable(traceback);
				decrefNullable(value);
				decrefNullable(type);
			}
		}
	}

	private void checkStatus(int status, String action) {
		if (status != 0) throw pythonError(action);
	}

	private MemorySegment checked(MemorySegment value, String action) {
		if (isNull(value)) throw pythonError(action);
		return value;
	}

	private void incref(MemorySegment value) {
		invoke(pyIncRef, value);
	}

	private void decref(MemorySegment value) {
		invoke(pyDecRef, value);
	}

	private void decrefNullable(MemorySegment value) {
		if (!isNull(value)) decref(value);
	}

	private static boolean isNull(MemorySegment value) {
		return value == null || value.address() == 0;
	}

	private static MemorySegment segment(Object value) {
		if (!(value instanceof MemorySegment segment) || isNull(segment)) {
			throw new IllegalArgumentException("Invalid Python reference");
		}
		return segment;
	}

	private static void checkDepth(int depth) {
		if (depth > MAX_CONVERSION_DEPTH) {
			throw new PythonException("Python/Convex value nesting exceeds " + MAX_CONVERSION_DEPTH);
		}
	}

	private MethodHandle fn(String name, MemoryLayout result, MemoryLayout... args) {
		FunctionDescriptor descriptor = result == null
			? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
		return linker.downcallHandle(symbol(name), descriptor);
	}

	private MemorySegment symbol(String name) {
		return symbols.find(name).orElseThrow(() ->
			new PythonException("CPython library is missing symbol " + name));
	}

	private Object invoke(MethodHandle handle, Object... args) {
		try {
			return handle.invokeWithArguments(args);
		} catch (PythonException e) {
			throw e;
		} catch (Throwable e) {
			throw new PythonException("CPython FFM call failed", e);
		}
	}

	private MemorySegment ptr(MethodHandle handle, Object... args) {
		return (MemorySegment) invoke(handle, args);
	}

	private int integer(MethodHandle handle, Object... args) {
		return (int) invoke(handle, args);
	}

	private long longNumber(MethodHandle handle, Object... args) {
		return (long) invoke(handle, args);
	}

	private double doubleNumber(MethodHandle handle, Object... args) {
		return (double) invoke(handle, args);
	}

	private static String cString(MemorySegment pointer) {
		if (isNull(pointer)) return "unknown";
		return pointer.reinterpret(4096).getString(0);
	}

	private static Library findLibrary(String configured) {
		List<String> attempts = new ArrayList<>();
		String explicit = configured;
		if (explicit == null || explicit.isBlank()) explicit = System.getProperty("covia.python.library");
		if (explicit == null || explicit.isBlank()) explicit = System.getenv("COVIA_PYTHON_LIBRARY");
		if (explicit != null && !explicit.isBlank()) {
			return loadLibrary(explicit, attempts);
		}

		for (Path candidate : candidatePaths()) {
			if (!Files.isRegularFile(candidate)) continue;
			try {
				return new Library(SymbolLookup.libraryLookup(candidate, Arena.global()),
					candidate.toAbsolutePath().toString());
			} catch (RuntimeException e) {
				attempts.add(candidate + " (" + e.getMessage() + ")");
			}
		}
		for (String name : libraryNames()) {
			try {
				return new Library(SymbolLookup.libraryLookup(name, Arena.global()), name);
			} catch (RuntimeException e) {
				attempts.add(name);
			}
		}
		throw new PythonException("No compatible CPython shared library found; set "
			+ "-Dcovia.python.library=<path> or COVIA_PYTHON_LIBRARY"
			+ (attempts.isEmpty() ? "" : " (tried " + String.join(", ", attempts) + ")"));
	}

	private static Library loadLibrary(String configured, List<String> attempts) {
		Path path = Path.of(configured);
		try {
			if (Files.isRegularFile(path)) {
				return new Library(SymbolLookup.libraryLookup(path.toAbsolutePath(), Arena.global()),
					path.toAbsolutePath().toString());
			}
			return new Library(SymbolLookup.libraryLookup(configured, Arena.global()), configured);
		} catch (RuntimeException e) {
			attempts.add(configured);
			throw new PythonException("Cannot load configured CPython library '"
				+ configured + "': " + e.getMessage(), e);
		}
	}

	private static List<Path> candidatePaths() {
		Set<Path> dirs = new java.util.LinkedHashSet<>();
		for (String env : List.of("PYTHONHOME", "CONDA_PREFIX", "VIRTUAL_ENV")) {
			String value = System.getenv(env);
			if (value != null && !value.isBlank()) {
				Path base = Path.of(value);
				dirs.add(base);
				dirs.add(base.resolve("lib"));
				dirs.add(base.resolve("libs"));
				dirs.add(base.resolve("bin"));
			}
		}
		for (String property : List.of(System.getProperty("java.library.path", ""),
			System.getenv().getOrDefault("PATH", ""))) {
			for (String dir : property.split(java.io.File.pathSeparator)) {
				if (!dir.isBlank()) dirs.add(Path.of(dir));
			}
		}
		dirs.add(Path.of("/usr/lib"));
		dirs.add(Path.of("/usr/local/lib"));
		List<Path> result = new ArrayList<>();
		for (Path dir : dirs) for (String file : libraryFiles()) result.add(dir.resolve(file));
		return result;
	}

	private static List<String> libraryNames() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> names = new ArrayList<>();
		for (String version : List.of("3.14", "3.13", "3.12", "3.11", "3.10")) {
			if (os.contains("win")) names.add("python" + version.replace(".", ""));
			else names.add("python" + version);
		}
		return names;
	}

	private static List<String> libraryFiles() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> files = new ArrayList<>();
		for (String version : List.of("3.14", "3.13", "3.12", "3.11", "3.10")) {
			if (os.contains("win")) files.add("python" + version.replace(".", "") + ".dll");
			else if (os.contains("mac")) files.add("libpython" + version + ".dylib");
			else {
				files.add("libpython" + version + ".so");
				files.add("libpython" + version + ".so.1.0");
			}
		}
		return files;
	}
}
