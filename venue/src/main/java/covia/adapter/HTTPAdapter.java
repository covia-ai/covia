package covia.adapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.Method;
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
import convex.core.util.JSONUtils;
import convex.core.util.Utils;
import convex.java.HTTPClients;
import covia.api.Fields;
import covia.venue.Engine;

public class HTTPAdapter extends AAdapter {

	public static final Logger log = LoggerFactory.getLogger(HTTPAdapter.class);

	@Override
	public String getName() {
		return "http";
	}
	
	@Override 
	protected void installAssets() {
		String BASE = "/asset-examples/";

		// Install HTTP-related operation assets
		installAsset(BASE + "httpget.json", null);
		installAsset(BASE + "httppost.json", null);
		installAsset(BASE + "googlesearch.json", null);
		
		// Install Google search orchestration examples
		installAsset(BASE + "google-search-orch.json", null);
		installAsset(BASE + "google-search-advanced-orch.json", null);
		installAsset(BASE + "google-search-practical-orch.json", null);
		
		log.info("HTTP adapter assets installed successfully");
	}
	
	/* Example Gemini request
	 {
  "operation": "http:any",

  "input": {
    "url": "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
    "method":"POST",
    "headers":{
      "content-type":"application/json",
      "x-goog-api-key":"YOUR-KEY-HERE"
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
		ACell bodyField=RT.getIn(input, Fields.BODY);
		
		try {
			Method method=Method.GET; // default
			if (methodField!=null) try {
				method=Method.valueOf(methodField.toString().trim().toUpperCase());
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid HTTP method specified: "+methodField);
			}
			
			SimpleHttpRequest req = SimpleHttpRequest.create(method, new URI(url.toString()));
			String bodyText=(bodyField==null)?"":JSONUtils.toJSONPretty(bodyField).toString();
			req.setBody(bodyText, ContentType.TEXT_PLAIN);
			for (MapEntry<AString,AString> me:headers.entryVector()) {
				req.setHeader(me.getKey().toString(), me.getValue().toString());
			}
			//System.err.println(req);
			//System.err.println(bodyText);
			
			CompletableFuture<SimpleHttpResponse> responseFuture = HTTPClients.execute(req);
			CompletableFuture<ACell> result= responseFuture.thenApply(response -> {
				// System.err.println(response);
				AMap<AString,ACell> output=Maps.empty();
				int code=response.getCode();
				output=output.assoc(Fields.STATUS, CVMLong.create(code));
				output=output.assoc(Fields.BODY, Strings.create(response.getBodyText()));
				
				List<Header> hds=Arrays.asList(response.getHeaders());
				Collection<MapEntry<AString,AString>> hes=Utils.map(hds,header->{
					return MapEntry.of(header.getName(),header.getValue());
				});
				AMap<AString,AString> rheaders=Maps.fromEntries(hes);
				output=output.assoc(Fields.HEADERS, RT.cvm(rheaders));
				return output; // Final result
			});
			return result; // Future result
		
		} catch (URISyntaxException e) {
			throw new RuntimeException("Bad URI syntax: "+url,e);
		}

	}

}
