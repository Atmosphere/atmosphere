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
import org.atmosphere.ai.llm.ChatMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST surface that triggers passivation. This is the documented
 * application-policy shape: the application decides when to pause and on which
 * signal to resume — {@code AiCapability.PASSIVATION} is policy, not a
 * user-facing streaming endpoint.
 *
 * <pre>
 *   POST /api/agent/pause                 snapshot a conversation, return its checkpoint id
 *   GET  /api/agent/checkpoints/{id}      inspect a snapshot without resuming
 *   POST /api/agent/resume                rehydrate a snapshot and continue the conversation
 * </pre>
 */
@RestController
@RequestMapping("/api/agent")
public class PassivationController {

    private final PassivationService service;

    public PassivationController(PassivationService service) {
        this.service = service;
    }

    @PostMapping("/pause")
    public ResponseEntity<PauseResponse> pause(@RequestBody PauseRequest request) {
        if (request == null || isBlank(request.pendingMessage())) {
            return ResponseEntity.badRequest().build();
        }
        var conversationId = isBlank(request.conversationId())
                ? "conv-" + UUID.randomUUID()
                : request.conversationId().trim();
        var reason = isBlank(request.reason()) ? "awaiting human approval" : request.reason().trim();
        var history = toHistory(request.history());

        var outcome = service.pause(conversationId, request.pendingMessage(), history, reason);
        return ResponseEntity.ok(new PauseResponse(
                outcome.checkpointId(), conversationId, outcome.historySize(), reason));
    }

    @GetMapping("/checkpoints/{id}")
    public ResponseEntity<SnapshotView> inspect(@PathVariable String id) {
        try {
            var snap = service.inspect(id);
            return ResponseEntity.ok(SnapshotView.of(snap));
        } catch (IllegalStateException notFound) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/resume")
    public ResponseEntity<ResumeResponse> resume(@RequestBody ResumeRequest request) {
        if (request == null || isBlank(request.checkpointId())) {
            return ResponseEntity.badRequest().build();
        }
        try {
            var outcome = service.resume(request.checkpointId().trim(), request.signal());
            return ResponseEntity.ok(new ResumeResponse(
                    outcome.checkpointId(),
                    outcome.response(),
                    outcome.restoredHistorySize(),
                    outcome.sessionId(),
                    outcome.continued()));
        } catch (IllegalStateException notFound) {
            return ResponseEntity.notFound().build();
        }
    }

    private static List<ChatMessage> toHistory(List<Turn> turns) {
        if (turns == null) {
            return List.of();
        }
        return turns.stream()
                .filter(t -> t != null && t.role() != null && t.content() != null)
                .map(t -> new ChatMessage(t.role(), t.content()))
                .toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** One conversation turn on the wire. */
    public record Turn(String role, String content) {
    }

    /** Body of {@code POST /api/agent/pause}. */
    public record PauseRequest(String conversationId,
                               String pendingMessage,
                               String reason,
                               List<Turn> history) {
    }

    /** Body of {@code POST /api/agent/resume}. */
    public record ResumeRequest(String checkpointId, String signal) {
    }

    /** Response of {@code POST /api/agent/pause}. */
    public record PauseResponse(String checkpointId,
                                String conversationId,
                                int historySize,
                                String reason) {
    }

    /** Response of {@code POST /api/agent/resume}. */
    public record ResumeResponse(String checkpointId,
                                 String response,
                                 int restoredHistorySize,
                                 String sessionId,
                                 boolean continued) {
    }

    /** Read-only view of a persisted snapshot for {@code GET /checkpoints/{id}}. */
    public record SnapshotView(String runtimeName,
                               String pendingMessage,
                               String reason,
                               int historySize,
                               List<Turn> history) {
        static SnapshotView of(AgentSnapshot snap) {
            var turns = snap.history().stream()
                    .map(m -> new Turn(m.role(), m.content()))
                    .toList();
            return new SnapshotView(
                    snap.runtimeName(), snap.pendingMessage(), snap.reason(),
                    snap.history().size(), turns);
        }
    }
}
