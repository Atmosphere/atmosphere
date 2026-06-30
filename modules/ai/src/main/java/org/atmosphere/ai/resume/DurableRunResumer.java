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
package org.atmosphere.ai.resume;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The production consumer that turns {@link DurableRunSpine#beginResume} into a
 * real re-drive: it reconstructs an {@link AgentExecutionContext} from the
 * recorded run seed plus the endpoint's <em>live</em> tool set and dispatches it
 * through the same {@link AgentRuntime} a fresh run uses, with a replay-mode
 * scope installed. Committed rounds and tool calls replay from the journal (zero
 * provider HTTP, side effects run at most once); only the uncommitted tail
 * executes live. The terminal path always {@linkplain DurableRunSpine#completeDrive
 * finalizes} the run so the lease is released.
 *
 * <p>Both the reconnect-triggered path (a client returning with an
 * {@code X-Atmosphere-Run-Id} for a run no longer live) and the admin re-drive
 * endpoint call this one orchestrator, so a crashed run resumes identically
 * however it is triggered (Correctness Invariant #7, Mode Parity).</p>
 *
 * @since 4.0
 */
public final class DurableRunResumer {

    private static final Logger logger = LoggerFactory.getLogger(DurableRunResumer.class);

    /** The outcome of a resume attempt, mirroring {@link DurableRunSpine.ResumeOutcome}. */
    public enum Status {
        /** The run was re-driven to completion (or its tail failed but was finalized). */
        RESUMED,
        /** The requester is not the run's principal — refused (Inv #6). */
        REFUSED,
        /** No resumable run for that id (disabled, no seed, or still leased). */
        NOT_FOUND
    }

    private final DurableRunSpine spine;

    public DurableRunResumer(DurableRunSpine spine) {
        this.spine = Objects.requireNonNull(spine, "spine");
    }

    /**
     * Re-drive a crashed run. The {@code freshSession} MUST report
     * {@code runId() == runId} so the journaled seams resolve the replay scope;
     * the caller streams the re-driven output to wherever the session points (a
     * reconnected client, an admin sink). This call blocks until the run reaches
     * its terminal state.
     *
     * @param runId              the run to resume
     * @param requesterPrincipal the principal requesting the resume (Inv #6)
     * @param liveTools          the endpoint's live tool set, re-attached so the
     *                           uncommitted tail can execute and committed tools
     *                           can be resolved by name on replay
     * @param approvalStrategy   the strategy for any approval gate the tail hits
     *                           (committed approvals replay without it); may be null
     * @param runtime            the runtime to dispatch through
     * @param freshSession       the output sink, carrying {@code runId}
     */
    public Status resume(String runId, String requesterPrincipal,
                         List<ToolDefinition> liveTools, ApprovalStrategy approvalStrategy,
                         AgentRuntime runtime, StreamingSession freshSession) {
        Objects.requireNonNull(freshSession, "freshSession");
        return drive(spine.beginResume(runId, requesterPrincipal),
                liveTools, approvalStrategy, runtime, freshSession);
    }

    /**
     * Admin re-drive: take over the run regardless of its owner (the caller is
     * authorized by an admin role at the endpoint's authz gate, not run
     * ownership). The endpoint's live runtime and tool set are resolved from the
     * recorded {@link EffectRecord.RunSeed#endpointPath() endpointPath} via
     * {@link ResumableEndpointRegistry}; a run whose endpoint is no longer
     * registered cannot be re-driven and is finalized as failed.
     *
     * @param runId        the run to resume
     * @param freshSession the output sink, carrying {@code runId}
     */
    public Status resumeAsAdmin(String runId, StreamingSession freshSession) {
        Objects.requireNonNull(freshSession, "freshSession");
        var outcome = spine.beginResumeAsAdmin(runId);
        if (outcome instanceof DurableRunSpine.ResumeOutcome.Resume resume) {
            var endpoint = ResumableEndpointRegistry.lookup(resume.seed().endpointPath());
            if (endpoint.isEmpty()) {
                logger.warn("Admin resume of run {}: endpoint '{}' is not registered; cannot re-drive",
                        resume.context().runId(), resume.seed().endpointPath());
                spine.completeDrive(resume.context(), false);
                return Status.NOT_FOUND;
            }
            return drive(outcome, endpoint.get().tools().get(), null,
                    endpoint.get().runtime(), freshSession);
        }
        return drive(outcome, List.of(), null, null, freshSession);
    }

    private Status drive(DurableRunSpine.ResumeOutcome outcome, List<ToolDefinition> liveTools,
                         ApprovalStrategy approvalStrategy, AgentRuntime runtime,
                         StreamingSession freshSession) {
        if (outcome instanceof DurableRunSpine.ResumeOutcome.None none) {
            logger.debug("Resume not possible: {}", none.reason());
            return Status.NOT_FOUND;
        }
        if (outcome instanceof DurableRunSpine.ResumeOutcome.Refused) {
            return Status.REFUSED;
        }
        var resume = (DurableRunSpine.ResumeOutcome.Resume) outcome;
        var context = resume.context();
        var ok = false;
        try {
            var execContext = reconstruct(resume.seed(), liveTools, approvalStrategy,
                    freshSession.sessionId());
            Objects.requireNonNull(runtime, "runtime").execute(execContext, freshSession);
            ok = true;
        } catch (RuntimeException e) {
            logger.warn("Resume of run {} failed during re-drive", context.runId(), e);
            if (!freshSession.isClosed()) {
                freshSession.error(e);
            }
        } finally {
            spine.completeDrive(context, ok);
        }
        return Status.RESUMED;
    }

    /**
     * Rebuild the runtime dispatch context from a recorded seed: the trailing
     * user turn becomes the message, system turns the system prompt, and the rest
     * the prior history — the same shape the original run assembled.
     */
    private AgentExecutionContext reconstruct(EffectRecord.RunSeed seed,
                                              List<ToolDefinition> liveTools,
                                              ApprovalStrategy approvalStrategy,
                                              String sessionId) {
        var messages = seed.messages();
        var systemPrompt = new StringBuilder();
        String message = "";
        var history = new ArrayList<ChatMessage>();
        // The last user-role message is the turn that drove the run; everything
        // before it (minus system turns) is prior history.
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                lastUser = i;
                break;
            }
        }
        for (int i = 0; i < messages.size(); i++) {
            var m = messages.get(i);
            if ("system".equals(m.role())) {
                if (systemPrompt.length() > 0) {
                    systemPrompt.append('\n');
                }
                systemPrompt.append(m.content() != null ? m.content() : "");
            } else if (i == lastUser) {
                message = m.content() != null ? m.content() : "";
            } else {
                history.add(m);
            }
        }
        return new AgentExecutionContext(
                message, systemPrompt.toString(), seed.model(),
                null, sessionId, seed.userId(), seed.conversationId(),
                liveTools != null ? liveTools : List.of(), null, null,
                List.of(), java.util.Map.of(), history, null, approvalStrategy);
    }
}
