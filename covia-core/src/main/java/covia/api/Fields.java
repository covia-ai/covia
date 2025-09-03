package covia.api;

import convex.core.data.ACell;
import convex.core.data.StringShort;
import convex.core.data.Strings;

/**
 * Static class for constant field name values used in Covia Grid API
 */
public class Fields {
	// Job related
	public static final StringShort CREATED=Strings.intern("created");
	public static final StringShort UPDATED = Strings.intern("updated");
	public static final StringShort JOB_STATUS_FIELD = Strings.intern("status");
	public static final StringShort JOB_ERROR_FIELD = Strings.intern("error");
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
	public static final StringShort FILE_NAME = Strings.intern("fineName");
	public static final StringShort SHA256 =  Strings.intern("sha256");
	
	// Misc / general purpose
	public static final StringShort OK = Strings.intern("OK");
	public static final StringShort TS = Strings.intern("ts");
	
	// Orchestration related
	public static final StringShort STEPS = Strings.intern("steps");
	public static final StringShort CONST = Strings.intern("const");
	
	// HTTP related
	public static final StringShort HEADERS = Strings.intern("headers");
	public static final StringShort URL = Strings.intern("url");
	public static final StringShort BODY = Strings.intern("body");
	public static final StringShort METHOD = Strings.intern("method");
	public static final StringShort INLINE = Strings.intern("inline");
	
	public static final StringShort PORT = Strings.intern("port");
	public static final StringShort HOSTNAME = Strings.intern("hostname");;
	public static final StringShort DID = Strings.intern("did");
	public static final StringShort VENUES = Strings.intern("venues");
	public static final StringShort ERROR = Strings.intern("error");
	
	// MCP stuff
	public static final StringShort MCP_TOOLNAME = Strings.intern("mcpToolName");
	public static final StringShort INPUT_SCHEMA = Strings.intern("inputSchema");
	public static final StringShort TITLE = Strings.intern("title");
	

}
