package covia.adapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;

public class HTTPAdapter extends AAdapter {

	public static final Logger log = LoggerFactory.getLogger(HTTPAdapter.class);

	@Override
	public String getName() {
		return "http";
	}
	
	@Override
	public String getDescription() {
		return "HTTP client enables seamless web API integration and external service communication. " +
			   "Supports GET, POST, and other HTTP methods with custom headers, query parameters, and request bodies. " +
			   "Perfect for integrating with REST APIs, web services, and external data sources like Google Search and AI model APIs.";
	}
	
	@Override 
	protected void installAssets() {
		String BASE = "/asset-examples/";

		// Install HTTP-related operation assets
		installAsset(BASE + "httpget.json");
		installAsset(BASE + "httppost.json");
		installAsset(BASE + "http-query-example.json");
		installAsset(BASE + "googlesearch.json");
		
		// Install Google search orchestration examples
		installAsset(BASE + "google-search-orch.json");
		installAsset(BASE + "google-search-advanced-orch.json");
		installAsset(BASE + "google-search-practical-orch.json");
		
		log.info("HTTP adapter assets installed successfully");
	}
	
	/* Example Gemini request with query parameters
	 {
  "operation": "http:any",

  "input": {
    "url": "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
    "method":"POST",
    "headers":{
      "content-type":"application/json",
      "x-goog-api-key":"YOUR-KEY-HERE"
    },
    "queryParams": {
      "key": "YOUR-API-KEY",
      "alt": "json"
    },
    "body":{
      "contents": [
      {
        "parts": [
            {
             "text": "Explain how AI works in a few words"
            }
          ]
        }
      ]
    }
  }
} 
	 
	 */

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		String[] ss=operation.split(":");
		
		AString url=RT.ensureString(RT.getIn(input, Fields.URL));
		AString methodField=RT.ensureString(RT.getIn(input, Fields.METHOD));
		if ((methodField==null)&&(ss.length>1)) {
			methodField=Strings.create(ss[1]);
		}
		
		AMap<AString,AString> headers=RT.ensureMap(RT.getIn(input, Fields.HEADERS));
		AMap<AString,AString> queryParams=RT.ensureMap(RT.getIn(input, Fields.QUERY_PARAMS));
		ACell bodyField=RT.getIn(input, Fields.BODY);
		
		try {
			HttpRequest.Builder requestBuilder;
			String method = "GET"; // default
			if (methodField != null) {
				method = methodField.toString().trim().toUpperCase();
			}
			
			// Build URL with query parameters
			String finalUrl = url.toString();
			if (queryParams != null && !queryParams.isEmpty()) {
				StringBuilder queryString = new StringBuilder();
				boolean first = true;
				
				for (MapEntry<AString,AString> me : queryParams.entryVector()) {
					if (!first) {
						queryString.append("&");
					}
					queryString.append(URLEncoder.encode(me.getKey().toString(), StandardCharsets.UTF_8))
							  .append("=")
							  .append(URLEncoder.encode(me.getValue().toString(), StandardCharsets.UTF_8));
					first = false;
				}
				
				if (queryString.length() > 0) {
					finalUrl += (finalUrl.contains("?") ? "&" : "?") + queryString.toString();
				}
			}
			
			// Create HTTP request builder
			requestBuilder = HttpRequest.newBuilder()
				.uri(new URI(finalUrl))
				.timeout(Duration.ofSeconds(30));
			
			// Set method and body
			String bodyText = (bodyField == null) ? "" : JSON.printPretty(bodyField).toString();
			switch (method) {
				case "GET":
					requestBuilder.GET();
					break;
				case "POST":
					requestBuilder.POST(HttpRequest.BodyPublishers.ofString(bodyText));
					break;
				case "PUT":
					requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(bodyText));
					break;
				case "DELETE":
					requestBuilder.DELETE();
					break;
				case "PATCH":
					requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(bodyText));
					break;
				default:
					throw new IllegalArgumentException("Unsupported HTTP method: " + method);
			}
			
			// Add headers
			if (headers != null) {
				for (MapEntry<AString,AString> me : headers.entryVector()) {
					requestBuilder.header(me.getKey().toString(), me.getValue().toString());
				}
			}
			
			HttpRequest request = requestBuilder.build();
			
			// Create HTTP client and execute request
			HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
			
			CompletableFuture<HttpResponse<String>> responseFuture = client.sendAsync(
				request, 
				HttpResponse.BodyHandlers.ofString()
			);
			
			CompletableFuture<ACell> result = responseFuture.thenApply(response -> {
				AMap<AString,ACell> output = Maps.empty();
				int code = response.statusCode();
				output = output.assoc(Fields.STATUS, CVMLong.create(code));
				output = output.assoc(Fields.BODY, Strings.create(response.body()));
				
				// Convert response headers
				Map<String, List<String>> responseHeaders = response.headers().map();
				AMap<AString,AString> rheaders = Maps.empty();
				for (Map.Entry<String, List<String>> entry : responseHeaders.entrySet()) {
					String key = entry.getKey();
					String value = String.join(", ", entry.getValue());
					rheaders = rheaders.assoc(Strings.create(key), Strings.create(value));
				}
				output = output.assoc(Fields.HEADERS, RT.cvm(rheaders));
				return output; // Final result
			});
			
			return result; // Future result
		
		} catch (URISyntaxException e) {
			throw new RuntimeException("Bad URI syntax: "+url,e);
		}

	}

}
