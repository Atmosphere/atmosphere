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
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A WebSocket that comes back with the run id of the answer it was streaming
 * (a page reloaded mid-answer) is replayed that answer and streams the rest,
 * on Quarkus's default servlet container. There the JSR 356 endpoint opens the
 * connection on a Vert.x event loop, and a write waits for the previous send
 * to complete, which also runs on that loop: written from the open callback,
 * the replay blocked the loop until the write timed out (about a minute,
 * BlockedThreadChecker warning all along), then failed with nothing replayed.
 */
public class AiReattachWebSocketTest {

    private static final int TOKENS = 30;
    private static final long TOKEN_INTERVAL_MS = 150;
    private static final Pattern RUN_ID = Pattern.compile(
            "\"key\"\\s*:\\s*\"X-Atmosphere-Run-Id\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"");

    /** Event-loop warnings: "Thread[vert.x-eventloop-thread-N] has been blocked for ... ms". */
    private static final Predicate<LogRecord> BLOCKED_LOOP = r -> r.getMessage() != null
            && r.getMessage().contains("has been blocked for");

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            // The WebSocket server, as quarkus-ai-chat declares it: the JSR 356
            // endpoint then opens connections on a Vert.x event loop.
            .setForcedDependencies(List.of(new ArtifactDependency("io.quarkus", "quarkus-websockets-deployment",
                    null, "jar", System.getProperty("quarkus.version", "3.39.5"))))
            .withApplicationRoot(jar -> jar.addClasses(AiReattachWebSocketTest.class, SlowAnswer.class))
            // Indexed so the build-time scan finds AiEndpointProcessor, as the
            // LangChain4j bridge quarkus-ai-chat runs on indexes it.
            .overrideConfigKey("quarkus.index-dependency.atmosphere-ai.group-id", "org.atmosphere")
            .overrideConfigKey("quarkus.index-dependency.atmosphere-ai.artifact-id", "atmosphere-ai")
            .overrideConfigKey("quarkus.atmosphere.packages", "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .setLogRecordPredicate(BLOCKED_LOOP);

    /** Streams {@value #TOKENS} numbered tokens, one every {@value #TOKEN_INTERVAL_MS} ms. */
    @AiEndpoint(path = "/atmosphere/slow-answer", requires = {AiCapability.TEXT_STREAMING})
    @AgentScope(unrestricted = true, justification = "Test fixture: streams numbered tokens for any prompt.")
    public static class SlowAnswer {

        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            try {
                for (int i = 0; i < TOKENS; i++) {
                    session.send("t" + i + " ");
                    Thread.sleep(TOKEN_INTERVAL_MS);
                }
                session.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.error(e);
            }
        }
    }

    @TestHTTPResource("/atmosphere/slow-answer")
    URL endpoint;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    public void aWebSocketBackWithItsRunIdIsReplayedTheAnswerPromptly() throws Exception {
        var first = new CopyOnWriteArrayList<String>();
        var ws = connect("", first);
        ws.sendText("count", true).get(5, TimeUnit.SECONDS);

        var runId = awaitRunId(first);
        awaitFrames(first, f -> f.contains("t2 "), 10_000, "the first connection never streamed t2");
        // The connection drops, as on a reload: no close frame, the run goes on.
        ws.abort();

        var second = new CopyOnWriteArrayList<String>();
        var reopened = System.nanoTime();
        connect("&X-Atmosphere-Run-Id=" + runId, second);

        // The replay starts at once: t0 is the first token of the run.
        awaitFrames(second, f -> f.contains("\"t0 \""), 5_000,
                "the replay of t0 did not arrive within 5 s of the reconnect");
        awaitFrames(second, f -> f.contains("\"t1 \""), 5_000,
                "the replay stopped after its first frame");
        var replayMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reopened);
        assertTrue(replayMillis < 5_000, "the replay took " + replayMillis + " ms");

        // The rest of the run follows, through its complete frame.
        awaitFrames(second, f -> f.contains("\"complete\""), TOKENS * TOKEN_INTERVAL_MS + 10_000,
                "the reattached connection never received the run's complete frame");
        var text = String.join("", second);
        for (int i = 0; i < TOKENS; i++) {
            assertTrue(text.contains("\"t" + i + " \""), "token t" + i + " missing from the reattached stream");
        }

        unitTest.assertLogRecords(records -> assertEquals(List.of(),
                records.stream().map(LogRecord::getMessage).toList(),
                "no Vert.x event loop may be blocked by the reattach"));
    }

    private WebSocket connect(String extraQuery, List<String> frames) throws Exception {
        var uri = URI.create(endpoint.toString().replaceFirst("^http", "ws")
                + "?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=0" + extraQuery);
        return http.newWebSocketBuilder().buildAsync(uri, new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                partial.append(data);
                if (last) {
                    frames.add(partial.toString());
                    partial.setLength(0);
                }
                webSocket.request(1);
                return null;
            }
        }).get(10, TimeUnit.SECONDS);
    }

    private static String awaitRunId(List<String> frames) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (var frame : frames) {
                var m = RUN_ID.matcher(frame);
                if (m.find()) {
                    return m.group(1);
                }
            }
            Thread.sleep(20);
        }
        return fail("no X-Atmosphere-Run-Id metadata frame: " + frames);
    }

    private static void awaitFrames(List<String> frames, Predicate<String> match, long timeoutMs, String message)
            throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (frames.stream().anyMatch(match)) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message + ": " + frames);
    }
}
