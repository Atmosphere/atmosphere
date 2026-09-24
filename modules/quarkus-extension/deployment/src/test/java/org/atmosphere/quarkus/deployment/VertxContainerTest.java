/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.quarkus.deployment;

import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import org.atmosphere.config.service.AtmosphereHandlerService;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.atmosphere.quarkus.runtime.vertx.VertxBlockingAsyncSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of {@code quarkus.atmosphere.container=vertx}: the
 * Atmosphere mapping is served from a Vert.x route with virtual-thread blocking
 * suspend. Covers long-polling, SSE and WebSocket delivery, the caller's
 * {@code SecurityIdentity} reaching the Atmosphere request, cancellation of a
 * suspended request when the client disconnects, the request-body bound, and
 * that the running framework really uses the Vert.x async support.
 */
public class VertxContainerTest {

    private static List<Dependency> securityDeps() {
        return List.of(new ArtifactDependency("io.quarkus",
                "quarkus-elytron-security-properties-file-deployment", null, "jar",
                System.getProperty("quarkus.version", "3.39.5")));
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setForcedDependencies(securityDeps())
            .withApplicationRoot(jar -> jar.addClasses(VertxContainerTest.class, Chat.class, Who.class))
            .overrideConfigKey("quarkus.atmosphere.container", "vertx")
            .overrideConfigKey("quarkus.atmosphere.packages", "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.atmosphere.vertx.max-body-size", "1K")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.auth.basic", "true")
            .overrideConfigKey("quarkus.security.users.embedded.enabled", "true")
            .overrideConfigKey("quarkus.security.users.embedded.plain-text", "true")
            .overrideConfigKey("quarkus.security.users.embedded.users.alice", "secret")
            .overrideConfigKey("quarkus.http.auth.permission.who.paths", "/atmosphere/who")
            .overrideConfigKey("quarkus.http.auth.permission.who.policy", "authenticated");

    /** Suspends GETs; a POST (or a WebSocket message) broadcasts its body. */
    @AtmosphereHandlerService(path = "/atmosphere/chat")
    public static class Chat implements AtmosphereHandler {
        static volatile CountDownLatch suspended = new CountDownLatch(1);
        static volatile CountDownLatch disconnected = new CountDownLatch(1);

        @Override
        public void onRequest(AtmosphereResource r) throws IOException {
            if ("GET".equalsIgnoreCase(r.getRequest().getMethod())) {
                r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                    @Override
                    public void onSuspend(AtmosphereResourceEvent event) {
                        suspended.countDown();
                    }

                    @Override
                    public void onDisconnect(AtmosphereResourceEvent event) {
                        disconnected.countDown();
                    }
                });
                r.suspend();
            } else {
                var body = r.getRequest().getReader().readLine();
                r.getBroadcaster().broadcast(body);
            }
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
            var r = event.getResource();
            if (event.getMessage() == null || event.isCancelled() || event.isClosedByClient()) {
                return;
            }
            r.getResponse().write("msg:" + event.getMessage());
            if (r.transport() == AtmosphereResource.TRANSPORT.LONG_POLLING) {
                r.resume();
            }
        }

        @Override
        public void destroy() {
        }
    }

    /** Echoes the caller the Atmosphere request sees; protected by an HTTP permission. */
    @AtmosphereHandlerService(path = "/atmosphere/who")
    public static class Who implements AtmosphereHandler {
        @Override
        public void onRequest(AtmosphereResource r) throws IOException {
            var principal = r.getRequest().getUserPrincipal();
            r.getResponse().write(principal == null ? "<anonymous>" : principal.getName());
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) {
        }

        @Override
        public void destroy() {
        }
    }

    @TestHTTPResource("/atmosphere/chat")
    URL chatUrl;

    @TestHTTPResource("/atmosphere/who")
    URL whoUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI chat(String transport) {
        return URI.create(chatUrl + "?X-Atmosphere-Transport=" + transport + "&X-Atmosphere-tracking-id=0");
    }

    private void post(String message) throws Exception {
        var res = http.send(HttpRequest.newBuilder(chat("polling"))
                .POST(HttpRequest.BodyPublishers.ofString(message)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), "broadcast POST");
    }

    @Test
    public void frameworkRunsOnTheVertxAsyncSupport() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        assertNotNull(framework, "vertx mode must publish its framework to the shared consumers");
        assertInstanceOf(VertxBlockingAsyncSupport.class, framework.getAsyncSupport());
    }

    @Test
    public void longPollingIsSuspendedThenResumedByABroadcast() throws Exception {
        Chat.suspended = new CountDownLatch(1);
        var poll = http.sendAsync(HttpRequest.newBuilder(chat("long-polling")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(Chat.suspended.await(10, TimeUnit.SECONDS), "the GET must suspend");
        assertTrue(!poll.isDone(), "a suspended long-poll must not answer before a broadcast");
        post("hello");
        var res = poll.get(10, TimeUnit.SECONDS);
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("msg:hello"), res.body());
    }

    @Test
    public void sseStreamsBroadcasts() throws Exception {
        Chat.suspended = new CountDownLatch(1);
        var lines = new LinkedBlockingQueue<String>();
        var stream = http.sendAsync(HttpRequest.newBuilder(chat("sse"))
                        .header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertTrue(Chat.suspended.await(10, TimeUnit.SECONDS), "the SSE GET must suspend");
        var response = stream.get(10, TimeUnit.SECONDS);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"),
                "SSE content type: " + response.headers().map());
        Thread.ofVirtual().start(() -> {
            try {
                response.body().forEach(lines::add);
            } catch (java.io.UncheckedIOException closedAtTeardown) {
                // The stream is torn down with the test app; nothing left to read.
                lines.add("<closed>");
            }
        });
        post("streamed");
        String line;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            line = lines.poll(1, TimeUnit.SECONDS);
        } while ((line == null || !line.contains("msg:streamed")) && System.nanoTime() < deadline);
        assertNotNull(line, "no SSE event received");
        assertTrue(line.startsWith("data:") && line.contains("msg:streamed"), line);
    }

    @Test
    public void webSocketMessagesAreBroadcastBack() throws Exception {
        var received = new LinkedBlockingQueue<String>();
        var uri = URI.create(chat("websocket").toString().replaceFirst("^http", "ws"));
        var ws = http.newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                received.add(data.toString());
                webSocket.request(1);
                return null;
            }
        }).get(10, TimeUnit.SECONDS);
        ws.sendText("over-ws", true).get(5, TimeUnit.SECONDS);
        String frame;
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            frame = received.poll(1, TimeUnit.SECONDS);
        } while ((frame == null || !frame.contains("msg:over-ws")) && System.nanoTime() < deadline);
        assertNotNull(frame, "no WebSocket frame received");
        assertTrue(frame.contains("msg:over-ws"), frame);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    public void callerIdentityReachesTheAtmosphereRequest() throws Exception {
        var anonymous = http.send(HttpRequest.newBuilder(whoUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode(), "the HTTP permission must reject before Atmosphere");
        var basic = "Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
        var res = http.send(HttpRequest.newBuilder(whoUrl.toURI()).header("Authorization", basic).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertEquals("alice", res.body());
    }

    @Test
    public void clientDisconnectCancelsTheSuspendedRequest() throws Exception {
        Chat.suspended = new CountDownLatch(1);
        Chat.disconnected = new CountDownLatch(1);
        var client = HttpClient.newHttpClient();
        CompletableFuture<HttpResponse<String>> poll = client.sendAsync(
                HttpRequest.newBuilder(chat("long-polling")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(Chat.suspended.await(10, TimeUnit.SECONDS), "the GET must suspend");
        poll.cancel(true);
        client.shutdownNow();
        assertTrue(Chat.disconnected.await(15, TimeUnit.SECONDS),
                "a client disconnect must cancel the parked request and fire onDisconnect");
    }

    @Test
    public void oversizedBodyIsRejectedWith413() throws Exception {
        var res = http.send(HttpRequest.newBuilder(chat("polling"))
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4096))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, res.statusCode());
    }
}
