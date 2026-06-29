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
package org.atmosphere.samples.springboot.passivation;

import org.atmosphere.ai.AgentSnapshot;
import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Delivery test for the PASSIVATION capability: it drives the full
 * pause → persist → resume flow over HTTP and asserts the <em>observable side
 * effects</em> that prove the conversation actually round-tripped through the
 * {@link CheckpointStore} — not merely that the beans wired up.
 *
 * <ol>
 *   <li><b>Pause persists the conversation.</b> After {@code POST /pause}, the
 *       store holds an {@link AgentSnapshot} whose history equals the original
 *       turns and whose pending message is the one that triggered the pause.
 *       This is asserted against the live store bean — structured Java
 *       objects, no JSON in the middle.</li>
 *   <li><b>Resume continues from where it left off.</b> After {@code POST
 *       /resume}, the restored history size equals the original, and the
 *       runtime's reply quotes a token that exists ONLY in the restored
 *       history (not in the resume signal or the pending message) — proving
 *       the snapshot's conversation reached the runtime rather than a cold
 *       restart.</li>
 * </ol>
 *
 * <p>Fully offline and key-free: the {@link DemoContinuationRuntime} is
 * deterministic and the store is in-memory.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "atmosphere.admin.enabled=false")
class PassivationDeliveryTest {

    // A token that appears ONLY in the earlier user turn — never in the
    // pending message or the resume signal. If it surfaces in the resumed
    // reply, the restored history must have reached the runtime.
    private static final String ORDER_TOKEN = "ORD-77781";
    private static final String EARLIER_USER_TURN = "I need a refund for order " + ORDER_TOKEN;
    private static final String EARLIER_ASSISTANT_TURN =
            "Refund started for " + ORDER_TOKEN + "; routed to a manager for approval.";
    private static final String PENDING_MESSAGE = "Please finalize the pending refund.";
    private static final String RESUME_SIGNAL = "approved: manager Alex signed off";

    private static final Pattern CHECKPOINT_ID =
            Pattern.compile("\"checkpointId\"\\s*:\\s*\"([^\"]+)\"");

    @LocalServerPort
    int port;

    @Autowired
    CheckpointStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void pausePersistsConversationAndResumeContinuesFromIt() throws Exception {
        // 1. PAUSE — snapshot a 2-turn conversation.
        var pauseJson = """
                {
                  "conversationId": "conv-refund-1",
                  "pendingMessage": "%s",
                  "reason": "awaiting manager approval",
                  "history": [
                    {"role": "user", "content": "%s"},
                    {"role": "assistant", "content": "%s"}
                  ]
                }""".formatted(PENDING_MESSAGE, EARLIER_USER_TURN, EARLIER_ASSISTANT_TURN);

        var pause = postJson("/api/agent/pause", pauseJson);
        assertEquals(200, pause.statusCode(), "pause should succeed, got: " + pause.body());
        assertTrue(pause.body().contains("\"historySize\":2"),
                "pause should report 2 turns, got: " + pause.body());
        var checkpointId = checkpointId(pause.body());
        assertNotNull(checkpointId, "pause must return a checkpoint id, got: " + pause.body());

        // 2. ASSERT PERSISTED — the store holds the snapshot, with the
        //    conversation history intact (the content actually reached the
        //    durable subsystem, not just a handle). Read straight off the
        //    store bean: structured objects, no JSON round-trip.
        var loaded = store.<AgentSnapshot>load(CheckpointId.of(checkpointId));
        assertTrue(loaded.isPresent(), "snapshot must be persisted under the returned id");
        AgentSnapshot snap = loaded.get().state();
        assertEquals(2, snap.history().size(), "persisted history must hold both turns");
        assertEquals("user", snap.history().get(0).role());
        assertEquals(EARLIER_USER_TURN, snap.history().get(0).content(),
                "persisted history must equal the original turn 1");
        assertEquals("assistant", snap.history().get(1).role());
        assertEquals(EARLIER_ASSISTANT_TURN, snap.history().get(1).content(),
                "persisted history must equal the original turn 2");
        assertEquals(PENDING_MESSAGE, snap.pendingMessage(),
                "snapshot must capture the in-flight message that triggered the pause");

        // 3. RESUME — rehydrate and continue with the approval signal.
        var resumeJson = """
                {"checkpointId": "%s", "signal": "%s"}"""
                .formatted(checkpointId, RESUME_SIGNAL);
        var resume = postJson("/api/agent/resume", resumeJson);
        assertEquals(200, resume.statusCode(), "resume should succeed, got: " + resume.body());

        // 3a. Restored history equals the original (size) and the run was a
        //     warm continuation, not a cold start.
        assertTrue(resume.body().contains("\"restoredHistorySize\":2"),
                "resume must restore the same 2 turns it paused with, got: " + resume.body());
        assertTrue(resume.body().contains("\"continued\":true"),
                "resume must be a continuation, not a fresh start, got: " + resume.body());

        // 3b. The decisive assertion: the reply quotes a token that lived ONLY
        //     in the restored history. A cold restart could not produce it.
        assertTrue(resume.body().contains(ORDER_TOKEN),
                "resumed reply must reference the restored conversation (" + ORDER_TOKEN
                        + "), proving continuation; got: " + resume.body());
        assertTrue(resume.body().contains(RESUME_SIGNAL),
                "resumed reply must thread the approval signal; got: " + resume.body());
        assertTrue(resume.body().contains("\"sessionId\":\"resume-"),
                "resume must mint a fresh session id for the run; got: " + resume.body());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void resumeOfUnknownCheckpointIsNotFound() throws Exception {
        var resume = postJson("/api/agent/resume",
                "{\"checkpointId\": \"does-not-exist\", \"signal\": \"anything\"}");
        assertEquals(404, resume.statusCode(),
                "resuming an unknown checkpoint must 404, not 500; got: " + resume.body());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void inspectReturnsTheStoredSnapshotWithoutResuming() throws Exception {
        var pauseJson = """
                {
                  "conversationId": "conv-inspect-1",
                  "pendingMessage": "%s",
                  "history": [ {"role": "user", "content": "%s"} ]
                }""".formatted(PENDING_MESSAGE, EARLIER_USER_TURN);
        var checkpointId = checkpointId(postJson("/api/agent/pause", pauseJson).body());

        var view = http.send(HttpRequest.newBuilder()
                        .uri(uri("/api/agent/checkpoints/" + checkpointId))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, view.statusCode());
        assertTrue(view.body().contains("\"runtimeName\":\"demo-continuation\""),
                "inspect must report the snapshot's runtime, got: " + view.body());
        assertTrue(view.body().contains("\"historySize\":1"),
                "inspect must report the stored history size, got: " + view.body());
        assertTrue(view.body().contains(EARLIER_USER_TURN),
                "inspect must surface the stored conversation content, got: " + view.body());
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder()
                        .uri(uri(path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String checkpointId(String body) {
        Matcher m = CHECKPOINT_ID.matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
