package covia.grid;

import convex.core.data.StringShort;
import convex.core.data.Strings;

public class Status {
	public static final StringShort FAILED=Strings.intern("FAILED");
	public static final StringShort PENDING = Strings.intern("PENDING");
	public static final StringShort STARTED = Strings.intern("STARTED");
	public static final StringShort COMPLETE = Strings.intern("COMPLETE");
	public static final StringShort CANCELLED = Strings.intern("CANCELLED");
	public static final StringShort TIMEOUT = Strings.intern("TIMEOUT");
	
	/**
	 * Not really a valid status, but use this during dev if needed
	 */
	public static final StringShort TODO = Strings.intern("TODO");
}
