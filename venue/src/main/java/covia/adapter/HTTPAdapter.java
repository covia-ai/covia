package covia.adapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.Method;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.java.HTTPClients;
import covia.api.Fields;

public class HTTPAdapter extends AAdapter {

	@Override
	public String getName() {
		return "http";
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(String operation, ACell meta, ACell input) {
		AString url=RT.ensureString(RT.getIn(input, Fields.URL));
		AString methodField=RT.ensureString(RT.getIn(input, Fields.METHOD));
		AMap<AString,AString> headers=RT.ensureMap(RT.getIn(input, Fields.HEADERS));
		
		try {
			Method method=Method.GET; // default
			if (methodField!=null) try {
				method=Method.valueOf(methodField.toString().trim().toUpperCase());
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid HTTP method specified: "+methodField);
			}
			
			SimpleHttpRequest req = SimpleHttpRequest.create(method, new URI(url.toString()));
			for (MapEntry<AString,AString> me:headers.entryVector()) {
				req.setHeader(me.getKey().toString(), me.getValue().toString());
			}
			CompletableFuture<SimpleHttpResponse> responseFuture = HTTPClients.execute(req);
			CompletableFuture<ACell> result= responseFuture.thenApply(response -> {
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
