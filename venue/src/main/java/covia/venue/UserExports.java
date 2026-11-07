package covia.venue;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ECIES;
import convex.core.cvm.CVMEncoder;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ADataStructure;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.grid.Job;
import covia.lattice.Namespace;
import covia.venue.migration.CallerAndOwner;

/**
 * A user's whole stored state as one value: export, import and clone.
 *
 * <p>The state is the user's record at {@code :user-data/<did>} without its
 * framework slots (lifecycle generation, deletion markers and the activity
 * {@code meta}). Everything else in the record travels, including namespaces
 * added after this was written. State the venue keeps outside the record
 * (login aliases and authenticators, DLFS drives, adapter-owned indexes,
 * scheduled events, content bytes) does not, and references from the state to
 * anything outside it are carried as stored.</p>
 *
 * <p>An export is the CAD3 multi-cell encoding of
 * {@code {format, version, did, venue, exported, user, secrets?}}.
 * {@code user} is the state without secrets. Secrets, encrypted on the venue
 * under its own key, are decrypted and sealed together to a caller-supplied
 * Ed25519 key with Convex ECIES; they are never exported in the clear or under
 * the venue's key. Covia's migrations of its stored shapes run on import: an
 * older export's records of calls take the current {@code caller} and
 * {@code owner} ({@link CallerAndOwner}) and secrets are re-encrypted under the
 * current key version; other records migrate on read, as they do on a stored
 * venue.</p>
 *
 * <p>An export keeps the user's jobs ({@code j/}) as an archive of the calls
 * it made. An import or a clone leaves them out: the account it fills has made
 * no calls here.</p>
 *
 * <p>Only quiet state is copied: an agent task still outstanding, or for an
 * export a job not yet finished, would be live in two places at once.</p>
 *
 * <p>These methods do no authority checks; the {@code user} adapter's
 * operations apply them. Embedders may call them directly.</p>
 */
public final class UserExports {

	/** The export's {@code format} value. */
	public static final AString FORMAT = Strings.intern("covia/user-export");

	/**
	 * The export format version this venue writes and the newest it reads.
	 * Advance it whenever the shape of a user's stored state changes in a way an
	 * older build cannot read, as the venue {@code :version} does, so an older
	 * venue refuses the export with {@link #NEWER_VERSION}.
	 */
	public static final long VERSION = 2;

	/** The first export version whose records of calls name {@code caller} and {@code owner}. */
	static final long CALLER_AND_OWNER = 2;

	public static final String PROTECTED = "The venue and public principals cannot be exported, imported or cloned";
	public static final String BUSY = "The user has unfinished work: a job not yet finished or an agent task outstanding. Retry once it has finished";
	public static final String SECRETS_NEED_KEY = "The user has secrets: supply accountKey to seal them, or secrets: false to leave them out";
	public static final String SECRET_UNREADABLE = "A secret could not be decrypted, so the export would be incomplete: "
		+ "set it again with secret:set or delete it, or export with secrets: false";
	public static final String NOT_AN_EXPORT = "Not a Covia user export";
	public static final String NEWER_VERSION = "The export was written by a newer Covia than this venue reads";
	public static final String SEED_REQUIRED = "The export holds sealed secrets: supply the seed they were sealed to";
	public static final String SEED_REJECTED = "The sealed secrets do not open with the supplied seed";
	public static final String SAME_USER = "A clone needs a target other than its source";

	static final AString K_FORMAT = Strings.intern("format");
	static final AString K_VENUE = Strings.intern("venue");
	static final AString K_EXPORTED = Strings.intern("exported");
	static final AString K_USER = Strings.intern("user");
	static final AString K_SECRETS = Strings.intern("secrets");

	private final Engine engine;

	public UserExports(Engine engine) {
		this.engine = engine;
	}

	/**
	 * Exports a registered user's whole state.
	 *
	 * @param did the user
	 * @param recipient key to seal the user's secrets to; may be null when
	 *        secrets are left out or there are none
	 * @param includeSecrets false to leave secrets out of the export
	 * @return the export
	 */
	public Blob exportUser(AString did, AccountKey recipient, boolean includeSecrets) {
		requireOrdinary(did);
		User user = engine.getVenueState().users().user(did);
		ACell record = (user == null) ? null : user.get();
		AMap<AString, ACell> state = Users.content(record);
		if (state == null) throw new NotFoundException(VenueState.UNKNOWN_USER, did.toString());
		String busy = unfinishedWork(state);
		if (busy != null) throw new UnavailableException(busy);

		AMap<AString, ACell> secrets = RT.ensureMap(state.get(Namespace.S));
		AMap<AString, ACell> export = Maps.of(
			K_FORMAT, FORMAT,
			Fields.VERSION, CVMLong.create(VERSION),
			Fields.DID, did,
			K_VENUE, engine.getDIDString(),
			K_EXPORTED, engine.getVenueState().getContext().currentTimestamp(),
			K_USER, state.dissoc(Namespace.S));
		if (includeSecrets && secrets != null && !secrets.isEmpty()) {
			if (recipient == null) throw new InvalidInputException(SECRETS_NEED_KEY);
			AMap<AString, ACell> plain;
			try {
				plain = SecretStore.plaintexts(secrets, SecretStore.keys(engine.getKeyPair()));
			} catch (RuntimeException e) {
				// Neither the provider's exception nor any decrypted material is reported.
				throw new NotSupportedException(SECRET_UNREADABLE);
			}
			export = export.assoc(K_SECRETS, ECIES.encrypt(recipient, Format.encodeMultiCell(plain, true)));
		}
		return Format.encodeMultiCell(export, true);
	}

	/**
	 * Makes a user from an export. The target is registered if it is not
	 * already, and must otherwise hold no user state.
	 *
	 * @param export an export from {@link #exportUser}, by this or another venue
	 * @param did the user to fill
	 * @param key the key pair the export's secrets were sealed to; may be null
	 *        when the export holds no secrets
	 * @return true when the import registered the target
	 */
	public boolean importUser(ABlob export, AString did, AKeyPair key) {
		requireOrdinary(did);
		AMap<AString, ACell> value = decode(export);
		// Secrets arrive only sealed: stored records were encrypted under the source venue's key.
		AMap<AString, ACell> state = RT.ensureMap(value.get(K_USER));
		state = migrate(state.dissoc(Namespace.S).dissoc(Namespace.J), RT.ensureLong(value.get(Fields.VERSION)).longValue());
		String busy = unfinishedWork(state);
		if (busy != null) throw new NotSupportedException(busy);

		ACell sealed = value.get(K_SECRETS);
		if (sealed != null) {
			if (!(sealed instanceof ABlob envelope)) throw new InvalidInputException(NOT_AN_EXPORT);
			if (key == null) throw new InvalidInputException(SEED_REQUIRED);
			ACell opened;
			try {
				opened = CVMEncoder.INSTANCE.decodeMultiCell(ECIES.decrypt(key, envelope.toFlatBlob()));
			} catch (BadFormatException | RuntimeException e) {
				throw new InvalidInputException(SEED_REJECTED);
			}
			AMap<AString, ACell> plain = RT.ensureMap(opened);
			if (plain == null) throw new InvalidInputException(NOT_AN_EXPORT);
			AMap<AString, ACell> records;
			try {
				records = SecretStore.records(plain, SecretStore.keys(engine.getKeyPair()));
			} catch (IllegalArgumentException e) {
				// The sealed secrets are the export's own content
				throw new InvalidInputException(e.getMessage(), e);
			}
			state = state.assoc(Namespace.S, records);
		}
		return restore(did, state);
	}

	/**
	 * Copies a user's whole state into another account on this venue. The copy
	 * is as stored, secrets included, so it shares structure with its source.
	 * The target is registered if it is not already, and must otherwise hold no
	 * user state.
	 *
	 * @return true when the clone registered the target
	 */
	public boolean cloneUser(AString from, AString to) {
		requireOrdinary(from);
		requireOrdinary(to);
		if (from.equals(to)) throw new InvalidInputException(SAME_USER);
		AMap<AString, ACell> state = engine.getVenueState().users().content(from);
		if (state == null) throw new NotFoundException(VenueState.UNKNOWN_USER, from.toString());
		state = state.dissoc(Namespace.J);
		String busy = unfinishedWork(state);
		if (busy != null) throw new UnavailableException(busy);
		return restore(to, state);
	}

	/** Writes the state, then re-arms its agents' wakes in this venue's schedule. */
	private boolean restore(AString did, AMap<AString, ACell> state) {
		boolean created = engine.getVenueState().users().restore(did, state);
		Scheduler scheduler = engine.gridScheduler();
		if (scheduler != null && state.get(Namespace.G) instanceof AMap<?, ?> agents) {
			// A copied wake handle names an event of the source; the schedule
			// replaces only an event owned by this user.
			for (ACell agentId : agents.keySet()) {
				if (agentId instanceof AString id) scheduler.rescheduleAgentWake(did, id);
			}
		}
		engine.flush();
		return created;
	}

	private void requireOrdinary(AString did) {
		if (did == null) throw new NotFoundException(VenueState.UNKNOWN_USER, null);
		engine.requireNotSubPrincipal(did);
		if (engine.isVenuePrincipal(did) || engine.isPublicPrincipal(did)) {
			throw new InvalidInputException(PROTECTED);
		}
	}

	/** Decodes and checks an export, refusing a version this venue cannot read. */
	static AMap<AString, ACell> decode(ABlob export) {
		if (export == null) throw new InvalidInputException(NOT_AN_EXPORT);
		ACell decoded;
		try {
			// Storeless: everything the export refers to must be in it.
			decoded = CVMEncoder.INSTANCE.decodeMultiCell(export.toFlatBlob());
		} catch (BadFormatException | RuntimeException e) {
			throw new InvalidInputException(NOT_AN_EXPORT);
		}
		AMap<AString, ACell> value = RT.ensureMap(decoded);
		if (value == null || !FORMAT.equals(value.get(K_FORMAT))
				|| !(value.get(Fields.VERSION) instanceof CVMLong version) || version.longValue() < 1
				|| !(value.get(K_USER) instanceof AMap<?, ?>)) {
			throw new InvalidInputException(NOT_AN_EXPORT);
		}
		if (version.longValue() > VERSION) throw new NotSupportedException(NEWER_VERSION);
		return value;
	}

	/**
	 * Brings the shapes an export at {@code version} holds to their current
	 * form. A record that names only its caller takes the namespace it would
	 * have on this venue.
	 */
	@SuppressWarnings("unchecked")
	private AMap<AString, ACell> migrate(AMap<AString, ACell> state, long version) {
		if (version >= CALLER_AND_OWNER) return state;
		ACell venue = engine.getVenueState().get();
		return (AMap<AString, ACell>) CallerAndOwner.userState(state,
			caller -> CallerAndOwner.legacyNamespace(venue, caller));
	}

	/** Null when the state is quiet; otherwise why it cannot be copied. */
	@SuppressWarnings("unchecked")
	static String unfinishedWork(AMap<AString, ACell> state) {
		if (state.get(Namespace.J) instanceof Index<?, ?> jobs) {
			for (long i = 0, n = jobs.count(); i < n; i++) {
				if (jobs.entryAt(i).getValue() instanceof AMap<?, ?> job && job.get(Fields.STATUS) != null
						&& !Job.isFinished((AMap<AString, ACell>) job)) return BUSY;
			}
		}
		if (state.get(Namespace.G) instanceof AMap<?, ?> agents) {
			for (ACell agent : agents.values()) {
				for (AString held : new AString[] {AgentState.KEY_TASKS, AgentState.KEY_RESOLVED}) {
					if (RT.getIn(agent, held) instanceof ADataStructure<?> tasks && tasks.count() > 0) return BUSY;
				}
			}
		}
		return null;
	}
}
