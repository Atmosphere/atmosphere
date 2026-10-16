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
package org.atmosphere.integrationtests.ai.streaming;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An atmosphere.js client on {@code transport: 'streaming'} with the Atmosphere
 * protocol on, over real HTTP: a streaming GET carrying tracking id {@code 0}
 * is answered, on the open response, with the id the server assigned; the
 * client then POSTs its messages naming that id, and reads every reply from the
 * stream, never from the POST's own response. Prompts to an
 * {@code @AiEndpoint} and to an {@code @Agent}, and an {@code @Agent} slash
 * command, must be answered on that stream, as they are over SSE.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AiStreamingTransportTest {

    private static final String AGENT_PATH = "/atmosphere/agent/test-agent";
    private static final Pattern ASSIGNED_ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.ai.streaming,org.atmosphere.integrationtests.agent")
                .withInitParam("org.atmosphere.annotation.packages",
                        "org.atmosphere.agent.processor,org.atmosphere.ai.processor");
        server.start();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // The framework initialises on the first request.
        httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterAll
    public void tearDown() throws Exception {
        // The streams are suspended by design; close() would wait for them.
        httpClient.shutdownNow();
        server.close();
    }

    @BeforeEach
    public void reset() {
        StreamingTransportTestEndpoint.READY.clear();
        StreamingTransportTestEndpoint.RUN_IDS.clear();
        StreamingTransportTestEndpoint.RELEASE.countDown();
        StreamingTransportTestEndpoint.RELEASE = new CountDownLatch(1);
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void anAiEndpointPromptIsAnsweredOnTheStream() throws Exception {
        var stream = open(StreamingTransportTestEndpoint.PATH, "alice-token", null);
        var id = stream.assignedId();

        var response = post(StreamingTransportTestEndpoint.PATH, id, "hello");

        assertEquals(200, response.statusCode(), "the prompt POST must be accepted: " + response.body());
        stream.await(s -> s.contains("reply-to:hello|alice-token") && s.contains("\"type\":\"complete\""),
                "the reply must arrive on the stream, under the ai.userId @Ready set");
        assertEquals(1, StreamingTransportTestEndpoint.READY.get(id), "@Ready must run once for the stream");
        stream.close();
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void anAgentPromptAndSlashCommandAreAnsweredOnTheStream() throws Exception {
        var stream = open(AGENT_PATH, null, null);
        var id = stream.assignedId();

        var command = post(AGENT_PATH, id, "/ping");
        assertEquals(200, command.statusCode(), "the slash command POST must be accepted: " + command.body());
        stream.await(s -> s.contains("pong"), "the slash command's reply must arrive on the stream");

        var prompt = post(AGENT_PATH, id, "hello agent");
        assertEquals(200, prompt.statusCode(), "the prompt POST must be accepted: " + prompt.body());
        stream.await(s -> s.contains("Agent received: hello agent"), "the prompt's reply must arrive on the stream");
        stream.close();
    }

    /**
     * A second streaming client reconnecting with the run id of a run still
     * going gets what the run sent so far, then the rest as the run sends it,
     * as an SSE or WebSocket client does.
     */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aReattachWithTheRunIdGetsTheBacklogThenTheRestOnTheStream() throws Exception {
        var origin = open(StreamingTransportTestEndpoint.PATH, null, null);
        var originId = origin.assignedId();
        assertEquals(200, post(StreamingTransportTestEndpoint.PATH, originId, "hold:live").statusCode());
        origin.await(s -> s.contains("part-1:hold:live"), "the run's first part must reach its own stream");
        awaitTrue(() -> StreamingTransportTestEndpoint.RUN_IDS.containsKey("hold:live"), "the run must start");
        var runId = StreamingTransportTestEndpoint.RUN_IDS.get("hold:live");

        var reattach = open(StreamingTransportTestEndpoint.PATH, null, runId);
        reattach.assignedId();
        reattach.await(s -> s.contains("part-1:hold:live"), "the reattach must get the backlog");
        assertFalse(reattach.text().contains("part-2:hold:live"), "the run has not sent its second part yet");

        StreamingTransportTestEndpoint.RELEASE.countDown();
        reattach.await(s -> s.contains("part-2:hold:live") && s.contains("\"type\":\"complete\""),
                "the reattach must keep receiving the run through complete");
        origin.close();
        reattach.close();
    }

    private StreamingConnection open(String path, String token, String runId) {
        var builder = HttpRequest.newBuilder(uri(path, "0", token)).GET();
        if (runId != null) {
            builder.header("X-Atmosphere-Run-Id", runId);
        }
        var connection = new StreamingConnection();
        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.fromSubscriber(connection));
        return connection;
    }

    private HttpResponse<String> post(String path, String trackingId, String message) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(uri(path, trackingId, null))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(message))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The query string atmosphere.js sends on {@code transport: 'streaming'} with the protocol on. */
    private URI uri(String path, String trackingId, String token) {
        return URI.create(server.getBaseUrl() + path
                + "?X-Atmosphere-tracking-id=" + trackingId
                + "&X-Atmosphere-Framework=5.0.0"
                + "&X-Atmosphere-Transport=streaming"
                + "&X-Atmosphere-TrackMessageSize=true"
                + "&X-atmo-protocol=true"
                + (token != null ? "&token=" + token : ""));
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(20);
        }
    }

    /** The body of one streaming GET, read as it arrives. */
    private static final class StreamingConnection implements Flow.Subscriber<List<ByteBuffer>> {

        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription.set(s);
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            synchronized (received) {
                for (var buffer : buffers) {
                    var bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    received.writeBytes(bytes);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }

        String text() {
            synchronized (received) {
                return received.toString(StandardCharsets.UTF_8);
            }
        }

        /** The id the protocol handshake assigned, written first on the stream. */
        String assignedId() throws InterruptedException {
            await(s -> ASSIGNED_ID.matcher(s).find(), "the stream must carry the assigned id");
            var matcher = ASSIGNED_ID.matcher(text());
            assertTrue(matcher.find());
            return matcher.group();
        }

        void await(Predicate<String> condition, String message) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!condition.test(text())) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError(message + ", got: " + text());
                }
                Thread.sleep(20);
            }
        }

        void close() {
            var s = subscription.get();
            if (s != null) {
                s.cancel();
            }
        }
    }
}
