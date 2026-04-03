package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.json.schema.JsonSchema;
import convex.core.lang.RT;
import covia.venue.RequestContext;

/**
 * Adapter exposing JSON Schema operations as grid tools.
 *
 * <p>All operations are pure JVM functions — no IO, no LLM, sub-millisecond.
 * Backed by {@link JsonSchema} from convex-core.</p>
 */
public class SchemaAdapter extends AAdapter {

	private static final AString K_SCHEMA = Strings.intern("schema");
	private static final AString K_VALUE = Strings.intern("value");
	private static final AString K_VALID = Strings.intern("valid");
	private static final AString K_ERRORS = Strings.intern("errors");
	private static final AString K_ERROR = Strings.intern("error");

	@Override
	public String getName() {
		return "schema";
	}

	@Override
	public String getDescription() {
		return "JSON Schema validation, inference, and coercion. " +
			   "Validates data against schemas, infers schemas from example data, " +
			   "and coerces values to match target types. Pure JVM operations.";
	}

	@Override
	protected void installAssets() {
		installAsset("/adapters/schema/validate.json");
		installAsset("/adapters/schema/validateAll.json");
		installAsset("/adapters/schema/infer.json");
		installAsset("/adapters/schema/coerce.json");
		installAsset("/adapters/schema/check.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String op = getSubOperation(meta);
		try {
			return switch (op) {
				case "validate" -> CompletableFuture.completedFuture(handleValidate(input));
				case "validateAll" -> CompletableFuture.completedFuture(handleValidateAll(input));
				case "infer" -> CompletableFuture.completedFuture(handleInfer(input));
				case "coerce" -> CompletableFuture.completedFuture(handleCoerce(input));
				case "check" -> CompletableFuture.completedFuture(handleCheck(input));
				default -> CompletableFuture.failedFuture(
					new IllegalArgumentException("Unknown schema operation: " + op));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell handleValidate(ACell input) {
		AMap<AString, ACell> schema = getMap(RT.getIn(input, K_SCHEMA));
		ACell value = RT.getIn(input, K_VALUE);
		if (schema == null) throw new IllegalArgumentException("'schema' is required");

		String err = JsonSchema.validate(schema, value);
		if (err == null) {
			return Maps.of(K_VALID, CVMBool.TRUE);
		}
		return Maps.of(K_VALID, CVMBool.FALSE, K_ERROR, Strings.create(err));
	}

	private ACell handleValidateAll(ACell input) {
		AMap<AString, ACell> schema = getMap(RT.getIn(input, K_SCHEMA));
		ACell value = RT.getIn(input, K_VALUE);
		if (schema == null) throw new IllegalArgumentException("'schema' is required");

		AVector<AString> errors = JsonSchema.validateAll(schema, value);
		return Maps.of(
			K_VALID, CVMBool.of(errors.isEmpty()),
			K_ERRORS, errors
		);
	}

	private ACell handleInfer(ACell input) {
		ACell value = RT.getIn(input, K_VALUE);
		AMap<AString, ACell> schema = JsonSchema.infer(value);
		return Maps.of(K_SCHEMA, schema);
	}

	private ACell handleCoerce(ACell input) {
		AMap<AString, ACell> schema = getMap(RT.getIn(input, K_SCHEMA));
		ACell value = RT.getIn(input, K_VALUE);
		if (schema == null) throw new IllegalArgumentException("'schema' is required");

		ACell coerced = JsonSchema.coerce(schema, value);
		return Maps.of(K_VALUE, coerced);
	}

	private ACell handleCheck(ACell input) {
		AMap<AString, ACell> schema = getMap(RT.getIn(input, K_SCHEMA));
		if (schema == null) throw new IllegalArgumentException("'schema' is required");

		String err = JsonSchema.checkSchema(schema);
		if (err == null) {
			return Maps.of(K_VALID, CVMBool.TRUE);
		}
		return Maps.of(K_VALID, CVMBool.FALSE, K_ERROR, Strings.create(err));
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> getMap(ACell cell) {
		return (cell instanceof AMap) ? (AMap<AString, ACell>) cell : null;
	}
}
