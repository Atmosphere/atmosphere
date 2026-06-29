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

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentSnapshot;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.checkpoint.AgentPassivation;
import org.atmosphere.checkpoint.CheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The application-policy driver behind {@code AiCapability.PASSIVATION}: it
 * decides <em>when</em> to pause an agent and on which signal to resume it,
 * and delegates the durable mechanics to
 * {@link AgentPassivation#passivate} / {@link AgentPassivation#resume}.
 *
 * <p>{@link #pause} captures the live conversation into an
 * {@link AgentSnapshot} persisted in the {@link CheckpointStore} and returns
 * the checkpoint id (the handle a human-approval workflow would hold for
 * minutes, hours, or days). {@link #resume} rehydrates that snapshot, threads
 * the restored history back into a fresh {@link AgentExecutionContext}, and
 * re-runs the {@link DemoContinuationRuntime} so it continues from where it
 * paused.</p>
 */
@Service
public class PassivationService {

    private static final Logger logger = LoggerFactory.getLogger(PassivationService.class);

    private static final String SYSTEM_PROMPT =
            "You are a refunds assistant. Resolve the customer's request once approval is granted.";
    private static final String MODEL = "demo-continuation";
    private static final String AGENT_ID = "refund-agent";
    private static final String USER_ID = "customer";
    private static final Duration RESUME_TIMEOUT = Duration.ofSeconds(5);

    private final CheckpointStore store;
    private final DemoContinuationRuntime runtime;

    public PassivationService(CheckpointStore store, DemoContinuationRuntime runtime) {
        this.store = store;
        this.runtime = runtime;
    }

    /**
     * Snapshot a paused conversation and return its durable handle.
     *
     * @param conversationId stable id correlating the paused turns
     * @param pendingMessage the in-flight message that triggered the pause
     * @param history        the conversation so far
     * @param reason         human-readable pause reason (audit / observability)
     */
    public PauseOutcome pause(String conversationId,
                              String pendingMessage,
                              List<ChatMessage> history,
                              String reason) {
        var context = new AgentExecutionContext(
                pendingMessage,
                SYSTEM_PROMPT,
                MODEL,
                AGENT_ID,
                conversationId,
                USER_ID,
                conversationId,
                List.of(),
                null,
                null,
                List.of(),
                Map.of("channel", "web"),
                history,
                null,
                null);
        var checkpointId = AgentPassivation.passivate(runtime, context, store, reason);
        logger.info("Passivated conversation {} -> checkpoint {} ({} turns, reason: {})",
                conversationId, checkpointId, history.size(), reason);
        return new PauseOutcome(checkpointId, history.size());
    }

    /**
     * Rehydrate a passivated conversation and continue it with {@code signal}
     * as the in-flight message.
     *
     * @param checkpointId the handle returned by {@link #pause}
     * @param signal       the external signal that triggered resume (e.g. the
     *                     approver's note); when blank, the snapshot's pending
     *                     message is replayed
     * @throws IllegalStateException if the checkpoint id is unknown
     */
    public ResumeOutcome resume(String checkpointId, String signal) {
        var session = new CapturingSession("resume-" + checkpointId + "-" + System.nanoTime());
        // Fresh base context — only its runtime references survive; the
        // snapshot overrides the persistent columns (system prompt, model,
        // identity, history). Resume drives the run into our capturing sink.
        var base = new AgentExecutionContext(
                "", SYSTEM_PROMPT, MODEL, AGENT_ID, null, USER_ID, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null, null);

        AgentPassivation.resume(runtime, store, checkpointId, signal, base, session);

        if (!session.await(RESUME_TIMEOUT)) {
            throw new IllegalStateException(
                    "Resumed run did not complete within " + RESUME_TIMEOUT);
        }
        if (session.failure() != null) {
            throw new IllegalStateException("Resumed run failed", session.failure());
        }

        var restoredHistorySize = intMetadata(session.metadata(), "resumed.history.size");
        var continued = Boolean.TRUE.equals(session.metadata().get("resumed.continued"));
        logger.info("Resumed checkpoint {} ({} turns restored, continued={})",
                checkpointId, restoredHistorySize, continued);
        return new ResumeOutcome(
                checkpointId, session.text(), restoredHistorySize, session.sessionId(), continued);
    }

    /**
     * Read back a snapshot without resuming — the inspection path a HITL
     * review console would render before approving.
     *
     * @throws IllegalStateException if the checkpoint id is unknown
     */
    public AgentSnapshot inspect(String checkpointId) {
        return AgentPassivation.loadSnapshot(store, checkpointId);
    }

    private static int intMetadata(Map<String, Object> metadata, String key) {
        return metadata.get(key) instanceof Number n ? n.intValue() : 0;
    }

    /** Outcome of a {@link #pause} call. */
    public record PauseOutcome(String checkpointId, int historySize) {
    }

    /** Outcome of a {@link #resume} call. */
    public record ResumeOutcome(String checkpointId,
                                String response,
                                int restoredHistorySize,
                                String sessionId,
                                boolean continued) {
    }
}
