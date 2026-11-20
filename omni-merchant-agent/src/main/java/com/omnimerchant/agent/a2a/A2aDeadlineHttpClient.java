package com.omnimerchant.agent.a2a;

import org.a2aproject.sdk.client.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** SDK transport adapter with an absolute turn deadline, including individual polling requests. */
public class A2aDeadlineHttpClient extends JdkA2AHttpClient {
    private final long deadline;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public A2aDeadlineHttpClient(long deadline) { this.deadline = deadline; }
    private A2AHttpResponse send(String url, String method, Map<String,String> headers, String body) throws IOException, InterruptedException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) throw new HttpTimeoutException("A2A turn deadline exceeded");
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(remaining));
        headers.forEach(request::header);
        var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return new A2AHttpResponse() {
            public int status() { return response.statusCode(); }
            public boolean success() { return status() >= 200 && status() < 300; }
            public String body() { return response.body(); }
        };
    }
    @Override public GetBuilder createGet() {
        return new GetBuilder() {
            private String url; private final Map<String,String> headers = new LinkedHashMap<>();
            public GetBuilder url(String value) { url = value; return this; }
            public GetBuilder addHeader(String name, String value) { headers.put(name, value); return this; }
            public GetBuilder addHeaders(Map<String,String> values) { headers.putAll(values); return this; }
            public A2AHttpResponse get() throws IOException, InterruptedException { return send(url, "GET", headers, null); }
            public CompletableFuture<Void> getAsyncSSE(Consumer<ServerSentEvent> consumer, Consumer<Throwable> errors, Runnable complete) throws IOException, InterruptedException {
                return A2aDeadlineHttpClient.super.createGet().url(url).addHeaders(headers).getAsyncSSE(consumer, errors, complete);
            }
        };
    }
    @Override public PostBuilder createPost() {
        return new PostBuilder() {
            private String url, body; private final Map<String,String> headers = new LinkedHashMap<>();
            public PostBuilder url(String value) { url = value; return this; }
            public PostBuilder body(String value) { body = value; return this; }
            public PostBuilder addHeader(String name, String value) { headers.put(name, value); return this; }
            public PostBuilder addHeaders(Map<String,String> values) { headers.putAll(values); return this; }
            public A2AHttpResponse post() throws IOException, InterruptedException { return send(url, "POST", headers, body); }
            public CompletableFuture<Void> postAsyncSSE(Consumer<ServerSentEvent> consumer, Consumer<Throwable> errors, Runnable complete) throws IOException, InterruptedException {
                return A2aDeadlineHttpClient.super.createPost().url(url).body(body).addHeaders(headers).postAsyncSSE(consumer, errors, complete);
            }
        };
    }
}
