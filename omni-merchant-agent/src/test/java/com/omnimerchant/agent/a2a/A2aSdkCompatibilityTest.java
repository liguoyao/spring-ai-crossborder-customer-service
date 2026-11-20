package com.omnimerchant.agent.a2a;

import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class A2aSdkCompatibilityTest {
    @Test
    void deadlineAdapterRejectsExpiredBudgetAndDoesNotFollowRedirects() throws Exception {
        assertThrows(java.net.http.HttpTimeoutException.class,
                () -> new A2aDeadlineHttpClient(1).createGet().url("http://127.0.0.1:1/").get());
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/unauthorized");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            var response = new A2aDeadlineHttpClient(System.currentTimeMillis()+5000).createGet()
                    .url("http://127.0.0.1:" + server.getAddress().getPort() + "/").get();
            assertEquals(302, response.status()); assertFalse(response.success());
        } finally { server.stop(0); }
    }
    @Test
    void officialSdkRoundTripsTasksAndSseOverRealHttp() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var task = new Task("task-1", "conversation-1", new TaskStatus(TaskState.TASK_STATE_COMPLETED),
                List.of(new Artifact("result", "result", null, List.of(new DataPart(Map.of("version", 1))), null, null)),
                List.of(), Map.of());
        server.createContext("/", exchange -> {
            try {
                var request = JsonUtil.OBJECT_MAPPER.fromJson(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
                var id = request.get("id").getAsString();
                var streaming = request.get("method").getAsString().equals(A2AMethods.SEND_STREAMING_MESSAGE_METHOD);
                var body = streaming ? "data: " + JsonUtil.toJson(new SendStreamingMessageResponse(id, task)) + "\n\n"
                        : JsonUtil.toJson(new SendMessageResponse(id, task));
                exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { exchange.close(); }
        });
        server.start();
        var transport = new JSONRPCTransport("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var message = Message.builder().role(Message.Role.ROLE_USER).messageId("m-1")
                    .contextId("conversation-1").parts(List.of(new TextPart("test"))).build();
            var params = new MessageSendParams(message, null, Map.of());
            assertEquals(task.id(), ((Task) transport.sendMessage(params, null)).id());
            var latch = new CountDownLatch(1);
            var events = new CopyOnWriteArrayList<StreamingEventKind>();
            var errors = new CopyOnWriteArrayList<Throwable>();
            transport.sendMessageStreaming(params, event -> { events.add(event); latch.countDown(); },
                    error -> { if (error != null) errors.add(error); latch.countDown(); }, null);
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertTrue(errors.isEmpty(), errors.toString());
            assertEquals(task.id(), ((Task) events.getFirst()).id());
            assertEquals("1.0", AgentInterface.CURRENT_PROTOCOL_VERSION);
        } finally { transport.close(); server.stop(0); }
    }
}
