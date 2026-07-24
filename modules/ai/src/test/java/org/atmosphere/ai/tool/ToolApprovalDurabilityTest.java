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
package org.atmosphere.ai.tool;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.approval.ApprovalResolution;
import org.atmosphere.ai.approval.ApprovalStrategy;
import org.atmosphere.ai.approval.PendingApproval;
import org.atmosphere.ai.resume.DurableRunContext;
import org.atmosphere.ai.resume.DurableRunScopeHolder;
import org.atmosphere.ai.resume.EffectKeys;
import org.atmosphere.ai.resume.InMemoryEffectJournal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the Tier-1 HITL durability P1: a pending approval used
 * to live only in a per-instance in-memory registry, so a node crash after a
 * human approved — but before the tool result committed — forced the resumed
 * run to re-prompt and lost the original decision. With durable runs on, the
 * decision is recorded as an {@code APPROVAL} effect committed <em>before</em>
 * the tool executes; a crash-resume re-drive replays it instead of
 * re-prompting the reviewer.
 *
 * <p>The crash window is simulated faithfully against a shared
 * {@link InMemoryEffectJournal}: drive once to a committed decision, mark the
 * enclosing TOOL_CALL effect {@code FAILED} (the state a crash between the
 * approval commit and the tool commit leaves behind), then re-drive with a
 * fresh {@link DurableRunContext} — fresh occurrence cursors, exactly like a
 * resumed process.</p>
 */
class ToolApprovalDurabilityTest {

    private static final String RUN_ID = "run-hitl-durability";
    private static final Map<String, Object> ARGS = Map.of("target", "row-7");

    @AfterEach
    void clearScopes() {
        DurableRunScopeHolder.clear();
    }

    /** Session carrying a run id so the durable scope resolves. */
    private static final class RunSession implements StreamingSession {
        @Override public String sessionId() { return "sess-hitl"; }
        @Override public Optional<String> runId() { return Optional.of(RUN_ID); }
        @Override public void send(String text) { }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public void emit(AiEvent event) { }
        @Override public boolean isClosed() { return false; }
        @Override public boolean hasErrored() { return false; }
    }

    /** Counts reviewer prompts; answers with the configured resolution. */
    private static final class CountingStrategy implements ApprovalStrategy {
        final AtomicInteger prompts = new AtomicInteger();
        private final ApprovalResolution answer;
        CountingStrategy(ApprovalResolution answer) { this.answer = answer; }
        @Override
        public ApprovalOutcome awaitApproval(PendingApproval approval, StreamingSession session) {
            return awaitApprovalDetailed(approval, session).outcome();
        }
        @Override
        public ApprovalResolution awaitApprovalDetailed(PendingApproval approval,
                                                        StreamingSession session) {
            prompts.incrementAndGet();
            return answer;
        }
    }

    private static ToolDefinition gatedTool(AtomicInteger executions) {
        return ToolDefinition.builder("delete_row", "Deletes a row")
                .parameter("target", "The row to delete", "string")
                .executor(args -> {
                    executions.incrementAndGet();
                    return "deleted " + args.get("target");
                })
                .requiresApproval("Really delete?")
                .build();
    }

    private static String drive(ToolDefinition tool, CountingStrategy strategy) {
        return ToolExecutionHelper.executeWithApproval(
                "delete_row", tool, ARGS, new RunSession(), strategy, null, Map.of());
    }

    @Test
    void approvedDecisionSurvivesCrashResumeWithoutRePrompting() {
        var journal = new InMemoryEffectJournal();
        var executions = new AtomicInteger();
        var strategy = new CountingStrategy(ApprovalResolution.approve());
        var tool = gatedTool(executions);

        // Drive 1: reviewer approves, tool runs, both effects commit.
        DurableRunScopeHolder.install(RUN_ID,
                new DurableRunContext(RUN_ID, journal, false, "owner", "alice"));
        assertEquals("deleted row-7", drive(tool, strategy));
        assertEquals(1, strategy.prompts.get());
        assertEquals(1, executions.get());

        // Crash window: the tool result never committed — flip the TOOL_CALL
        // effect to FAILED so the resume re-drives it live.
        var toolCallKey = EffectKeys.toolCall(RUN_ID, "delete_row", ARGS, 0);
        journal.markFailed(RUN_ID, toolCallKey, "simulated crash before tool commit");

        // Drive 2: a resumed process — fresh context, fresh cursors.
        DurableRunScopeHolder.install(RUN_ID,
                new DurableRunContext(RUN_ID, journal, true, "owner", "alice"));
        assertEquals("deleted row-7", drive(tool, strategy));

        assertEquals(1, strategy.prompts.get(),
                "the recorded approval must replay — the reviewer is NOT re-prompted");
        assertEquals(2, executions.get(),
                "the re-driven tool executes live under the replayed approval");
    }

    @Test
    void timeoutIsNotReplayedAsADecision() {
        var journal = new InMemoryEffectJournal();
        var executions = new AtomicInteger();
        var strategy = new CountingStrategy(ApprovalResolution.timedOut());
        var tool = gatedTool(executions);

        DurableRunScopeHolder.install(RUN_ID,
                new DurableRunContext(RUN_ID, journal, false, "owner", "alice"));
        var first = drive(tool, strategy);
        assertTrue(first.contains("timeout"), first);
        assertEquals(0, executions.get(), "a timed-out gate never runs the tool");

        // Re-drive after the crash: an expiry is the ABSENCE of a decision —
        // the resumed run must re-prompt, never replay a timeout.
        journal.markFailed(RUN_ID, EffectKeys.toolCall(RUN_ID, "delete_row", ARGS, 0),
                "simulated crash");
        DurableRunScopeHolder.install(RUN_ID,
                new DurableRunContext(RUN_ID, journal, true, "owner", "alice"));
        drive(tool, strategy);
        assertEquals(2, strategy.prompts.get(),
                "a timeout must not be replayed as a durable decision");
    }

    @Test
    void differentPrincipalCannotInheritARecordedApproval() {
        var journal = new InMemoryEffectJournal();
        var executions = new AtomicInteger();
        var strategy = new CountingStrategy(ApprovalResolution.approve());
        var tool = gatedTool(executions);

        DurableRunScopeHolder.install(RUN_ID,
                new DurableRunContext(RUN_ID, journal, false, "owner", "alice"));
        drive(tool, strategy);
        assertEquals(1, strategy.prompts.get());

        journal.markFailed(RUN_ID, EffectKeys.toolCall(RUN_ID, "delete_row", ARGS, 0),
                "simulated crash");
        // Re-drive under a DIFFERENT principal: the digest folds the userId, so
        // the recorded decision must not be inherited (Invariant #6).
        DurableRunScopeHolder.install(RUN_ID,
                new DurableRunContext(RUN_ID, journal, true, "owner", "mallory"));
        drive(tool, strategy);
        assertEquals(2, strategy.prompts.get(),
                "another principal's re-drive must re-prompt, never inherit the approval");
    }

    @Test
    void nonDurableGatePromptsEveryDrive() {
        // No durable scope installed: the gate is the byte-identical live path
        // — every drive prompts (pins that the memo is scope-gated).
        var executions = new AtomicInteger();
        var strategy = new CountingStrategy(ApprovalResolution.approve());
        var tool = gatedTool(executions);

        drive(tool, strategy);
        drive(tool, strategy);
        assertEquals(2, strategy.prompts.get());
        assertEquals(2, executions.get());
    }
}
