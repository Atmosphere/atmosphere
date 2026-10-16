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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * command, must be answered on that stream, as they are over SSE. Where the
 * streaming and SSE set-up is shared (a prompt posted before {@code @Ready} ran,
 * the endpoint's suspend timeout), both transports are pinned.
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
                        "org.atmosphere.agent.processor,org.atmosphere.ai.processor")
                // Generous, so a host under load cannot end a prompt's wait for its connection's set-up.
                .withInitParam("org.atmosphere.ai.prompt.repollWaitMs", "10000");
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
        HeldReadyTestEndpoint.RELEASE.countDown();
        HeldReadyTestEndpoint.RELEASE = new CountDownLatch(1);
        HeldReadyTestEndpoint.ENTERED = new CountDownLatch(1);
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

    /**
     * The protocol handshake writes the assigned id while the connection is
     * suspended, before {@code @Ready} runs: a prompt posted as soon as the id
     * arrives must wait for {@code @Ready} (and the path params and system
     * prompt) and then run under what it set, not run on a connection still
     * being set up. Over streaming as over SSE.
     */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @ParameterizedTest
    @ValueSource(strings = {"streaming", "sse"})
    public void aPromptPostedWhileReadyRunsWaitsForReady(String transport) throws Exception {
        var stream = open(HeldReadyTestEndpoint.PATH, transport, "held-user", null);
        var id = stream.assignedId();
        assertTrue(HeldReadyTestEndpoint.ENTERED.await(10, TimeUnit.SECONDS), "@Ready must have started");

        var response = httpClient.sendAsync(postRequest(HeldReadyTestEndpoint.PATH, transport, id, "hello"),
                HttpResponse.BodyHandlers.ofString());
        // Time for the POST to reach the server while @Ready is still held.
        Thread.sleep(1_000);
        assertFalse(stream.text().contains("reply-to:hello"),
                "the prompt must not run before @Ready finished, got: " + stream.text().strip());
        HeldReadyTestEndpoint.RELEASE.countDown();

        assertEquals(200, response.get(30, TimeUnit.SECONDS).statusCode(), "the prompt POST must be accepted");
        stream.await(s -> s.contains("reply-to:hello|held-user") && s.contains("\"type\":\"complete\""),
                "the reply must arrive on the stream, under the ai.userId @Ready set");
        stream.close();
    }

    /**
     * A streaming connection is suspended for the endpoint's {@code timeout()},
     * as an SSE one is: the server ends its response once that time has passed,
     * and atmosphere.js reconnects under its tracking id.
     */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @ParameterizedTest
    @ValueSource(strings = {"streaming", "sse"})
    public void aConnectionIsEndedAfterTheEndpointTimeout(String transport) throws Exception {
        var stream = open(ShortTimeoutTestEndpoint.PATH, transport, null, null);
        stream.assignedId();
        var since = System.nanoTime();

        assertTrue(stream.ended.await(ShortTimeoutTestEndpoint.TIMEOUT_MS + 10_000, TimeUnit.MILLISECONDS),
                "the server must end the " + transport + " response after the endpoint timeout");
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - since) >= ShortTimeoutTestEndpoint.TIMEOUT_MS / 2,
                "the response must not end before the endpoint timeout");
    }

    private StreamingConnection open(String path, String token, String runId) {
        return open(path, "streaming", token, runId);
    }

    private StreamingConnection open(String path, String transport, String token, String runId) {
        var builder = HttpRequest.newBuilder(uri(path, transport, "0", token)).GET();
        if (runId != null) {
            builder.header("X-Atmosphere-Run-Id", runId);
        }
        var connection = new StreamingConnection();
        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.fromSubscriber(connection));
        return connection;
    }

    private HttpResponse<String> post(String path, String trackingId, String message) throws Exception {
        return httpClient.send(postRequest(path, "streaming", trackingId, message),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest postRequest(String path, String transport, String trackingId, String message) {
        return HttpRequest.newBuilder(uri(path, transport, trackingId, null))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(message))
                .build();
    }

    /** The query string atmosphere.js sends on {@code transport} with the protocol on. */
    private URI uri(String path, String transport, String trackingId, String token) {
        return URI.create(server.getBaseUrl() + path
                + "?X-Atmosphere-tracking-id=" + trackingId
                + "&X-Atmosphere-Framework=5.0.0"
                + "&X-Atmosphere-Transport=" + transport
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
        /** Counted down when the response ends. */
        private final CountDownLatch ended = new CountDownLatch(1);

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
            ended.countDown();
        }

        @Override
        public void onComplete() {
            ended.countDown();
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
