package covia.api;

import convex.core.data.StringShort;
import convex.core.data.Strings;

/**
 * Static class for constant field name values used in Covia Grid API
 */
public class Fields {
	// Job related
	public static final StringShort CREATED=Strings.intern("created");
	public static final StringShort UPDATED = Strings.intern("updated");
	/** Non-fatal advisories attached to an operation result (a vector of message
	 *  strings, e.g. config sanity warnings). Absent when there are none. */
	public static final StringShort WARNINGS = Strings.intern("warnings");
	/** Configured operation tools that could not be offered in the current
	 *  capability context. Vector entries have {@code operation} and
	 *  {@code reason} fields. */
	public static final StringShort UNAVAILABLE_TOOLS = Strings.intern("unavailableTools");
	public static final StringShort REASON = Strings.intern("reason");
	public static final StringShort INPUT = Strings.intern("input");
	public static final StringShort OUTPUT = Strings.intern("output");
	/** Optional destination for structurally handing off an operation result. */
	public static final StringShort OUTPUT_PATH = Strings.intern("outputPath");
	/** Recursive CVM storage size of a value, used by small result receipts. */
	public static final StringShort BYTES = Strings.intern("bytes");
	public static final StringShort RESULT = Strings.intern("result");
	public static final StringShort OPERATION = Strings.intern("operation");
	/** Operation may execute through result-oriented/internal paths without a
	 * durable Job record. Only an explicit true opts in. */
	public static final StringShort READ_ONLY = Strings.intern("readOnly");
	/** Whether an operation may use internal/result-only execution. An explicit
	 * false forces a durable Job even when the caller uses run/invokeInternal. */
	public static final StringShort INTERNAL = Strings.intern("internal");
	public static final StringShort DEFAULT = Strings.intern("default");
	public static final StringShort MESSAGE = Strings.intern("message");
	/** Stable caller-supplied message identity, used for idempotent A2A intake. */
	public static final StringShort MESSAGE_ID = Strings.intern("messageId");
	public static final StringShort DELAY = Strings.intern("delay");
	public static final StringShort ID = Strings.intern("id");
	public static final StringShort STATUS = Strings.intern("status");	
	public static final StringShort OP = Strings.intern("op");

	/** Token usage map {@code {input, output, total}} — reported by LLM ops on
	 *  assistant messages, aggregated per cycle onto agent timeline entries,
	 *  session {@code meta.tokens} and job records (#217). Counts are
	 *  provider-measured, never estimated; absence means "not measured". */
	public static final StringShort TOKENS = Strings.intern("tokens");

	// List / pagination related
	public static final StringShort ITEMS = Strings.intern("items");
	public static final StringShort TOTAL = Strings.intern("total");
	/** Prompt-cache tokens served at the discounted read rate (provider-reported). */
	public static final StringShort CACHE_READ = Strings.intern("cacheRead");
	/** Prompt-cache tokens written at the write premium (provider-reported). */
	public static final StringShort CACHE_WRITE = Strings.intern("cacheWrite");
	public static final StringShort OFFSET = Strings.intern("offset");
	public static final StringShort LIMIT = Strings.intern("limit");
	
	// Content related
	public static final StringShort NAME =  Strings.intern("name");
	public static final StringShort DESCRIPTION = Strings.intern("description");
	public static final StringShort CONTENT = Strings.intern("content");
	/** Canonical reference field used by content descriptors and reference-addressed APIs. */
	public static final StringShort REF = Strings.intern("ref");
	public static final StringShort CONTENT_TYPE = Strings.intern("contentType");
	public static final StringShort FILE_NAME = Strings.intern("fileName");
	public static final StringShort SHA256 =  Strings.intern("sha256");
	
	// Misc / general purpose
	public static final StringShort OK = Strings.intern("OK");
	public static final StringShort HIDDEN = Strings.intern("HIDDEN");
	public static final StringShort TS = Strings.intern("ts");
	
	// Orchestration related
	public static final StringShort STEPS = Strings.intern("steps");
	public static final StringShort CONST = Strings.intern("const");
	public static final StringShort CONCAT = Strings.intern("concat");
	/** Step-level ordered map specification. Its {@code in} binding must resolve
	 *  to an {@code ADataStructure}; each element is exposed through
	 *  {@code ["item", ...]} and its ordinal through {@code ["index"]}. */
	public static final StringShort FOREACH = Strings.intern("foreach");
	public static final StringShort IN = Strings.intern("in");
	public static final StringShort ITEM = Strings.intern("item");
	public static final StringShort INDEX = Strings.intern("index");
	public static final StringShort MAX_CONCURRENCY = Strings.intern("maxConcurrency");
	/** Orchestration binding head building an array whose ELEMENTS are computed.
	 *  A vector is otherwise always an expression, so this is the only way to
	 *  produce an array that references prior steps ({@code ["const", …]} freezes
	 *  its whole subtree and leaves inner bindings inert). */
	public static final StringShort ARRAY = Strings.intern("array");
	
	// HTTP related
	public static final StringShort HEADERS = Strings.intern("headers");
	public static final StringShort QUERY_PARAMS = Strings.intern("queryParams");
	public static final StringShort BODY = Strings.intern("body");
	public static final StringShort METHOD = Strings.intern("method");
	public static final StringShort BEARER_SECRET = Strings.intern("bearerSecret");
	/** Literal bearer credential supplied directly to an outbound protocol
	 * adapter. Operation metadata must declare this as a secret field so the
	 * value never persists in a Job record. */
	public static final StringShort BEARER_TOKEN = Strings.intern("bearerToken");
	public static final StringShort INLINE = Strings.intern("inline");
	
	public static final StringShort PORT = Strings.intern("port");
	public static final StringShort HOSTNAME = Strings.intern("hostname");;
	public static final StringShort DID = Strings.intern("did");
	public static final StringShort VENUES = Strings.intern("venues");
	public static final StringShort ERROR = Strings.intern("error");
	
	// MCP stuff
	public static final StringShort ADAPTER = Strings.intern("adapter");
	public static final StringShort INPUT_SCHEMA = Strings.intern("inputSchema");
	public static final StringShort TITLE = Strings.intern("title");
	public static final StringShort MCP = Strings.intern("mcp");
	public static final StringShort SERVER = Strings.intern("server");
	public static final StringShort ARGUMENTS = Strings.intern("arguments");
	public static final StringShort REMOTE_TOOL_NAME = Strings.intern("remoteToolName");
	public static final StringShort TOOL_NAME = Strings.intern("toolName");
	public static final StringShort TOOL = Strings.intern("tool");
	public static final StringShort TOOLS = Strings.intern("tools");
	public static final StringShort IS_ERROR = Strings.intern("isError");
	public static final StringShort TYPE = Strings.intern("type");
	public static final StringShort TEXT = Strings.intern("text");
	public static final StringShort STRUCTURED_CONTENT = Strings.intern("structuredContent");
	public static final StringShort PARAMS = Strings.intern("params");
	public static final StringShort TOKEN = Strings.intern("token");
	public static final StringShort UCANS = Strings.intern("ucans");
	public static final StringShort OBJECT = Strings.intern("object");
	public static final StringShort ANY = Strings.intern("any");
	public static final StringShort PROPERTIES = Strings.intern("properties");
	public static final StringShort ADDITIONAL_PROPERTIES = Strings.intern("additionalProperties");
	
	// A2A stuff — wire format goes through spec POJOs + gson; only keep
	// constants for fields that appear on both sides of the wire boundary
	// (in Covia-side storage/config as well as in A2A JSON).
	public static final StringShort A2A = Strings.intern("a2a");
	public static final StringShort URL = Strings.intern("url");
	public static final StringShort OUTPUT_SCHEMA = Strings.intern("outputSchema");
	/** When the A2A adapter mirrors a remote Task into a local Job, this field
	 *  stores the remote Task ID on the local Job's data so clients can
	 *  correlate the two. */
	public static final StringShort REMOTE_TASK_ID = Strings.intern("remoteTaskId");

	// Asset related
	public static final StringShort DEFINITION = Strings.intern("definition");
	public static final StringShort METADATA = Strings.intern("metadata");
	public static final StringShort STORED = Strings.intern("stored");

	// JSON Schema
	public static final StringShort REQUIRED = Strings.intern("required");
	public static final StringShort DEFS = Strings.intern("$defs");
	public static final StringShort DEFINITIONS = Strings.intern("definitions");

	// Venue status
	public static final StringShort VERSION = Strings.intern("version");
	/** UCAN JWT profile emitted by this venue. Older tokens without an explicit
	 * profile marker may still be accepted through the compatibility boundary. */
	public static final StringShort UCAN_PROFILE = Strings.intern("ucanProfile");

	// Lattice navigation
	public static final StringShort PATH = Strings.intern("path");
	public static final StringShort VALUE = Strings.intern("value");

	// Agent related
	public static final StringShort AGENT_ID = Strings.intern("agentId");
	public static final StringShort CONFIG = Strings.intern("config");
	public static final StringShort DELIVERED = Strings.intern("delivered");
	public static final StringShort MESSAGES = Strings.intern("messages");
	public static final StringShort TASKS = Strings.intern("tasks");
	public static final StringShort PENDING = Strings.intern("pending");
	public static final StringShort TASK_RESULTS = Strings.intern("taskResults");
	/** A cycle's exchange with the model — {context, tools, inferences} — on
	 *  the transition output; spread onto the timeline entry (#392). */
	public static final StringShort CYCLE = Strings.intern("cycle");
	/** A frame's standing context: the head and live surface as first
	 *  rendered — the prompt minus the conversation. */
	public static final StringShort CONTEXT = Strings.intern("context");
	/** One record per model call: {ts, ms, op, model?, sent?, tools?, reply | error, calls?}. */
	public static final StringShort INFERENCES = Strings.intern("inferences");
	/** The messages an inference sent for the first time in its cycle. */
	public static final StringShort SENT = Strings.intern("sent");
	/** The model's reply, verbatim. */
	public static final StringShort REPLY = Strings.intern("reply");
	/** The tool batch an inference requested: [{id, name, ms, result, isError?, frame?}]. */
	public static final StringShort CALLS = Strings.intern("calls");
	/** A subgoal call's child frame record: {context, tools, inferences}. */
	public static final StringShort FRAME = Strings.intern("frame");
	/** Wall-clock milliseconds. */
	public static final StringShort MS = Strings.intern("ms");
	public static final StringShort JOB_ID = Strings.intern("jobId");
	public static final StringShort SNAPSHOT = Strings.intern("snapshot");
	public static final StringShort AUTO_WAKE = Strings.intern("autoWake");
	public static final StringShort WAIT = Strings.intern("wait");
	public static final StringShort FORCE = Strings.intern("force");
	public static final StringShort TIMEOUT = Strings.intern("timeout");
	public static final StringShort REMOVE = Strings.intern("remove");
	public static final StringShort REMOVED = Strings.intern("removed");
	public static final StringShort OVERWRITE = Strings.intern("overwrite");
	public static final StringShort INCLUDE_TERMINATED = Strings.intern("includeTerminated");
	public static final StringShort TASK_ID = Strings.intern("taskId");
	public static final StringShort CANCELLED = Strings.intern("cancelled");
	public static final StringShort DELETED = Strings.intern("deleted");
	public static final StringShort RESPONSE_SCHEMA = Strings.intern("responseSchema");
	/** Requester opt-in: enforce {@link #RESPONSE_SCHEMA} at task completion (#376). */
	public static final StringShort STRICT = Strings.intern("strict");
	public static final StringShort T = Strings.intern("t");

	// Session related
	public static final StringShort SESSION_ID = Strings.intern("sessionId");
	public static final StringShort LOADS = Strings.intern("loads");
	public static final StringShort PRIVATE = Strings.intern("private");
	public static final StringShort HISTORY = Strings.intern("history");
	public static final StringShort PARTIES = Strings.intern("parties");
	/** Session metadata count and transition-output turn vector. In transition
	 *  output, contains non-terminal assistant/tool turns produced before the
	 *  final {@link #RESPONSE}; the framework inserts them between user input
	 *  and the terminal assistant response. */
	public static final StringShort TURNS = Strings.intern("turns");
	public static final StringShort C = Strings.intern("c");

	// Scheduler fields (B8.8) — per-thread on session/task records
	public static final StringShort WAKE_TIME = Strings.intern("wakeTime");
	public static final StringShort YIELD_COUNT = Strings.intern("yieldCount");
	/** Transition input field (S3b): the picked session record map
	 *  {id, parties, meta, c, history, pending} for the cycle's session. */
	public static final StringShort SESSION = Strings.intern("session");

	// Lean transition contract (Sub-stage 3)
	public static final StringShort NEW_INPUT = Strings.intern("newInput");
	public static final StringShort RESPONSE = Strings.intern("response");
	public static final StringShort TASK_COMPLETE = Strings.intern("taskComplete");
	/** Transition output field: updated session frame stack. When present,
	 *  mergeRunResult CAS-replaces session.frames with this value (the adapter
	 *  owns its own assistant/tool turns). When absent, the framework appends
	 *  the response as an assistant turn to frames[0].conversation. */
	public static final StringShort FRAMES = Strings.intern("frames");

	// Identity / authorization
	public static final StringShort CALLER = Strings.intern("caller");
	/** The principal that actually performed an invocation, when that is not the
	 *  owning {@link #CALLER} — an agent sub-principal ({@code <owner>:g:<id>}).
	 *  Absent means the owner acted directly, so ordinary job records are
	 *  unchanged and no migration is implied. Attribution only: ownership,
	 *  access control and quota all key on {@code caller}. */
	public static final StringShort ACTOR = Strings.intern("actor");
	public static final StringShort ROLES = Strings.intern("roles");
	public static final StringShort REQUIRES = Strings.intern("requires");
	public static final StringShort EMAIL = Strings.intern("email");
	public static final StringShort PROVIDER = Strings.intern("provider");
	public static final StringShort PROVIDER_SUB = Strings.intern("providerSub");
	public static final StringShort SUB = Strings.intern("sub");
	public static final StringShort KID = Strings.intern("kid");
	/** Venue-owned public authenticator registry on a named user record. */
	public static final StringShort AUTHENTICATION_KEYS = Strings.intern("authenticationKeys");
	public static final StringShort KEY = Strings.intern("key");
	public static final StringShort LABEL = Strings.intern("label");
	public static final StringShort ADDED_AT = Strings.intern("addedAt");
	public static final StringShort ADDED_BY = Strings.intern("addedBy");
	public static final StringShort REVOKED_AT = Strings.intern("revokedAt");
	public static final StringShort REVOKED_BY = Strings.intern("revokedBy");

	// Convex related
	public static final StringShort PEER = Strings.intern("peer");
	public static final StringShort ADDRESS = Strings.intern("address");
	public static final StringShort SOURCE = Strings.intern("source");
	public static final StringShort SEED = Strings.intern("seed");
	public static final StringShort VENUE = Strings.intern("venue");


}
