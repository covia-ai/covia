package covia.venue.auth;

import java.util.HashMap;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import convex.core.util.JSON;
import io.javalin.http.Context;

public class LoginProviders {

	private static final HashMap<String, OAuthProvider> PROVIDERS = new HashMap<>();

	private static final CloseableHttpClient client = HttpClients.createDefault();

	// Configuration for social login providers
	static {
		// Google
		PROVIDERS.put("google",
				new OAuthProvider("your-google-client-id", "your-google-client-secret",
						"http://localhost:8080/auth/google/callback", "https://accounts.google.com/o/oauth2/v2/auth",
						"https://oauth2.googleapis.com/token", "https://www.googleapis.com/oauth2/v3/userinfo",
						"openid email profile"));
		// Facebook (example - replace with real credentials)
		PROVIDERS.put("facebook",
				new OAuthProvider("your-facebook-client-id", "your-facebook-client-secret",
						"http://localhost:8080/auth/facebook/callback", "https://www.facebook.com/v19.0/dialog/oauth",
						"https://graph.facebook.com/v19.0/oauth/access_token",
						"https://graph.facebook.com/me?fields=id,name,email", "public_profile,email"));
		// Add more providers as needed (Twitter, GitHub, etc.)
	}

	public static void handleLogin(Context ctx) {
		String providerName = ctx.pathParam("provider");
		OAuthProvider provider = PROVIDERS.get(providerName);
		if (provider == null) {
			ctx.status(400).result("Unsupported provider: " + providerName);
			return;
		}

		String authUrl = provider.getAuthUrl();
		ctx.redirect(authUrl);
	}

	@SuppressWarnings("deprecation")
	public static void handleCallback(Context ctx) throws Exception {
		String providerName = ctx.pathParam("provider");
		OAuthProvider provider = PROVIDERS.get(providerName);
		if (provider == null) {
			ctx.status(400).result("Unsupported provider: " + providerName);
			return;
		}

		String code = ctx.queryParam("code");
		if (code == null) {
			ctx.status(400).result("Missing authorisation code");
			return;
		}

		// Exchange code for tokens
		HttpPost tokenRequest = new HttpPost(provider.tokenUrl);
		HashMap<String, String> params = new HashMap<>();
		params.put("code", code);
		params.put("client_id", provider.clientId);
		params.put("client_secret", provider.clientSecret);
		params.put("redirect_uri", provider.redirectUri);
		params.put("grant_type", "authorization_code");
		tokenRequest.setEntity(new StringEntity(JSON.toString(params)));

		try (CloseableHttpResponse response = client.execute(tokenRequest)) {
			if (response.getCode() != 200) {
				ctx.status(500).result("Failed to exchange code: " + response.getReasonPhrase());
				return;
			}
			String json = EntityUtils.toString(response.getEntity());
			TokenResponse token = TokenResponse.fromJSON(json);

			// Fetch user info
			HttpGet userInfoRequest = new HttpGet(provider.userInfoUrl);
			userInfoRequest.setHeader("Authorization", "Bearer " + token.accessToken);

			try (var userInfoResponse = client.execute(userInfoRequest)) {
				if (userInfoResponse.getCode() != 200) {
					ctx.status(500).result("Failed to fetch user info: " + userInfoResponse.getReasonPhrase());
					return;
				}
				String userJson = EntityUtils.toString(userInfoResponse.getEntity());
				UserInfo user = new UserInfo(userJson);

				// Store user in session (or generate JWT)
				ctx.sessionAttribute("user", user.email);
				ctx.redirect("/dashboard");
			}
		}
	}

	// OAuth provider configuration
	static class OAuthProvider {
		final String clientId;
		final String clientSecret;
		final String redirectUri;
		final String authUrlBase;
		final String tokenUrl;
		final String userInfoUrl;
		final String scope;

		OAuthProvider(String clientId, String clientSecret, String redirectUri, String authUrlBase, String tokenUrl,
				String userInfoUrl, String scope) {
			this.clientId = clientId;
			this.clientSecret = clientSecret;
			this.redirectUri = redirectUri;
			this.authUrlBase = authUrlBase;
			this.tokenUrl = tokenUrl;
			this.userInfoUrl = userInfoUrl;
			this.scope = scope;
		}

		String getAuthUrl() {
			return authUrlBase + "?response_type=code" + "&client_id=" + clientId + "&redirect_uri=" + redirectUri
					+ "&scope=" + scope + "&access_type=offline";
		}
	}

	// Data classes for JSON parsing
	static class TokenResponse {
		public String accessToken;
		public String idToken;

		@SuppressWarnings("unchecked")
		public static TokenResponse fromJSON(String json) {
			Map<String, Object> data = (Map<String, Object>) JSON.jvm(json);
			TokenResponse tr = new TokenResponse();
			tr.accessToken = (String) data.get("accessToken");
			tr.idToken = (String) data.get("idToken");
			return tr;
		}
	}

	static class UserInfo {
		public String id; // Provider-specific user ID
		public String email;
		public String name;

		public UserInfo(String json) {
			@SuppressWarnings("unchecked")
			Map<String, String> data = (Map<String, String>) JSON.jvm(json);
			this.id = data.get("id");
			this.email = data.get("email");
			this.name = data.get("name");
		}

	}
}
