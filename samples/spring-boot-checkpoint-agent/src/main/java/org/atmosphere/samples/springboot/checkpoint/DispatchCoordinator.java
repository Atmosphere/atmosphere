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

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.coordinator.annotation.AgentRef;
import org.atmosphere.coordinator.annotation.Coordinator;
import org.atmosphere.coordinator.annotation.Fleet;
import org.atmosphere.coordinator.fleet.AgentFleet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Demonstrates a durable HITL (human-in-the-loop) workflow split across
 * two entry points — a streaming coordinator prompt and a REST approve
 * endpoint — linked by a {@code CheckpointStore}:
 *
 * <ol>
 *   <li>User sends a request to this coordinator (WebSocket/SSE stream)</li>
 *   <li>The coordinator dispatches to {@code analyzer}, which emits an
 *       {@code AgentCompleted} event on the journal</li>
 *   <li>{@link CheckpointConfig}'s {@code CheckpointingCoordinationJournal}
 *       bridge captures that event as a {@code WorkflowSnapshot}</li>
 *   <li>The coordinator streams the analyzer's recommendation + a pointer
 *       to the freshly-written checkpoint id so the caller (or a reviewer)
 *       can pause here, inspect the snapshot, and resume later</li>
 *   <li>A reviewer hits {@code GET /api/checkpoints?coordination=dispatch}
 *       (see {@link CheckpointController}) to find the pending checkpoint</li>
 *   <li>Resumption: {@code POST /api/checkpoints/{id}/approve} invokes
 *       {@link ApproverAgent#execute} with the original request recovered
 *       from the snapshot's state, then {@code store.fork()}s the result as
 *       a child snapshot. The child IS the workflow continuation — the
 *       history chain now runs analyzer → approver across what may have
 *       been a gap of days between HTTP calls.</li>
 * </ol>
 *
 * <p>Only step 2 runs inside this {@code @Prompt} method; step 6 runs in
 * {@link CheckpointController#approve}. The two halves are bound solely by
 * the checkpoint id — no live coordinator thread is kept alive during the
 * HITL pause.</p>
 */
@Coordinator(name = "dispatch",
        description = "Analyzer/approver dispatch with checkpointed HITL pauses")
@Fleet({
        @AgentRef(type = AnalyzerAgent.class),
        @AgentRef(type = ApproverAgent.class)
})
@Component
public class DispatchCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(DispatchCoordinator.class);

    @Prompt
    public void onPrompt(String message, AgentFleet fleet, StreamingSession session) {
        logger.info("Dispatch received: {}", message);

        // Step 1 — analyze. The CheckpointingCoordinationJournal bridge
        // persists an AgentCompleted snapshot automatically after this call.
        var analysis = fleet.agent("analyzer").call("analyze", Map.of("request", message));
        if (!analysis.success()) {
            session.stream("Analyzer failed: " + analysis.text());
            return;
        }

        // Step 2 — surface the snapshot id so the human reviewer (or a
        // follow-up automated gate) can inspect and approve via HTTP.
        session.stream("Analysis complete.\n\n"
                + "Result: " + analysis.text() + "\n\n"
                + "This result has been checkpointed. Review at "
                + "GET /api/checkpoints (filter by coordination=dispatch) and "
                + "POST /api/checkpoints/{id}/approve to resume.");
    }
}
