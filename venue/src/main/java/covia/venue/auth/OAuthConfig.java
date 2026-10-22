package covia.venue.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

/**
 * Configuration for a single OAuth provider, combining well-known endpoints
 * with venue-specific credentials loaded from config.
 */
public class OAuthConfig {

	public final String name;
	public final String clientId;
	public final String clientSecret;
	public final String authUrl;
	public final String tokenUrl;
	public final String userInfoUrl;
	public final String jwksUri;
	public final String issuer;
	public final String scope;
	public final String redirectUri;
	private volatile JWSVerificationKeySelector<SecurityContext> keySelector;
	public static final String TENANT_REQUIRED = "Microsoft login requires an explicit tenantId UUID; common/organizations login is not enabled";
	public static final String INVALID_ID_TOKEN = "Invalid provider ID token";

	public OAuthConfig(String name, String clientId, String clientSecret,
			String authUrl, String tokenUrl, String userInfoUrl,
			String jwksUri, String issuer, String scope, String redirectUri) {
		this.name = name;
		this.clientId = clientId;
		this.clientSecret = clientSecret;
		this.authUrl = authUrl;
		this.tokenUrl = tokenUrl;
		this.userInfoUrl = userInfoUrl;
		this.jwksUri = jwksUri;
		this.issuer = issuer;
		this.scope = scope;
		this.redirectUri = redirectUri;
	}

	public static OAuthConfig google(String clientId, String clientSecret, String baseUrl) {
		return new OAuthConfig("google", clientId, clientSecret,
			"https://accounts.google.com/o/oauth2/v2/auth",
			"https://oauth2.googleapis.com/token",
			"https://www.googleapis.com/oauth2/v3/userinfo",
			"https://www.googleapis.com/oauth2/v3/certs",
			"https://accounts.google.com",
			"openid email profile",
			baseUrl + "/auth/google/callback");
	}

	public static OAuthConfig microsoft(String clientId, String clientSecret, String baseUrl, String tenantId) {
		if (tenantId == null || !tenantId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
			throw new IllegalArgumentException(TENANT_REQUIRED);
		}
		String authority = "https://login.microsoftonline.com/" + tenantId.toLowerCase(java.util.Locale.ROOT);
		return new OAuthConfig("microsoft", clientId, clientSecret,
			authority + "/oauth2/v2.0/authorize",
			authority + "/oauth2/v2.0/token",
			"https://graph.microsoft.com/oidc/userinfo",
			authority + "/discovery/v2.0/keys",
			authority + "/v2.0",
			"openid email profile",
			baseUrl + "/auth/microsoft/callback");
	}

	public static OAuthConfig github(String clientId, String clientSecret, String baseUrl) {
		return new OAuthConfig("github", clientId, clientSecret,
			"https://github.com/login/oauth/authorize",
			"https://github.com/login/oauth/access_token",
			"https://api.github.com/user",
			null, // GitHub doesn't issue JWTs
			"https://github.com",
			"read:user",
			baseUrl + "/auth/github/callback");
	}

	/**
	 * Construct the full authorisation redirect URL for this provider.
	 */
	public String getAuthUrl(String state, CodeVerifier verifier, Nonce nonce) {
		String url = authUrl
			+ "?response_type=code"
			+ "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
			+ "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
			+ "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
			+ "&state=" + URLEncoder.encode(java.util.Objects.requireNonNull(state), StandardCharsets.UTF_8)
			+ "&code_challenge_method=S256&code_challenge="
			+ CodeChallenge.compute(CodeChallengeMethod.S256, verifier).getValue();
		if (jwksUri != null) url += "&nonce=" + URLEncoder.encode(java.util.Objects.requireNonNull(nonce).getValue(), StandardCharsets.UTF_8);
		return url;
	}

	/** One strict validator shared by browser callbacks and direct provider-token authentication. */
	public IDTokenClaimsSet validateIdToken(String token, Nonce nonce) throws Exception {
		if (issuer == null || jwksUri == null) throw new IllegalArgumentException(INVALID_ID_TOKEN);
		SignedJWT jwt = SignedJWT.parse(token);
		JWSVerificationKeySelector<SecurityContext> keys = keySelector;
		if (keys == null) synchronized (this) {
			keys = keySelector;
			if (keys == null) {
				var source = JWKSourceBuilder.<SecurityContext>create(URI.create(jwksUri).toURL(),
					new DefaultResourceRetriever(10_000, 10_000, 1_048_576))
					.refreshAheadCache(false).build();
				keys = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source);
				keySelector = keys;
			}
		}
		// Google documents both exact issuer spellings. Both bind to the configured canonical issuer.
		String expectedIssuer = "https://accounts.google.com".equals(issuer)
			&& "accounts.google.com".equals(jwt.getJWTClaimsSet().getIssuer()) ? "accounts.google.com" : issuer;
		IDTokenValidator current = new IDTokenValidator(new Issuer(expectedIssuer), new ClientID(clientId), keys, null);
		current.setMaxClockSkew(60);
		IDTokenClaimsSet verified = current.validate(jwt, nonce);
		// Nimbus' OIDC verifier checks required iss/sub/aud/exp/iat/nonce.
		// Its general JWT verifier additionally checks optional nbf.
		var temporal = new DefaultJWTClaimsVerifier<>(null, Set.of());
		temporal.setMaxClockSkew(60);
		temporal.verify(jwt.getJWTClaimsSet(), null);
		String azp = jwt.getJWTClaimsSet().getStringClaim("azp");
		if ((azp != null && !clientId.equals(azp))
				|| (jwt.getJWTClaimsSet().getAudience().size() > 1 && azp == null)
				|| verified.getSubject().getValue().isBlank()) throw new IllegalArgumentException(INVALID_ID_TOKEN);
		return verified;
	}
}
