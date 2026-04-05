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

import org.atmosphere.checkpoint.CheckpointId;
import org.atmosphere.checkpoint.CheckpointQuery;
import org.atmosphere.checkpoint.CheckpointStore;
import org.atmosphere.checkpoint.WorkflowSnapshot;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST API exposing the {@link CheckpointStore} for inspection + approval.
 * This is what the {@code atmosphere checkpoint} CLI subcommand talks to.
 *
 * <pre>
 *   GET    /api/checkpoints                     list all snapshots
 *   GET    /api/checkpoints?coordination=dispatch  filter by coordination
 *   GET    /api/checkpoints/{id}                show a single snapshot
 *   POST   /api/checkpoints/{id}/fork?state=... fork from a snapshot
 *   POST   /api/checkpoints/{id}/approve        resume the workflow: invoke approver + chain result
 *   DELETE /api/checkpoints/{id}                delete a snapshot
 * </pre>
 *
 * <p>The {@code /approve} endpoint is the HITL resumption point: it loads
 * the analyzer's checkpoint, invokes the {@link ApproverAgent} with the
 * original request recovered from the analyzer's result, and appends the
 * approver's completion as a child snapshot. That child is the continuation
 * of the durable workflow.</p>
 */
@RestController
@RequestMapping("/api/checkpoints")
public class CheckpointController {

    // Extracts the "request" field from the analyzer's JSON result, which
    // is embedded in the parent snapshot's state. Kept permissive so the
    // sample tolerates minor format changes.
    private static final Pattern REQUEST_JSON_FIELD =
            Pattern.compile("\"request\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final CheckpointStore store;
    private final ApproverAgent approverAgent;

    public CheckpointController(CheckpointStore store, ApproverAgent approverAgent) {
        this.store = store;
        this.approverAgent = approverAgent;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String coordination,
            @RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "100") int limit) {
        var query = CheckpointQuery.builder()
                .coordinationId(coordination)
                .agentName(agent)
                .limit(limit)
                .build();
        return store.list(query).stream().map(CheckpointController::toJson).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable String id) {
        return store.load(CheckpointId.of(id))
                .map(snap -> ResponseEntity.ok(toJson(snap)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/fork")
    public ResponseEntity<Map<String, Object>> fork(
            @PathVariable String id,
            @RequestParam(required = false) String state) {
        try {
            var forked = store.fork(CheckpointId.of(id), state);
            return ResponseEntity.ok(toJson(forked));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Resume the workflow from the analyzer's checkpoint: extract the
     * original request from the stored state, invoke the approver, and
     * chain the approver's completion as a child snapshot. The returned
     * snapshot is the continuation of the durable workflow.
     *
     * <p>If the caller wants to override the request recovered from the
     * snapshot (e.g. the human amended it during review), they can pass
     * {@code ?request=...}.</p>
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String id,
            @RequestParam(defaultValue = "operator") String by,
            @RequestParam(required = false) String request) {
        var sourceId = CheckpointId.of(id);
        var source = store.<Object>load(sourceId);
        if (source.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var originalRequest = request != null
                ? request
                : recoverRequestFromSnapshot(source.get());
        if (originalRequest == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Could not recover original request from snapshot state; "
                            + "pass ?request=... to override."));
        }

        // Real work: invoke the approver specialist. This is the workflow
        // resumption the README and the DispatchCoordinator advertise.
        var approverResult = approverAgent.execute(originalRequest, by);

        // Chain the approver's completion as a child snapshot. Its state is
        // the approver's result string; its parent is the analyzer's
        // snapshot, so the full workflow history is preserved.
        var resumed = store.fork(sourceId, approverResult);
        return ResponseEntity.ok(toJson(resumed));
    }

    private static String recoverRequestFromSnapshot(WorkflowSnapshot<?> snap) {
        // The CoordinationStateExtractor.event() extractor stores the
        // triggering CoordinationEvent; the AgentCompleted's resultText
        // contains the analyzer's JSON output with a "request" field.
        var state = snap.state();
        String haystack = state instanceof CoordinationEvent.AgentCompleted completed
                ? completed.resultText()
                : String.valueOf(state);
        if (haystack == null) {
            return null;
        }
        Matcher m = REQUEST_JSON_FIELD.matcher(haystack);
        if (!m.find()) {
            return null;
        }
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return store.delete(CheckpointId.of(id))
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private static Map<String, Object> toJson(WorkflowSnapshot<?> snap) {
        // LinkedHashMap preserves field order for readable responses.
        var map = new LinkedHashMap<String, Object>();
        map.put("id", snap.id().value());
        map.put("parentId", snap.parent().map(CheckpointId::value).orElse(null));
        map.put("coordinationId", snap.coordinationId());
        map.put("agentName", snap.agentName());
        map.put("createdAt", snap.createdAt().toString());
        map.put("metadata", snap.metadata());
        map.put("state", String.valueOf(snap.state()));
        return map;
    }
}
