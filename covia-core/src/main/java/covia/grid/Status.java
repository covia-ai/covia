package covia.grid;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.StringShort;
import convex.core.data.Strings;

public class Status {
	public static final StringShort FAILED=Strings.intern("FAILED");
	public static final StringShort PENDING = Strings.intern("PENDING");
	public static final StringShort STARTED = Strings.intern("STARTED");
	public static final StringShort COMPLETE = Strings.intern("COMPLETE");
	public static final StringShort CANCELLED = Strings.intern("CANCELLED");
	public static final StringShort TIMEOUT = Strings.intern("TIMEOUT");
	public static final StringShort REJECTED = Strings.intern("REJECTED");
	public static final StringShort INPUT_REQUIRED = Strings.intern("INPUT_REQUIRED");
	public static final StringShort AUTH_REQUIRED = Strings.intern("AUTH_REQUIRED");
	public static final StringShort PAUSED = Strings.intern("PAUSED");
	
	/**
	 * Not really a valid status, but use this during dev if needed
	 */
	public static final StringShort TODO = Strings.intern("TODO");

	public static AHashMap<AString, ACell> failure(String message) {
		return Maps.of(
			"status", FAILED,
			"message", Strings.create(message)
		);
	}
	
	public static AHashMap<AString, ACell> rejected(String message) {
		return Maps.of(
			"status", REJECTED,
			"message", Strings.create(message)
		);
	}
	
	public static AHashMap<AString, ACell> inputRequired(String message) {
		return Maps.of(
			"status", INPUT_REQUIRED,
			"message", Strings.create(message)
		);
	}
	
	public static AHashMap<AString, ACell> authRequired(String message) {
		return Maps.of(
			"status", AUTH_REQUIRED,
			"message", Strings.create(message)
		);
	}
	
	public static AHashMap<AString, ACell> paused(String message) {
		return Maps.of(
			"status", PAUSED,
			"message", Strings.create(message)
		);
	}
}
