package covia.venue;

import convex.core.data.AString;
import convex.core.data.Strings;

/**
 * Static configuration utilities and constants for Covia venue configuration.
 *
 * <p>This class contains all configuration keys used in venue JSON5 config files.
 * All keys are interned AString constants for direct use with CVM data structures.
 */
public class Config {

	// ========== Top-level config keys ==========

	/** Key for venues array */
	public static final AString VENUES = Strings.intern("venues");

	/** Key for Convex peer configuration */
	public static final AString CONVEX = Strings.intern("convex");

	// ========== Venue config keys ==========

	/** Key for venue name */
	public static final AString NAME = Strings.intern("name");

	/** Key for venue DID */
	public static final AString DID = Strings.intern("did");

	/** Key for venue hostname */
	public static final AString HOSTNAME = Strings.intern("hostname");

	/** Key for venue port */
	public static final AString PORT = Strings.intern("port");

	/** Key for MCP configuration */
	public static final AString MCP = Strings.intern("mcp");

	/** Key for adapters configuration */
	public static final AString ADAPTERS = Strings.intern("adapters");

	// ========== Storage config keys ==========

	/** Key for storage configuration section */
	public static final AString STORAGE = Strings.intern("storage");

	/** Key for storage content type */
	public static final AString CONTENT = Strings.intern("content");

	/** Key for storage path (for file/dlfs storage) */
	public static final AString PATH = Strings.intern("path");

	// ========== Storage type values ==========

	/** Storage type: lattice (CRDT-backed, default) */
	public static final AString STORAGE_TYPE_LATTICE = Strings.intern("lattice");

	/** Storage type: memory (in-memory, non-persistent) */
	public static final AString STORAGE_TYPE_MEMORY = Strings.intern("memory");

	/** Storage type: file (filesystem-based) */
	public static final AString STORAGE_TYPE_FILE = Strings.intern("file");

	/** Storage type: dlfs (DLFS lattice-backed filesystem) */
	public static final AString STORAGE_TYPE_DLFS = Strings.intern("dlfs");

	// ========== MCP config keys ==========

	/** Key for MCP enabled flag */
	public static final AString ENABLED = Strings.intern("enabled");

	private Config() {
		// Prevent instantiation
	}
}
