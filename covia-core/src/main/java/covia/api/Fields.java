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
	public static final StringShort INPUT = Strings.intern("input");
	public static final StringShort OUTPUT = Strings.intern("output");
	public static final StringShort RESULT = Strings.intern("result");
	public static final StringShort OPERATION = Strings.intern("operation");
	public static final StringShort MESSAGE = Strings.intern("message");
	public static final StringShort DELAY = Strings.intern("delay");
	public static final StringShort ID = Strings.intern("id");
	public static final StringShort STATUS = Strings.intern("status");	
	public static final StringShort OP = Strings.intern("op");
	
	// List / pagination related
	public static final StringShort ITEMS = Strings.intern("items");
	public static final StringShort TOTAL = Strings.intern("total");
	public static final StringShort OFFSET = Strings.intern("offset");
	public static final StringShort LIMIT = Strings.intern("limit");
	
	// Content related
	public static final StringShort NAME =  Strings.intern("name");
	public static final StringShort DESCRIPTION = Strings.intern("description");
	public static final StringShort CONTENT = Strings.intern("content");
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
	
	// HTTP related
	public static final StringShort HEADERS = Strings.intern("headers");
	public static final StringShort QUERY_PARAMS = Strings.intern("queryParams");
	public static final StringShort BODY = Strings.intern("body");
	public static final StringShort METHOD = Strings.intern("method");
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
	
	// A2A stuff
	public static final StringShort A2A = Strings.intern("a2a");
	public static final StringShort AGENT_PROVIDER = Strings.intern("agentProvider");
	public static final StringShort AGENT_CAPABILITIES = Strings.intern("agentCapabilities");
	public static final StringShort AGENT_SKILLS = Strings.intern("agentSkills");
	public static final StringShort AGENT_INTERFACES = Strings.intern("agentInterfaces");
	public static final StringShort SECURITY_SCHEME = Strings.intern("securityScheme");
	public static final StringShort PREFERRED_TRANSPORT = Strings.intern("preferredTransport");
	public static final StringShort ADDITIONAL_INTERFACES = Strings.intern("additionalInterfaces");
	public static final StringShort TRANSPORT = Strings.intern("transport");
	public static final StringShort URL = Strings.intern("url");
	public static final StringShort PREFERRED = Strings.intern("preferred");
	public static final StringShort CATEGORY = Strings.intern("category");
	public static final StringShort OUTPUT_SCHEMA = Strings.intern("outputSchema");

	// Asset related
	public static final StringShort DEFINITION = Strings.intern("definition");
	public static final StringShort METADATA = Strings.intern("metadata");
	public static final StringShort STORED = Strings.intern("stored");

	// JSON Schema
	public static final StringShort REQUIRED = Strings.intern("required");
	public static final StringShort DEFS = Strings.intern("$defs");
	public static final StringShort DEFINITIONS = Strings.intern("definitions");

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
	public static final StringShort JOB_ID = Strings.intern("jobId");
	public static final StringShort SNAPSHOT = Strings.intern("snapshot");
	public static final StringShort AUTO_WAKE = Strings.intern("autoWake");
	public static final StringShort WAIT = Strings.intern("wait");
	public static final StringShort TIMEOUT = Strings.intern("timeout");
	public static final StringShort REMOVE = Strings.intern("remove");
	public static final StringShort REMOVED = Strings.intern("removed");
	public static final StringShort OVERWRITE = Strings.intern("overwrite");
	public static final StringShort INCLUDE_TERMINATED = Strings.intern("includeTerminated");
	public static final StringShort TASK_ID = Strings.intern("taskId");
	public static final StringShort CANCELLED = Strings.intern("cancelled");
	public static final StringShort RESPONSE_SCHEMA = Strings.intern("responseSchema");
	public static final StringShort T = Strings.intern("t");

	// Session related
	public static final StringShort SESSION_ID = Strings.intern("sessionId");
	public static final StringShort HISTORY = Strings.intern("history");
	public static final StringShort PARTIES = Strings.intern("parties");
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

	// Identity / authorization
	public static final StringShort CALLER = Strings.intern("caller");
	public static final StringShort ROLES = Strings.intern("roles");
	public static final StringShort REQUIRES = Strings.intern("requires");
	public static final StringShort EMAIL = Strings.intern("email");
	public static final StringShort PROVIDER = Strings.intern("provider");
	public static final StringShort PROVIDER_SUB = Strings.intern("providerSub");
	public static final StringShort SUB = Strings.intern("sub");
	public static final StringShort KID = Strings.intern("kid");

	// Convex related
	public static final StringShort PEER = Strings.intern("peer");
	public static final StringShort ADDRESS = Strings.intern("address");
	public static final StringShort SOURCE = Strings.intern("source");
	public static final StringShort SEED = Strings.intern("seed");
	public static final StringShort VENUE = Strings.intern("venue");


}
