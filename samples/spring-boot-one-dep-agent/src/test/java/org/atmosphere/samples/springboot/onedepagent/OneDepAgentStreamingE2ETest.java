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
package org.atmosphere.samples.springboot.onedepagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery proof for the Atmosphere-4 blog "Try it" claim:
 * <em>"one dependency + a single {@code @Agent} class is a running streaming
 * chat app."</em>
 *
 * <p>The sample declares exactly one Atmosphere dependency
 * ({@code atmosphere-ai-spring-boot-starter}) and one {@code @Agent} class
 * ({@link ChatAgent}). This test boots that sample on a random port and drives
 * <b>one streaming chat turn end to end</b> over a real WebSocket: it sends a
 * user prompt to the {@code @Agent}'s auto-registered handler at
 * {@code /atmosphere/agent/chat} and asserts that the reply arrives as
 * <b>multiple streamed {@code streaming-text} frames</b> followed by a
 * {@code complete} frame — the exact wire shape the Atmosphere Console renders.</p>
 *
 * <p>Keyless: with no {@code LLM_API_KEY} the framework's built-in demo runtime
 * serves, so the test needs no provider and makes no external network call. The
 * assertion is specifically on <em>streaming</em> (more than one text frame,
 * non-empty reassembled text, then completion) — not merely "the server
 * started".</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OneDepAgentStreamingE2ETest {

    /** Path the {@code AgentProcessor} registers for {@code @Agent(name = "chat")}. */
    private static final String AGENT_PATH = "/atmosphere/agent/chat";

    @LocalServerPort
    private int port;

    @Test
    void oneAgentClassStreamsAChatTurnEndToEnd() throws Exception {
        var frames = new CopyOnWriteArrayList<String>();
        var openLatch = new CountDownLatch(1);
        var completeLatch = new CountDownLatch(1);

        var httpClient = HttpClient.newHttpClient();
        var uri = URI.create("ws://localhost:" + port + AGENT_PATH
                + "?X-Atmosphere-tracking-id=" + UUID.randomUUID()
                + "&X-Atmosphere-Transport=websocket"
                + "&X-Atmosphere-Framework=5.0.0");

        var ws = httpClient.newWebSocketBuilder()
                .buildAsync(uri, new FrameCollector(frames, openLatch, completeLatch))
                .join();

        assertThat(openLatch.await(10, TimeUnit.SECONDS))
                .as("WebSocket should connect to the @Agent handler at " + AGENT_PATH)
                .isTrue();
        // Give the suspend/handshake a beat before firing the prompt.
        Thread.sleep(500);

        // Drive one chat turn. A non-command prompt falls through to the @Prompt
        // method, which calls session.stream(...) — the demo runtime then streams.
        ws.sendText("Hello there, tell me about Atmosphere", true).join();

        assertThat(completeLatch.await(20, TimeUnit.SECONDS))
                .as("the streaming turn must terminate with a 'complete' frame; frames=" + frames)
                .isTrue();

        // Real streaming: more than one streaming-text frame arrived, i.e. tokens
        // were pushed incrementally rather than as a single blob.
        var textFrames = frames.stream().filter(f -> f.contains("\"type\":\"streaming-text\"")).toList();
        assertThat(textFrames)
                .as("expected multiple incremental streaming-text frames (streamed tokens); frames=" + frames)
                .hasSizeGreaterThan(1);

        // The reassembled text the user would see is non-empty.
        var reassembled = textFrames.stream()
                .map(OneDepAgentStreamingE2ETest::extractData)
                .reduce("", String::concat);
        assertThat(reassembled.strip())
                .as("the concatenation of streamed text frames must be a non-empty reply")
                .isNotEmpty();

        // A terminal complete frame was observed.
        assertThat(frames)
                .as("a terminal 'complete' frame must close the stream")
                .anyMatch(f -> f.contains("\"type\":\"complete\""));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        httpClient.close();
    }

    /** Extract the {@code "data":"..."} value from a streaming-text frame (best-effort). */
    private static String extractData(String frame) {
        var key = "\"data\":\"";
        var start = frame.indexOf(key);
        if (start < 0) {
            return "";
        }
        start += key.length();
        var end = frame.indexOf('"', start);
        return end > start ? frame.substring(start, end) : "";
    }

    /** Reassembles fragmented WebSocket text frames and records each complete message. */
    private static final class FrameCollector implements WebSocket.Listener {
        private final List<String> frames;
        private final CountDownLatch openLatch;
        private final CountDownLatch completeLatch;
        private final StringBuilder buffer = new StringBuilder();

        FrameCollector(List<String> frames, CountDownLatch openLatch, CountDownLatch completeLatch) {
            this.frames = frames;
            this.openLatch = openLatch;
            this.completeLatch = completeLatch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            openLatch.countDown();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                var msg = buffer.toString().strip();
                buffer.setLength(0);
                if (!msg.isEmpty()) {
                    frames.add(msg);
                    if (msg.contains("\"type\":\"complete\"")) {
                        completeLatch.countDown();
                    }
                }
            }
            webSocket.request(1);
            return null;
        }
    }
}
