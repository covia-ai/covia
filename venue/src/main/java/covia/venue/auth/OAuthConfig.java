package covia.venue.auth;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

	public static OAuthConfig microsoft(String clientId, String clientSecret, String baseUrl) {
		return new OAuthConfig("microsoft", clientId, clientSecret,
			"https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
			"https://login.microsoftonline.com/common/oauth2/v2.0/token",
			"https://graph.microsoft.com/oidc/userinfo",
			"https://login.microsoftonline.com/common/discovery/v2.0/keys",
			null, // Microsoft issuer varies by tenant — skip iss validation
			"openid email profile",
			baseUrl + "/auth/microsoft/callback");
	}

	public static OAuthConfig github(String clientId, String clientSecret, String baseUrl) {
		return new OAuthConfig("github", clientId, clientSecret,
			"https://github.com/login/oauth/authorize",
			"https://github.com/login/oauth/access_token",
			"https://api.github.com/user",
			null, // GitHub doesn't issue JWTs
			null,
			"read:user user:email",
			baseUrl + "/auth/github/callback");
	}

	/**
	 * Construct the full authorisation redirect URL for this provider.
	 */
	public String getAuthUrl() {
		return authUrl
			+ "?response_type=code"
			+ "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
			+ "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
			+ "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
			+ "&access_type=offline";
	}
}
