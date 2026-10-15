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
package org.atmosphere.integrationtests.ai.longpolling;

import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A long-polling client over real HTTP is one connection across its polls:
 * {@code @Ready} runs once for it, as for one WebSocket connection, and what it
 * set on the first poll's request is what a prompt dispatched to a later poll
 * reads. A long-polling client reconnecting with a run id is answered with the
 * run's replay at once, not at its poll's suspend timeout, and a later poll
 * carrying the run id only with what the run added since.
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AiEndpointLongPollingConnectionTest {

    private static final Pattern RUN_ID = Pattern.compile("\"key\":\"X-Atmosphere-Run-Id\",\"value\":\"([^\"]+)\"");

    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.ai.longpolling")
                .withInitParam("org.atmosphere.annotation.packages", "org.atmosphere.ai.processor");
        server.start();
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // The framework initialises on the first request.
        httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterAll
    public void tearDown() throws Exception {
        // Polls may still be suspended by design; close() would wait for them.
        httpClient.shutdownNow();
        server.close();
    }

    @BeforeEach
    public void reset() {
        LongPollingConnectionTestEndpoint.READY.clear();
        LongPollingConnectionTestEndpoint.PROMPTS.clear();
        LongPollingConnectionTestEndpoint.RUN_IDS.clear();
        LongPollingConnectionTestEndpoint.RELEASE.countDown();
        LongPollingConnectionTestEndpoint.RELEASE = new CountDownLatch(1);
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void readyRunsOnceAcrossTheClientsPollsAndItsAttributesReachLaterPolls() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        var first = poll(id, "alice-token", null);
        awaitSuspended(id);

        // The prompt runs on the first poll; the reply is handed over whole: the
        // waiting poll returns empty and the next one takes the reply.
        assertEquals(200, post(id, "one").get(10, TimeUnit.SECONDS).statusCode());
        first.get(10, TimeUnit.SECONDS);
        var reply = poll(id, "alice-token", null).get(10, TimeUnit.SECONDS).body();
        assertTrue(reply.contains("reply-to:one"), "the next poll must take the reply: " + reply);

        // A third poll, suspended: the next prompt is dispatched to it.
        poll(id, "alice-token", null);
        awaitSuspended(id);
        assertEquals(200, post(id, "two").get(10, TimeUnit.SECONDS).statusCode());
        awaitTrue(() -> LongPollingConnectionTestEndpoint.PROMPTS.size() == 2, "both prompts must run");

        assertEquals(1, LongPollingConnectionTestEndpoint.READY.get(id),
                "@Ready must run once for the client's polls, as for one WebSocket connection");
        assertEquals(List.of("one|alice-token", "two|alice-token"), LongPollingConnectionTestEndpoint.PROMPTS,
                "a prompt dispatched to a later poll must read the ai.userId @Ready set for the connection");
    }

    /**
     * A client that polls again while its previous poll is still suspended on the
     * server (a proxy whose read timeout is shorter than the suspend made it give
     * up on that poll): the broadcaster closes the old poll while it suspends the
     * new one, and that departure, of a poll the new one replaces, must not end
     * the client's connection and run {@code @Ready} again.
     */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aPollReplacingTheClientsStillSuspendedPollDoesNotRunReadyAgain() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        var first = poll(id, "alice-token", null);
        awaitSuspended(id);
        var firstResource = server.getFramework().atmosphereFactory().findResource(id).orElseThrow();

        var second = poll(id, "alice-token", null);
        awaitTrue(() -> server.getFramework().atmosphereFactory().findResource(id)
                .map(r -> r != firstResource && r.isSuspended()).orElse(false), "the second poll must be suspended");
        var secondResource = server.getFramework().atmosphereFactory().findResource(id).orElseThrow();
        poll(id, "alice-token", null);
        awaitTrue(() -> server.getFramework().atmosphereFactory().findResource(id)
                .map(r -> r != secondResource && r.isSuspended()).orElse(false), "the third poll must be suspended");

        assertEquals(200, first.get(10, TimeUnit.SECONDS).statusCode(), "the server closes the replaced first poll");
        assertEquals(200, second.get(10, TimeUnit.SECONDS).statusCode(), "the server closes the replaced second poll");
        assertEquals(1, LongPollingConnectionTestEndpoint.READY.get(id),
                "@Ready must run once for a client whose polls replace its still suspended ones");
        assertEquals(200, post(id, "after-replace").get(10, TimeUnit.SECONDS).statusCode());
        awaitTrue(() -> LongPollingConnectionTestEndpoint.PROMPTS.contains("after-replace|alice-token"),
                "a prompt dispatched to the latest poll must read the ai.userId @Ready set for the connection");
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aReconnectWithTheRunIdIsAnsweredWithTheReplayAtOnce() throws Exception {
        var id = "lp-" + UUID.randomUUID();
        var first = poll(id, null, null);
        awaitSuspended(id);
        assertEquals(200, post(id, "replay-me").get(10, TimeUnit.SECONDS).statusCode());
        first.get(10, TimeUnit.SECONDS);
        var reply = poll(id, null, null).get(10, TimeUnit.SECONDS).body();
        var matcher = RUN_ID.matcher(reply);
        assertTrue(matcher.find(), "the reply must name its run: " + reply);
        var runId = matcher.group(1);

        // The client reconnects under a new tracking id, carrying the run id. The
        // endpoint suspends its polls for 120 s: the replay must not wait for that.
        var reattach = poll("lp-" + UUID.randomUUID(), null, runId).get(10, TimeUnit.SECONDS);

        assertEquals(200, reattach.statusCode());
        assertTrue(reattach.body().contains("reply-to:replay-me"),
                "the reattaching poll must be answered with the run's replay: " + reattach.body());
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aSecondRunIdPollWithNothingNewIsNotAnsweredWithTheReplayAgain() throws Exception {
        var origin = "lp-" + UUID.randomUUID();
        var first = poll(origin, null, null);
        awaitSuspended(origin);
        assertEquals(200, post(origin, "replay-once").get(10, TimeUnit.SECONDS).statusCode());
        first.get(10, TimeUnit.SECONDS);
        var runId = runIdOf(poll(origin, null, null).get(10, TimeUnit.SECONDS).body());

        var id = "lp-" + UUID.randomUUID();
        var replay = poll(id, null, runId).get(10, TimeUnit.SECONDS);
        assertTrue(replay.body().contains("reply-to:replay-once"), "the first poll gets the replay: " + replay.body());

        // atmosphere.js re-sends the run id on its next poll. The run has nothing
        // new for this client: the poll must wait like any other one, not come
        // straight back with the same replay (a request loop for the registry TTL).
        var again = poll(id, null, runId);
        assertThrows(TimeoutException.class, () -> again.get(2, TimeUnit.SECONDS),
                "a run-id poll with nothing new must stay suspended");
        assertEquals(200, post(id, "next").get(10, TimeUnit.SECONDS).statusCode());
        var answer = again.get(10, TimeUnit.SECONDS).body();
        assertFalse(answer.contains("reply-to:replay-once"), "the replay must not be sent twice: " + answer);
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aRunIdPollOfARunStillGoingGetsOnlyWhatTheRunAddedSince() throws Exception {
        var origin = "lp-" + UUID.randomUUID();
        poll(origin, null, null);
        awaitSuspended(origin);
        assertEquals(200, post(origin, "hold:live").get(10, TimeUnit.SECONDS).statusCode());
        // Over long-polling the run's own client gets its reply whole at the end; a
        // client learns the id of a run still going over another transport, before
        // it falls back to long-polling.
        awaitTrue(() -> LongPollingConnectionTestEndpoint.RUN_IDS.containsKey("hold:live"), "the run must start");
        var runId = LongPollingConnectionTestEndpoint.RUN_IDS.get("hold:live");

        var id = "lp-" + UUID.randomUUID();
        var backlog = poll(id, null, runId).get(10, TimeUnit.SECONDS).body();
        assertTrue(backlog.contains("part-1:hold:live"), "the reattach gets the backlog: " + backlog);

        var next = poll(id, null, runId);
        assertThrows(TimeoutException.class, () -> next.get(2, TimeUnit.SECONDS),
                "nothing new yet: the poll must wait for the run's next frame");
        LongPollingConnectionTestEndpoint.RELEASE.countDown();
        var update = next.get(10, TimeUnit.SECONDS).body();
        assertTrue(update.contains("part-2:hold:live"), "the poll must return with the run's next frame: " + update);
        assertFalse(update.contains("part-1:hold:live"), "the backlog must not be sent twice: " + update);
    }

    private static String runIdOf(String body) {
        var matcher = RUN_ID.matcher(body);
        assertTrue(matcher.find(), "the reply must name its run: " + body);
        return matcher.group(1);
    }

    private CompletableFuture<HttpResponse<String>> poll(String trackingId, String token, String runId) {
        var builder = HttpRequest.newBuilder(uri(trackingId, token)).GET();
        if (runId != null) {
            builder.header("X-Atmosphere-Run-Id", runId);
        }
        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> post(String trackingId, String prompt) {
        return httpClient.sendAsync(HttpRequest.newBuilder(uri(trackingId, null))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(prompt))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String trackingId, String token) {
        return URI.create(server.getBaseUrl() + LongPollingConnectionTestEndpoint.PATH
                + "?X-Atmosphere-tracking-id=" + trackingId
                + "&X-Atmosphere-Transport=long-polling"
                + "&X-Atmosphere-Framework=5.0.0"
                + (token != null ? "&token=" + token : ""));
    }

    private void awaitSuspended(String trackingId) throws InterruptedException {
        awaitTrue(() -> server.getFramework().atmosphereFactory().findResource(trackingId)
                        .map(r -> r.isSuspended()).orElse(false),
                trackingId + " must have a suspended poll");
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(20);
        }
    }
}
