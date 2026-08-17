package covia.adapter.claudecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * The settings of one Claude Code process, as an immutable, validated map of
 * option name to value.
 *
 * <p>Options are layered — the adapter's {@code defaults}, then the project's
 * settings, then (for the keys a caller may set) the call's own input — and
 * the merged result becomes the {@code claude} command line. Two runs whose
 * merged options are {@link #equals equal} can share a live process; a
 * differing request restarts the process ({@code --resume} keeps the
 * conversation) with the new flags.</p>
 *
 * <p>Every option maps to one CLI flag; the value shapes are those the flag
 * accepts (see {@code claude --help}). Only {@link #CALL_KEYS} may be set per
 * call: process-environment settings ({@code addDirs}, {@code mcpConfig},
 * {@code settings}, {@code env}) belong to the operator.</p>
 */
public final class RunOptions {

	public static final AString MODEL = Strings.intern("model");
	public static final AString FALLBACK_MODEL = Strings.intern("fallbackModel");
	public static final AString EFFORT = Strings.intern("effort");
	public static final AString PERMISSION_MODE = Strings.intern("permissionMode");
	public static final AString ALLOWED_TOOLS = Strings.intern("allowedTools");
	public static final AString DISALLOWED_TOOLS = Strings.intern("disallowedTools");
	public static final AString TOOLS = Strings.intern("tools");
	public static final AString MAX_TURNS = Strings.intern("maxTurns");
	public static final AString MAX_BUDGET_USD = Strings.intern("maxBudgetUsd");
	public static final AString APPEND_SYSTEM_PROMPT = Strings.intern("appendSystemPrompt");
	public static final AString SYSTEM_PROMPT = Strings.intern("systemPrompt");
	public static final AString JSON_SCHEMA = Strings.intern("jsonSchema");
	public static final AString AGENT = Strings.intern("agent");
	public static final AString ADD_DIRS = Strings.intern("addDirs");
	public static final AString MCP_CONFIG = Strings.intern("mcpConfig");
	public static final AString STRICT_MCP_CONFIG = Strings.intern("strictMcpConfig");
	public static final AString SETTINGS = Strings.intern("settings");
	public static final AString KEEP_ALIVE = Strings.intern("keepAlive");
	public static final AString ENV = Strings.intern("env");

	/** The permission mode that bypasses every tool check; a call may only choose it if the project already has it. */
	public static final String BYPASS_PERMISSIONS = "bypassPermissions";

	static final Set<String> PERMISSION_MODES = Set.of(
		"acceptEdits", "auto", BYPASS_PERMISSIONS, "manual", "dontAsk", "plan", "default");
	static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

	/** Every option an operator may set (adapter {@code defaults} or a project). */
	public static final Set<AString> ALL_KEYS = Set.of(
		MODEL, FALLBACK_MODEL, EFFORT, PERMISSION_MODE, ALLOWED_TOOLS, DISALLOWED_TOOLS, TOOLS,
		MAX_TURNS, MAX_BUDGET_USD, APPEND_SYSTEM_PROMPT, SYSTEM_PROMPT, JSON_SCHEMA, AGENT,
		ADD_DIRS, MCP_CONFIG, STRICT_MCP_CONFIG, SETTINGS, KEEP_ALIVE, ENV);

	/** The options a caller may set on {@code run} / {@code session}. */
	public static final Set<AString> CALL_KEYS = Set.of(
		MODEL, FALLBACK_MODEL, EFFORT, PERMISSION_MODE, ALLOWED_TOOLS, DISALLOWED_TOOLS, TOOLS,
		MAX_TURNS, MAX_BUDGET_USD, APPEND_SYSTEM_PROMPT, SYSTEM_PROMPT, JSON_SCHEMA, AGENT, KEEP_ALIVE);

	private static final Set<AString> STRING_KEYS = Set.of(
		MODEL, FALLBACK_MODEL, EFFORT, PERMISSION_MODE, APPEND_SYSTEM_PROMPT, SYSTEM_PROMPT, AGENT);
	private static final Set<AString> LIST_KEYS = Set.of(ALLOWED_TOOLS, DISALLOWED_TOOLS, TOOLS, ADD_DIRS);

	public static final RunOptions EMPTY = new RunOptions(Maps.empty());

	private final AMap<AString, ACell> values;

	private RunOptions(AMap<AString, ACell> values) {
		this.values = values;
	}

	/**
	 * Parses an options object.
	 *
	 * @param cell    the object (null = no options)
	 * @param allowed the keys permitted at this layer
	 * @param where   config/input location for messages
	 * @param strict  reject unknown keys (always true for call input)
	 */
	public static RunOptions parse(ACell cell, Set<AString> allowed, String where, boolean strict) {
		if (cell == null) return EMPTY;
		AMap<AString, ACell> m = RT.castMap(cell);
		if (m == null) throw new IllegalArgumentException(where + " must be an object of Claude Code options");
		AMap<AString, ACell> out = Maps.empty();
		for (long i = 0; i < m.count(); i++) {
			var e = m.entryAt(i);
			ACell k = e.getKey();
			ACell v = e.getValue();
			if (!(k instanceof AString key) || !ALL_KEYS.contains(key)) {
				if (strict) throw new IllegalArgumentException(where + ": unknown option " + k
					+ " (known: " + String.join(", ", names(allowed)) + ")");
				continue;
			}
			if (!allowed.contains(key)) {
				throw new IllegalArgumentException(where + ": option " + key
					+ " cannot be set here (it is an operator setting of the adapter or project)");
			}
			if (v == null) continue;
			out = out.assoc(key, validate(key, v, where + "." + key));
		}
		return out.isEmpty() ? EMPTY : new RunOptions(out);
	}

	private static List<String> names(Set<AString> keys) {
		List<String> names = new ArrayList<>();
		for (AString k : keys) names.add(k.toString());
		Collections.sort(names);
		return names;
	}

	private static ACell validate(AString key, ACell v, String where) {
		if (STRING_KEYS.contains(key)) {
			if (!(v instanceof AString s) || s.isEmpty()) {
				throw new IllegalArgumentException(where + " must be a non-empty string");
			}
			String str = s.toString();
			if (PERMISSION_MODE.equals(key) && !PERMISSION_MODES.contains(str)) {
				throw new IllegalArgumentException(where + " must be one of " + sorted(PERMISSION_MODES) + ": " + str);
			}
			if (EFFORT.equals(key) && !EFFORTS.contains(str)) {
				throw new IllegalArgumentException(where + " must be one of " + sorted(EFFORTS) + ": " + str);
			}
			return v;
		}
		if (LIST_KEYS.contains(key)) {
			AVector<ACell> list = RT.ensureVector(v);
			if (list == null) {
				// A single string is accepted for convenience.
				if (v instanceof AString s) return Vectors.of(s);
				throw new IllegalArgumentException(where + " must be an array of strings");
			}
			for (long i = 0; i < list.count(); i++) {
				if (!(list.get(i) instanceof AString)) {
					throw new IllegalArgumentException(where + " must be an array of strings");
				}
			}
			return list;
		}
		if (MAX_TURNS.equals(key)) {
			CVMLong n = RT.ensureLong(v);
			if (n == null || n.longValue() < 1) throw new IllegalArgumentException(where + " must be a positive integer");
			return n;
		}
		if (MAX_BUDGET_USD.equals(key)) {
			CVMDouble d = RT.castDouble(v);
			if (d == null || !(d.doubleValue() > 0)) throw new IllegalArgumentException(where + " must be a positive number");
			return d;
		}
		if (STRICT_MCP_CONFIG.equals(key) || KEEP_ALIVE.equals(key)) {
			if (!(v instanceof CVMBool)) throw new IllegalArgumentException(where + " must be a boolean");
			return v;
		}
		if (JSON_SCHEMA.equals(key)) {
			if (v instanceof AString s) {
				try {
					ACell parsed = JSON.parse(s);
					if (!(parsed instanceof AMap)) throw new IllegalArgumentException();
					return parsed;
				} catch (RuntimeException e) {
					throw new IllegalArgumentException(where + " must be a JSON Schema object (or its JSON text)");
				}
			}
			if (!(v instanceof AMap)) throw new IllegalArgumentException(where + " must be a JSON Schema object");
			return v;
		}
		if (MCP_CONFIG.equals(key) || SETTINGS.equals(key)) {
			// A JSON object (written to a file for the CLI) or a string (a file path, passed as-is).
			if (!(v instanceof AMap) && !(v instanceof AString)) {
				throw new IllegalArgumentException(where + " must be a JSON object or a file path");
			}
			return v;
		}
		if (ENV.equals(key)) {
			AMap<AString, ACell> env = RT.castMap(v);
			if (env == null) throw new IllegalArgumentException(where + " must be an object of variable name -> value");
			for (long i = 0; i < env.count(); i++) {
				var e = env.entryAt(i);
				if (!(e.getKey() instanceof AString) || !(e.getValue() instanceof AString)) {
					throw new IllegalArgumentException(where + " values must be strings (a literal or an s/NAME secret reference)");
				}
			}
			return env;
		}
		throw new IllegalArgumentException(where + ": unknown option");
	}

	private static List<String> sorted(Set<String> set) {
		List<String> l = new ArrayList<>(set);
		Collections.sort(l);
		return l;
	}

	/** This layer overlaid by {@code over}: every option {@code over} sets replaces this one's. */
	public RunOptions overlay(RunOptions over) {
		if (over == null || over.values.isEmpty()) return this;
		AMap<AString, ACell> out = values;
		for (long i = 0; i < over.values.count(); i++) {
			var e = over.values.entryAt(i);
			out = out.assoc(e.getKey(), e.getValue());
		}
		return new RunOptions(out);
	}

	public ACell get(AString key) {
		return values.get(key);
	}

	public String string(AString key) {
		ACell v = values.get(key);
		return (v instanceof AString s) ? s.toString() : null;
	}

	public boolean bool(AString key, boolean dflt) {
		ACell v = values.get(key);
		return (v instanceof CVMBool b) ? b.booleanValue() : dflt;
	}

	/** Whether the process stays alive after a turn (default: yes). */
	public boolean keepAlive() {
		return bool(KEEP_ALIVE, true);
	}

	public String permissionMode() {
		return string(PERMISSION_MODE);
	}

	/** The {@code env} option as a plain map (empty when unset). */
	public Map<String, String> env() {
		Map<String, String> out = new LinkedHashMap<>();
		AMap<AString, ACell> env = RT.castMap(values.get(ENV));
		if (env == null) return out;
		for (long i = 0; i < env.count(); i++) {
			var e = env.entryAt(i);
			out.put(e.getKey().toString(), e.getValue().toString());
		}
		return out;
	}

	/** The options with anything secret-bearing ({@code env}) removed — what {@code projects} shows. */
	public AMap<AString, ACell> publicView() {
		return values.dissoc(ENV);
	}

	public AMap<AString, ACell> values() {
		return values;
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	/**
	 * The CLI flags for these options, excluding {@code env}/{@code keepAlive}
	 * (process environment and pool policy, not flags). JSON-valued
	 * {@code mcpConfig}/{@code settings} are passed through {@code files}: the
	 * caller supplies a function that materialises JSON text as a file path.
	 */
	public List<String> flags(java.util.function.Function<String, String> files) {
		List<String> args = new ArrayList<>();
		flag(args, "--model", MODEL);
		flag(args, "--fallback-model", FALLBACK_MODEL);
		flag(args, "--effort", EFFORT);
		flag(args, "--permission-mode", PERMISSION_MODE);
		flag(args, "--agent", AGENT);
		listFlag(args, "--allowedTools", ALLOWED_TOOLS);
		listFlag(args, "--disallowedTools", DISALLOWED_TOOLS);
		listFlag(args, "--tools", TOOLS);
		listFlag(args, "--add-dir", ADD_DIRS);
		ACell turns = values.get(MAX_TURNS);
		if (turns != null) { args.add("--max-turns"); args.add(String.valueOf(((CVMLong) turns).longValue())); }
		ACell budget = values.get(MAX_BUDGET_USD);
		if (budget != null) { args.add("--max-budget-usd"); args.add(String.valueOf(((CVMDouble) budget).doubleValue())); }
		flag(args, "--append-system-prompt", APPEND_SYSTEM_PROMPT);
		flag(args, "--system-prompt", SYSTEM_PROMPT);
		ACell schema = values.get(JSON_SCHEMA);
		if (schema != null) { args.add("--json-schema"); args.add(JSON.print(schema).toString()); }
		fileFlag(args, "--mcp-config", MCP_CONFIG, files);
		if (bool(STRICT_MCP_CONFIG, false)) args.add("--strict-mcp-config");
		fileFlag(args, "--settings", SETTINGS, files);
		return args;
	}

	private void flag(List<String> args, String flag, AString key) {
		String v = string(key);
		if (v != null) { args.add(flag); args.add(v); }
	}

	private void listFlag(List<String> args, String flag, AString key) {
		AVector<ACell> list = RT.ensureVector(values.get(key));
		if (list == null || list.isEmpty()) return;
		args.add(flag);
		for (long i = 0; i < list.count(); i++) args.add(list.get(i).toString());
	}

	private void fileFlag(List<String> args, String flag, AString key,
			java.util.function.Function<String, String> files) {
		ACell v = values.get(key);
		if (v == null) return;
		args.add(flag);
		args.add((v instanceof AString s) ? s.toString() : files.apply(JSON.print(v).toString()));
	}

	@Override
	public boolean equals(Object o) {
		return (o instanceof RunOptions r) && values.equals(r.values);
	}

	@Override
	public int hashCode() {
		return values.hashCode();
	}

	@Override
	public String toString() {
		return "RunOptions" + JSON.print(publicView());
	}
}
