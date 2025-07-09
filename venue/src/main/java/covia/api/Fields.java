package covia.api;

import convex.core.data.StringShort;
import convex.core.data.Strings;

public class Fields {
	public static final StringShort CREATED=Strings.intern("created");
	public static final StringShort  UPDATED = Strings.intern("updated");
	public static final StringShort JOB_STATUS_FIELD = Strings.intern("status");
	public static final StringShort JOB_ERROR_FIELD = Strings.intern("error");
	public static final StringShort INPUT = Strings.intern("input");
	public static final StringShort OUTPUT = Strings.intern("output");
	public static final StringShort ID = Strings.intern("id");
	public static final StringShort STATUS = Strings.intern("status");
	
	public static final StringShort NAME =  Strings.intern("name");
	public static final StringShort CONTENT = Strings.intern("content");
	
	public static final StringShort SHA256 =  Strings.intern("sha256");
}
