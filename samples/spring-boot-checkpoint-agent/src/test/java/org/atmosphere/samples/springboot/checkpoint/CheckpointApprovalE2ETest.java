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
package org.atmosphere.samples.springboot.checkpoint;

import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the documented HITL happy path
 * ({@code dispatch → snapshot → approve?by=alice} with NO {@code ?request=}).
 *
 * <p>Root cause: {@code AnalyzerAgent.analyze} was a plain {@code @AiTool},
 * which is never registered as a dispatchable A2A skill. The coordinator's
 * {@code fleet.agent("analyzer").call("analyze", ...)} therefore failed with
 * "Unknown skill: analyze"; the journal captured an {@code AgentFailed}
 * snapshot (no recoverable request), and the documented approve returned 400.
 * The fix exposes {@code analyze} as an {@code @AgentSkill}.</p>
 *
 * <p>Both tests are deterministic and key-free. The coordinator's own
 * {@code @Prompt} session is pipeline-backed and connects to an LLM runtime,
 * so a full WebSocket-driven run is gated on LLM quota and would be flaky in
 * CI. Instead this splits the chain into its two deterministic halves:</p>
 * <ol>
 *   <li>the analyzer skill dispatches over A2A (proving "Unknown skill" is
 *       gone and the analysis JSON — carrying the original request — is
 *       produced); and</li>
 *   <li>given that {@code AgentCompleted} snapshot, the default approve
 *       recovers the request and returns 200 (it returned 400 before the fix
 *       because the snapshot was an {@code AgentFailed}).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "atmosphere.admin.enabled=false",
                // Clean slate per run; no SQLite file carried across builds.
                "atmosphere.checkpoint.store=in-memory"
        })
class CheckpointApprovalE2ETest {

    private static final String REQUEST = "please refund order 1234";

    @LocalServerPort
    int port;

    @Autowired
    CheckpointStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void analyzerSkillDispatchesViaA2a() throws Exception {
        // The exact regression: the coordinator dispatches the "analyze" skill
        // through the analyzer's A2A endpoint. Before the fix this returned a
        // JSON-RPC error "Unknown skill: analyze"; now it runs the skill and
        // returns its analysis artifact (deterministic, no LLM).
        var rpc = """
                {"jsonrpc":"2.0","id":1,"method":"SendMessage",
                 "params":{"message":{"messageId":"m1","role":"ROLE_USER",
                   "parts":[{"text":"%s"}],
                   "metadata":{"skillId":"analyze"}},
                  "arguments":{"request":"%s"}}}""".formatted(REQUEST, REQUEST);

        var resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/atmosphere/agent/analyzer/a2a"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(rpc))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(),
                "analyzer A2A endpoint should accept the skill call, got: " + resp.body());
        assertFalse(resp.body().contains("Unknown skill"),
                "analyze must be a dispatchable skill, got: " + resp.body());
        assertTrue(resp.body().contains("risk"),
                "response should carry the analyzer's JSON, got: " + resp.body());
        assertTrue(resp.body().contains(REQUEST),
                "analysis must echo the original request, got: " + resp.body());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void defaultApproveRecoversRequestFromCompletedSnapshot() throws Exception {
        // Seed exactly the snapshot a successful dispatch now produces: an
        // AgentCompleted whose resultText is the analyzer's JSON (with the
        // original request). Pre-fix the snapshot was an AgentFailed with no
        // "request", so recovery returned null and approve was 400.
        var analyzerJson = "{\"request\":\"" + REQUEST + "\",\"risk\":\"HIGH\","
                + "\"recommendation\":\"requires manual approval\"}";
        var completed = new CoordinationEvent.AgentCompleted(
                "dispatch", "analyzer", "analyze", analyzerJson,
                Duration.ofMillis(2), Instant.now());
        var saved = store.save(WorkflowSnapshot.root("dispatch", completed));
        var id = saved.id().value();

        // The documented happy path — no ?request= override.
        var approve = http.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/api/checkpoints/" + id + "/approve?by=alice"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, approve.statusCode(),
                "default approve (no ?request) must succeed, got "
                        + approve.statusCode() + ": " + approve.body());
        assertTrue(approve.body().contains("approved by alice"),
                "approve result should record the approver, got: " + approve.body());
        assertTrue(approve.body().contains(REQUEST),
                "approve result should chain the recovered request, got: " + approve.body());
    }
}
