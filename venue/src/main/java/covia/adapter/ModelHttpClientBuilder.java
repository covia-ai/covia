package covia.adapter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import covia.exception.CoviaException;
import covia.venue.Engine;
import covia.venue.RequestContext;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/** LangChain's transport extension: authorise the actual provider request on
 * every attempt, using the context of this invocation, including harness calls.
 * The upstream default JDK transport never follows redirects. */
final class ModelHttpClientBuilder implements HttpClientBuilder {

	private final Engine engine;
	private final RequestContext ctx;
	private final HttpClientBuilder delegate = new JdkHttpClientBuilder();

	ModelHttpClientBuilder(Engine engine, RequestContext ctx) {
		this.engine = engine;
		this.ctx = ctx;
	}

	@Override public Duration connectTimeout() { return delegate.connectTimeout(); }
	@Override public Duration readTimeout() { return delegate.readTimeout(); }
	@Override public HttpClientBuilder connectTimeout(Duration timeout) { delegate.connectTimeout(timeout); return this; }
	@Override public HttpClientBuilder readTimeout(Duration timeout) { delegate.readTimeout(timeout); return this; }

	@Override public HttpClient build() {
		HttpClient client = delegate.build();
		return new HttpClient() {
			private void require(HttpRequest request) {
				try {
					engine.requireOutboundHttp(ctx, request.method().name(), request.url());
				} catch (CoviaException refused) {
					// The refusal is final: langchain4j retries anything not marked
					// so. LangChainAdapter.providerFailure reports the refusal itself.
					throw new NonRetriableException(refused);
				}
			}
			@Override public SuccessfulHttpResponse execute(HttpRequest request) {
				require(request);
				return client.execute(request);
			}
			@Override public CompletableFuture<SuccessfulHttpResponse> executeAsync(HttpRequest request) {
				try { require(request); }
				catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
				return client.executeAsync(request);
			}
			@Override public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
				require(request);
				client.execute(request, parser, listener);
			}
		};
	}
}
