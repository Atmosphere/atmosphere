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
package org.atmosphere.integrationtests.ai.detach;

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A page reloaded before its run wrote a frame — the run id received, the
 * model not answered yet — reattaches with the run id and receives the whole
 * answer and its {@code complete} frame. The run went on with the connection
 * it started on gone: its request, which the reload cancelled, is no longer
 * readable, so whatever the run reads of it after the reload (the streaming
 * session's request attribute, an interceptor's {@code preProcess} and
 * {@code beforeCompletion}) must read what the request held when the prompt
 * was admitted, not fail.
 *
 * <p>The model is a fake OpenAI-compatible server the built-in runtime is
 * pointed at by init-params; the {@code LLM_*} environment variables, which
 * override them, must not be set for the test's JVM.</p>
 */
@Tag("core")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ReloadBeforeFirstFrameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern HANDSHAKE = Pattern.compile("^([0-9a-fA-F-]{36})\\|");
    private static final String RUN_ID_KEY = "X-Atmosphere-Run-Id";
    private static final int TOKENS = 20;
    /** The tokens a {@code hold mid-answer} prompt is answered with before the model waits for {@link #MID_ANSWER}. */
    private static final int HELD_AFTER = 5;
    private static volatile CountDownLatch MID_ANSWER = new CountDownLatch(1);
    /** Holds the turn's setup (its fact resolution) while not {@code null}. */
    private static volatile CountDownLatch SLOW_FACTS;

    private HttpServer model;
    private EmbeddedAtmosphereServer server;
    private HttpClient httpClient;

    @BeforeAll
    public void setUp() throws Exception {
        for (var env : List.of("LLM_BASE_URL", "LLM_API_KEY", "LLM_MODE", "LLM_MODEL")) {
            var value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                throw new IllegalStateException(env + " is set for this JVM and overrides the fake model; unset it");
            }
        }
        model = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var holds = body.contains("hold mid-answer");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                if (body.contains("use the tool") && !body.contains("tool_call_id")) {
                    // The model's first answer to a tool prompt: a call of the agent's tool.
                    out.write(("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                            + "\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                            + "\"arguments\":\"{\\\"topic\\\":\\\"teeth\\\"}\"}}]},\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                for (var i = 0; i < TOKENS; i++) {
                    if (holds && i == HELD_AFTER && !MID_ANSWER.await(30, TimeUnit.SECONDS)) {
                        return;
                    }
                    var chunk = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{\"content\":\"t" + i + " \"},\"finish_reason\":null}]}\n\n";
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(5);
                }
                out.write(("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        model.start();
        server = new EmbeddedAtmosphereServer()
                .withAnnotationPackage("org.atmosphere.integrationtests.ai.detach")
                .withInitParam("org.atmosphere.annotation.packages",
                        "org.atmosphere.agent.processor,org.atmosphere.ai.processor")
                .withInitParam("org.atmosphere.ai.llmMode", "local")
                .withInitParam("org.atmosphere.ai.llmModel", "fake-model")
                .withInitParam("org.atmosphere.ai.llmBaseUrl",
                        "http://127.0.0.1:" + model.getAddress().getPort() + "/v1");
        server.start();
        // The turn's setup reads the facts of the request before the @Prompt
        // method runs; SLOW_FACTS holds it there while a test needs it to.
        server.getFramework().getAtmosphereConfig().properties().put(
                org.atmosphere.ai.facts.FactResolver.FACT_RESOLVER_PROPERTY,
                (org.atmosphere.ai.facts.FactResolver) request -> {
                    var hold = SLOW_FACTS;
                    try {
                        if (hold != null && !hold.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("facts never released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return org.atmosphere.ai.facts.FactResolver.FactBundle.empty();
                });
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        httpClient.send(HttpRequest.newBuilder(URI.create(server.getBaseUrl() + "/")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    @AfterAll
    public void tearDown() throws Exception {
        DetachTestEndpoint.RELEASE.countDown();
        MID_ANSWER.countDown();
        var facts = SLOW_FACTS;
        if (facts != null) {
            facts.countDown();
        }
        // setUp may have stopped before building these (an LLM_* variable set in the JVM).
        if (httpClient != null) {
            httpClient.shutdownNow();
        }
        if (server != null) {
            server.close();
        }
        if (model != null) {
            model.stop(0);
        }
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aPageReloadedBeforeTheFirstFrameReceivesTheWholeAnswer() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        RequestReadingInterceptor.READS.clear();
        var page = connect("0", null);
        page.socket.sendText("hello", true).join();
        page.await(frames -> runId(frames) != null, "the page must receive the run id");
        var runId = runId(page.frames());
        assertEquals(List.of(), texts(page.frames()), "no frame of the answer before the reload");

        // The reload: the page goes away (a WebSocket going-away close) and comes
        // back under a new tracking id with the run id it kept.
        page.socket.sendClose(1001, "reload").get(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        var reloaded = connect("0", runId);
        DetachTestEndpoint.RELEASE.countDown();

        reloaded.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal),
                "the reloaded page must receive the end of the run");
        Thread.sleep(300);
        var terminal = reloaded.frames().stream().filter(ReloadBeforeFirstFrameTest::terminal).toList();
        assertEquals(List.of("complete"), terminal.stream().map(f -> f.path("type").asString()).toList(),
                "the run completes: " + describe(reloaded.frames()));
        var expected = new ArrayList<String>();
        for (var i = 0; i < TOKENS; i++) {
            expected.add("t" + i + " ");
        }
        assertEquals(String.join("", expected), String.join("", texts(reloaded.frames())),
                "the reloaded page receives the whole answer, once");
        assertEquals(List.of("stamped:hello"), RequestReadingInterceptor.READS,
                "the interceptor reads, after the reload, what it stamped on the request");
        reloaded.close();
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void anInterceptorReadsTheRequestAfterAReloadInTheMiddleOfTheAnswer() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachTestEndpoint.RELEASE.countDown();
        MID_ANSWER = new CountDownLatch(1);
        RequestReadingInterceptor.READS.clear();
        var page = connect("0", null);
        page.socket.sendText("hold mid-answer", true).join();
        page.await(frames -> texts(frames).size() == HELD_AFTER, "the page must see the start of the answer");
        var runId = runId(page.frames());

        page.socket.sendClose(1001, "reload").get(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        var reloaded = connect("0", runId);
        MID_ANSWER.countDown();

        reloaded.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal),
                "the reloaded page must receive the end of the run");
        Thread.sleep(300);
        assertEquals(List.of("complete"), reloaded.frames().stream().filter(ReloadBeforeFirstFrameTest::terminal)
                .map(f -> f.path("type").asString()).toList(), "the run completes: " + describe(reloaded.frames()));
        assertEquals(List.of("stamped:hold mid-answer"), RequestReadingInterceptor.READS,
                "beforeCompletion reads, after the reload, what preProcess stamped on the request");
        reloaded.close();
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void anAgentWhoseModelCallsAToolAnswersAPageReloadedBeforeTheFirstFrame() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachToolAgent.LOOKUPS.clear();
        var path = "/atmosphere/agent/" + DetachToolAgent.NAME;
        var page = connect(path, "0", null);
        page.socket.sendText("use the tool", true).join();
        page.await(frames -> runId(frames) != null, "the page must receive the run id");
        var runId = runId(page.frames());
        assertEquals(List.of(), texts(page.frames()), "no frame of the answer before the reload");

        page.socket.sendClose(1001, "reload").get(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        var reloaded = connect(path, "0", runId);
        DetachTestEndpoint.RELEASE.countDown();

        reloaded.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal),
                "the reloaded page must receive the end of the run");
        Thread.sleep(300);
        assertEquals(List.of("complete"), reloaded.frames().stream().filter(ReloadBeforeFirstFrameTest::terminal)
                .map(f -> f.path("type").asString()).toList(), "the run completes: " + describe(reloaded.frames()));
        var expected = new StringBuilder();
        for (var i = 0; i < TOKENS; i++) {
            expected.append('t').append(i).append(' ');
        }
        assertEquals(expected.toString(), String.join("", texts(reloaded.frames())),
                "the reloaded page receives the whole answer the model gave after the tool, once");
        assertEquals(List.of("teeth@" + path), DetachToolAgent.LOOKUPS,
                "the tool read, after the reload, the request the prompt came on");
        reloaded.close();
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void theRunIdArrivesBeforeTheTurnsSetupAndAReloadDuringItGetsTheWholeAnswer() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachTestEndpoint.RELEASE.countDown();
        var facts = new CountDownLatch(1);
        SLOW_FACTS = facts;
        try {
            var page = connect("0", null);
            page.socket.sendText("hello", true).join();
            // The setup (here the facts of the request) has not finished: the
            // run id is the page's already.
            page.await(frames -> runId(frames) != null,
                    "the page must receive the run id while the turn's setup runs");
            var runId = runId(page.frames());

            page.socket.sendClose(1001, "reload").get(5, TimeUnit.SECONDS);
            Thread.sleep(500);
            var reloaded = connect("0", runId);
            facts.countDown();

            reloaded.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal),
                    "the reloaded page must receive the end of the run");
            Thread.sleep(300);
            assertEquals(List.of("complete"), reloaded.frames().stream().filter(ReloadBeforeFirstFrameTest::terminal)
                    .map(f -> f.path("type").asString()).toList(), "the run completes: " + describe(reloaded.frames()));
            var expected = new StringBuilder();
            for (var i = 0; i < TOKENS; i++) {
                expected.append('t').append(i).append(' ');
            }
            assertEquals(expected.toString(), String.join("", texts(reloaded.frames())),
                    "the reloaded page receives the whole answer, once");
            reloaded.close();
        } finally {
            SLOW_FACTS = null;
            facts.countDown();
        }
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aTurnRefusedAfterItsRunIdWasSentEndsForThePageThatReattached() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachTestEndpoint.RELEASE.countDown();
        var facts = new CountDownLatch(1);
        SLOW_FACTS = facts;
        try {
            var page = connect("0", null);
            page.socket.sendText(RequestReadingInterceptor.DENIED, true).join();
            page.await(frames -> runId(frames) != null,
                    "the page must receive the run id while the turn's setup runs");
            var runId = runId(page.frames());

            page.socket.sendClose(1001, "reload").get(5, TimeUnit.SECONDS);
            Thread.sleep(500);
            var reloaded = connect("0", runId);
            facts.countDown();

            reloaded.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal),
                    "the reloaded page must receive the end of the refused run");
            Thread.sleep(300);
            var ends = reloaded.frames().stream().filter(ReloadBeforeFirstFrameTest::terminal).toList();
            assertEquals(List.of("error"), ends.stream().map(f -> f.path("type").asString()).toList(),
                    "the refused run ends with one error frame: " + describe(reloaded.frames()));
            assertEquals(RequestReadingInterceptor.DENIAL, ends.get(0).path("data").asString());
            assertEquals(List.of(), texts(reloaded.frames()), "a refused run writes no answer");
            reloaded.close();
        } finally {
            SLOW_FACTS = null;
            facts.countDown();
        }
    }

    private static final String AGENT_PATH = "/atmosphere/agent/" + DetachToolAgent.NAME;

    /** A prompt wrapped with the run id its client minted for it, as atmosphere.js sends it. */
    private static String envelope(String runId, String prompt) {
        return "{\"clientRunId\":\"" + runId + "\",\"prompt\":\"" + prompt + "\"}";
    }

    private static String expectedAnswer() {
        var expected = new StringBuilder();
        for (var i = 0; i < TOKENS; i++) {
            expected.append('t').append(i).append(' ');
        }
        return expected.toString();
    }

    /** The texts of {@code frames} of session {@code sessionId}, each seq once. */
    private static String answerOf(List<JsonNode> frames) {
        var bySeq = new java.util.TreeMap<Long, String>();
        String session = null;
        for (var frame : frames) {
            if ("streaming-text".equals(frame.path("type").asString())) {
                if (session == null) {
                    session = frame.path("sessionId").asString();
                }
                if (session.equals(frame.path("sessionId").asString())) {
                    bySeq.putIfAbsent(frame.path("seq").asLong(), frame.path("data").asString());
                }
            }
        }
        return String.join("", bySeq.values());
    }

    private static boolean unknownRun(List<JsonNode> frames, String runId) {
        return frames.stream().anyMatch(f -> "metadata".equals(f.path("type").asString())
                && "X-Atmosphere-Run-Unknown".equals(f.path("key").asString())
                && runId.equals(f.path("value").asString()));
    }

    /**
     * A page reloaded before the server received its prompt: the server never
     * saw the run id the page minted for it. The reloaded page reconnects with
     * that id, is told the server has no run by it, and sends the prompt it
     * kept again under the same id: the turn runs once, under that id, and the
     * page gets the whole answer.
     */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aPromptTheServerNeverReceivedRunsOnceUnderItsIdWhenTheReloadedPageSendsItAgain() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachTestEndpoint.RELEASE.countDown();
        DetachToolAgent.LOOKUPS.clear();
        var runId = java.util.UUID.randomUUID().toString();

        // The prompt never reached the server: the page reloaded first.
        var reloaded = connect(AGENT_PATH, "0", runId);
        reloaded.await(frames -> unknownRun(frames, runId), "the reloaded page is told the run is unknown");
        reloaded.socket.sendText(envelope(runId, "use the tool"), true).join();

        reloaded.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal),
                "the resent prompt runs to its end");
        Thread.sleep(300);
        assertEquals(runId, runId(reloaded.frames()), "the run takes the id the page minted");
        assertEquals(expectedAnswer(), answerOf(reloaded.frames()), "the page gets the whole answer");
        assertEquals(1, DetachToolAgent.LOOKUPS.size(), "the turn's tool ran once: " + DetachToolAgent.LOOKUPS);
        reloaded.close();
    }

    /**
     * The same prompt arrives twice under its id — sent again after a reload
     * though the first had arrived, or two copies racing — and runs once: a
     * later copy reattaches to the run (replaying what it wrote, a finished
     * run included) instead of running a second time.
     */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aPromptSentAgainUnderItsIdRunsOnce() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachTestEndpoint.RELEASE.countDown();
        DetachToolAgent.LOOKUPS.clear();
        var runId = java.util.UUID.randomUUID().toString();

        var page = connect(AGENT_PATH, "0", null);
        // Two copies back to back, racing for the id.
        page.socket.sendText(envelope(runId, "use the tool"), true).join();
        page.socket.sendText(envelope(runId, "use the tool"), true).join();
        page.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal), "the run ends");
        Thread.sleep(500);
        assertEquals(1, DetachToolAgent.LOOKUPS.size(), "two copies racing run once: " + DetachToolAgent.LOOKUPS);
        page.close();

        // Sent again after the run finished, from a reloaded page.
        var reloaded = connect(AGENT_PATH, "0", null);
        reloaded.socket.sendText(envelope(runId, "use the tool"), true).join();
        reloaded.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal),
                "the reloaded page is replayed the finished run");
        Thread.sleep(300);
        assertEquals(1, DetachToolAgent.LOOKUPS.size(), "a finished run is replayed, not run again");
        assertEquals(expectedAnswer(), answerOf(reloaded.frames()), "the replay holds the whole answer");
        reloaded.close();
    }

    /**
     * A run id is bound to the caller whose prompt it named: another user can
     * neither reattach to the run by it nor take it for a prompt of its own —
     * that prompt runs, under an id of the server's.
     */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void anotherUserCanNeitherAttachToNorAdoptSomeoneElsesRunId() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachTestEndpoint.RELEASE.countDown();
        DetachToolAgent.LOOKUPS.clear();
        var runId = java.util.UUID.randomUUID().toString();

        var alice = connect(AGENT_PATH + "?token=alice", "0", null);
        alice.socket.sendText(envelope(runId, "use the tool"), true).join();
        alice.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal), "alice's run ends");
        assertEquals(runId, runId(alice.frames()));

        var bob = connect(AGENT_PATH + "?token=bob", "0", runId);
        Thread.sleep(500);
        assertEquals(List.of(), texts(bob.frames()), "bob is replayed nothing of alice's run");
        bob.socket.sendText(envelope(runId, "use the tool"), true).join();
        bob.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal), "bob's prompt runs");
        Thread.sleep(300);
        var bobRun = runId(bob.frames());
        assertNotNull(bobRun, "bob's prompt runs under a run of its own");
        org.junit.jupiter.api.Assertions.assertNotEquals(runId, bobRun, "bob does not take alice's run id");
        assertEquals(2, DetachToolAgent.LOOKUPS.size(), "bob's prompt ran as a run of its own: " + DetachToolAgent.LOOKUPS);
        alice.close();
        bob.close();
    }

    /** A client that mints no run id sends its prompt alone, as before: the run gets an id of the server's. */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test
    public void aPromptWithoutAClientRunIdRunsAsBefore() throws Exception {
        DetachTestEndpoint.RELEASE = new CountDownLatch(1);
        DetachTestEndpoint.RELEASE.countDown();
        DetachToolAgent.LOOKUPS.clear();
        var page = connect(AGENT_PATH, "0", null);
        page.socket.sendText("use the tool", true).join();
        page.await(frames -> frames.stream().anyMatch(ReloadBeforeFirstFrameTest::terminal), "the run ends");
        Thread.sleep(300);
        assertNotNull(runId(page.frames()), "the server names the run");
        assertEquals(expectedAnswer(), answerOf(page.frames()));
        assertEquals(1, DetachToolAgent.LOOKUPS.size());
        page.close();
    }

    private static boolean terminal(JsonNode frame) {
        var type = frame.path("type").asString();
        return "complete".equals(type) || "error".equals(type) || "cancelled".equals(type);
    }

    private static String runId(List<JsonNode> frames) {
        for (var frame : frames) {
            if ("metadata".equals(frame.path("type").asString()) && RUN_ID_KEY.equals(frame.path("key").asString())) {
                return frame.path("value").asString();
            }
        }
        return null;
    }

    private static List<String> texts(List<JsonNode> frames) {
        return frames.stream().filter(f -> "streaming-text".equals(f.path("type").asString()))
                .map(f -> f.path("data").asString()).toList();
    }

    private static String describe(List<JsonNode> frames) {
        return frames.stream().map(f -> f.path("type").asString() + ":" + (f.path("data").isString() ? f.path("data").asString() : f.path("data").toString()))
                .collect(Collectors.joining(" "));
    }

    private Page connect(String trackingId, String runId) throws Exception {
        return connect(DetachTestEndpoint.PATH, trackingId, runId);
    }

    private Page connect(String path, String trackingId, String runId) throws Exception {
        var url = "ws://localhost:" + server.getPort() + path
                + (path.contains("?") ? "&" : "?") + "X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0&X-atmo-protocol=true"
                + "&Content-Type=application/json&X-Atmosphere-tracking-id=" + trackingId
                + (runId != null ? "&X-Atmosphere-Run-Id=" + runId : "");
        var page = new Page();
        page.socket = httpClient.newWebSocketBuilder().buildAsync(URI.create(url), page).get(10, TimeUnit.SECONDS);
        page.assigned.get(10, TimeUnit.SECONDS);
        assertNotNull(page.socket);
        return page;
    }

    /** One page's WebSocket, recording every AI frame written to it. */
    private static final class Page implements WebSocket.Listener {
        private final CompletableFuture<String> assigned = new CompletableFuture<>();
        private final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private volatile WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                var message = partial.toString();
                partial.setLength(0);
                var handshake = HANDSHAKE.matcher(message);
                if (!assigned.isDone() && handshake.find()) {
                    assigned.complete(handshake.group(1));
                } else {
                    for (var json : jsonObjects(message)) {
                        try {
                            var node = MAPPER.readTree(json);
                            if (node.hasNonNull("sessionId") && node.has("seq")) {
                                frames.add(node);
                            }
                        } catch (RuntimeException e) {
                            // Not a frame of the streaming protocol.
                        }
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        List<JsonNode> frames() {
            return List.copyOf(frames);
        }

        void await(Predicate<List<JsonNode>> condition, String message) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                if (condition.test(frames())) {
                    return;
                }
                Thread.sleep(20);
            }
            throw new AssertionError(message + ": " + describe(frames()));
        }

        void close() {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                socket.abort();
            }
        }
    }

    /** The JSON objects in {@code message}, skipping what is between them (length prefixes, padding). */
    static List<String> jsonObjects(String message) {
        var out = new ArrayList<String>();
        var depth = 0;
        var start = -1;
        var inString = false;
        for (var i = 0; i < message.length(); i++) {
            var c = message.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"' && depth > 0) {
                inString = true;
            } else if (c == '{') {
                if (depth++ == 0) {
                    start = i;
                }
            } else if (c == '}' && depth > 0 && --depth == 0) {
                out.add(message.substring(start, i + 1));
            }
        }
        return out;
    }
}
