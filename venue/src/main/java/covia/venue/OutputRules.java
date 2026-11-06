package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * What an operation declares about a job's data, kept as the job was admitted
 * so that a catalog change mid-job changes neither: the fields hidden in its
 * input and output ({@code operation.secretFields}) and the schema its output
 * is checked against ({@code operation.output}, when the operator turns
 * {@code outputValidation} on).
 *
 * @param secretFields Names of the fields to hide, or null when none
 * @param outputSchema The output's JSON Schema, or null when none
 */
public record OutputRules(AVector<ACell> secretFields, AMap<AString, ACell> outputSchema) {

	/** Rules of an operation that declares neither. */
	public static final OutputRules NONE = new OutputRules(null, null);

	static final AString SECRET_FIELDS = Strings.intern("secretFields");
	static final AString OUTPUT_SCHEMA = Strings.intern("outputSchema");

	/** The rules an operation's metadata declares; {@link #NONE} for null metadata. */
	public static OutputRules of(AMap<AString, ACell> meta) {
		AVector<ACell> secret = RT.ensureVector(RT.getIn(meta, Fields.OPERATION, SECRET_FIELDS));
		AMap<AString, ACell> schema = RT.ensureMap(RT.getIn(meta, Fields.OPERATION, Fields.OUTPUT));
		return create(secret, schema);
	}

	/** The rules a job's entry keeps ({@link #toCell}); {@link #NONE} for null. */
	public static OutputRules fromCell(ACell cell) {
		return create(RT.ensureVector(RT.getIn(cell, SECRET_FIELDS)), RT.ensureMap(RT.getIn(cell, OUTPUT_SCHEMA)));
	}

	private static OutputRules create(AVector<ACell> secret, AMap<AString, ACell> schema) {
		if (secret != null && secret.isEmpty()) secret = null;
		if (schema != null && schema.isEmpty()) schema = null;
		return (secret == null && schema == null) ? NONE : new OutputRules(secret, schema);
	}

	/** The rules as an entry keeps them, or null when there are none. */
	public AMap<AString, ACell> toCell() {
		AMap<AString, ACell> cell = Maps.empty();
		if (secretFields != null) cell = cell.assoc(SECRET_FIELDS, secretFields);
		if (outputSchema != null) cell = cell.assoc(OUTPUT_SCHEMA, outputSchema);
		return cell.isEmpty() ? null : cell;
	}

	/**
	 * {@code value} with each secret field replaced by {@link Fields#HIDDEN}. A
	 * value that is not a map, or holds none of them, is returned as it is.
	 */
	@SuppressWarnings("unchecked")
	public ACell redact(ACell value) {
		if (secretFields == null || !(value instanceof AMap)) return value;
		AMap<AString, ACell> map = (AMap<AString, ACell>) value;
		for (ACell name : secretFields) {
			AString field = RT.ensureString(name);
			if (field != null && map.containsKey(field)) map = map.assoc(field, Fields.HIDDEN);
		}
		return map;
	}

	/**
	 * A job's data with the secret fields of its input and output hidden. Every
	 * recorded change is redacted, since an adapter may change a record after
	 * submission.
	 */
	public AMap<AString, ACell> redactJob(AMap<AString, ACell> jobData) {
		if (secretFields == null) return jobData;
		AMap<AString, ACell> redacted = jobData;
		for (AString key : new AString[] {Fields.INPUT, Fields.OUTPUT}) {
			ACell value = redacted.get(key);
			if (value == null) continue;
			ACell safe = redact(value);
			if (safe != value) redacted = redacted.assoc(key, safe);
		}
		return redacted;
	}
}
