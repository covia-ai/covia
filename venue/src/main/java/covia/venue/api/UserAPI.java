package covia.venue.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.crypto.util.Multikey;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.grid.Venue;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiResponse;

/**
 * User-related API endpoints for a Covia Venue.
 *
 * Provides DID document resolution for venue-issued user DIDs.
 * A user DID of the form did:web:venue.example.com:u:alice resolves
 * to https://venue.example.com/u/alice/did.json per the did:web specification.
 *
 * The venue is the controller of these DIDs and signs assertions about the user's
 * identity using its own key pair.
 */
public class UserAPI extends ACoviaAPI {

	public static final Logger log = LoggerFactory.getLogger(UserAPI.class);

	public UserAPI(Venue venue) {
		super(venue);
	}

	public void addRoutes(Javalin javalin) {
		javalin.get("/u/{id}/did.json", this::getUserDIDDocument);
	}

	@OpenApi(path = "/u/{id}/did.json",
			methods = HttpMethod.GET,
			tags = { "User" },
			summary = "Get user DID document for did:web resolution",
			operationId = "getUserDIDDocument",
			pathParams = {
				@OpenApiParam(name = "id", description = "User identifier within this venue")
			},
			responses = {
				@OpenApiResponse(
						status = "200",
						description = "DID Document",
						content = {
							@OpenApiContent(
									type = "application/json",
									from = Object.class)
						}),
				@OpenApiResponse(status = "404", description = "User not found")
			})
	protected void getUserDIDDocument(Context ctx) {
		ctx.header("Content-type", ContentTypes.JSON);

		String userId = ctx.pathParam("id");
		if (userId == null || userId.isEmpty()) {
			buildError(ctx, 400, "User ID required");
			return;
		}

		// Look up user from the user database
		AMap<AString, ACell> userRecord = engine().getAuth().getUser(userId);
		if (userRecord == null) {
			buildError(ctx, 404, "User not found: " + userId);
			return;
		}

		try {
			String baseUrl = getExternalBaseUrl(ctx, "");
			AMap<AString, ACell> didDocument = createUserDIDDocument(userId, userRecord, baseUrl);
			buildResult(ctx, didDocument);
		} catch (Exception e) {
			log.error("Error generating user DID document", e);
			buildError(ctx, 500, "Error generating DID document");
		}
	}

	/**
	 * Create a DID document for a venue-issued user identity.
	 *
	 * The venue acts as controller, with the venue's key in the verificationMethod.
	 * This allows others to verify venue-signed JWTs for this user by resolving
	 * the DID document and checking that the venue's key is listed.
	 *
	 * @param userId User identifier (e.g. "alice")
	 * @param userRecord User record from the user database (must contain "did")
	 * @param baseUrl Base URL of this venue (e.g. "https://venue.example.com")
	 * @return DID Document as a map
	 */
	private AMap<AString, ACell> createUserDIDDocument(String userId, AMap<AString, ACell> userRecord, String baseUrl) {
		// Use the DID from the user record if available, otherwise derive from venue DID
		AString userDID = convex.core.lang.RT.ensureString(userRecord.get(Strings.create("did")));
		AString did;
		if (userDID != null) {
			did = userDID;
		} else {
			// Derive user DID by appending to venue DID
			did = Strings.create(engine().getDIDString() + ":u:" + userId);
		}

		// The venue is the controller — its key is the verification method
		AString venueDID = engine().getDIDString();
		AString venueKey = Multikey.encodePublicKey(engine().getAccountKey());
		AString keyID = Strings.create(did + "#venue-key");

		AMap<AString, ACell> ddo = Maps.of(
			"@context", "https://www.w3.org/ns/did/v1",
			"id", did,
			"controller", venueDID,
			"verificationMethod", Vectors.of(Maps.of(
					"id", keyID,
					"type", "Multikey",
					"controller", venueDID,
					"publicKeyMultibase", venueKey
				)),
			"authentication", Vectors.of(keyID),
			"assertionMethod", Vectors.of(keyID)
		);

		return ddo;
	}

	/**
	 * Extract the host (with port if non-standard) from a base URL.
	 * e.g. "https://venue.example.com" -> "venue.example.com"
	 *      "http://localhost:8080" -> "localhost%3A8080"
	 * did:web spec requires percent-encoding colons in the host.
	 */
	static String extractHost(String baseUrl) {
		String host = baseUrl;
		// Strip scheme
		int schemeEnd = host.indexOf("://");
		if (schemeEnd >= 0) {
			host = host.substring(schemeEnd + 3);
		}
		// Strip path
		int pathStart = host.indexOf('/');
		if (pathStart >= 0) {
			host = host.substring(0, pathStart);
		}
		// Percent-encode colons per did:web spec
		host = host.replace(":", "%3A");
		return host;
	}
}
