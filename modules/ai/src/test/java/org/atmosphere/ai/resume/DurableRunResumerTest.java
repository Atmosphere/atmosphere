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
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the resume orchestrator that both consumers (reconnect + admin) call: it
 * reconstructs the runtime dispatch context from the recorded seed, drives the
 * runtime, refuses a foreign principal, and always finalizes the run (releasing
 * the lease) on the terminal path.
 */
class DurableRunResumerTest {

    private static final String RUN_ID = "run-redrive";
    private static final Duration TTL = Duration.ofMinutes(5);

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
    }

    private static DurableRunSpine spine(EffectJournal journal) {
        return new DurableRunSpine(journal, new DurableRunConfig(true, TTL, true), "proc");
    }

    private static void recordSeed(EffectJournal journal, String userId) {
        var seed = new EffectRecord.RunSeed("gpt-x",
                List.of(ChatMessage.system("be helpful"),
                        ChatMessage.user("prior"), ChatMessage.assistant("ok"),
                        ChatMessage.user("the question")),
                "tools-d", null, "conv-1", userId, "/chat");
        var key = EffectKeys.runInput(RUN_ID);
        journal.appendPending(RUN_ID, EffectKind.RUN_INPUT, key, "d");
        journal.commit(RUN_ID, key, RunSeeds.serialize(seed));
    }

    @Test
    void resumesReconstructsContextAndFinalizes() {
        var journal = new InMemoryEffectJournal();
        recordSeed(journal, "alice");
        var runtime = new CapturingRuntime();
        var resumer = new DurableRunResumer(spine(journal));

        var status = resumer.resume(RUN_ID, "alice", List.of(), null, runtime, new RunSession());

        assertEquals(DurableRunResumer.Status.RESUMED, status);
        var ctx = runtime.captured;
        assertEquals("the question", ctx.message(), "the trailing user turn becomes the message");
        assertEquals("be helpful", ctx.systemPrompt(), "system turns become the system prompt");
        assertEquals("gpt-x", ctx.model());
        assertEquals("conv-1", ctx.conversationId());
        assertEquals("alice", ctx.userId());
        assertEquals(2, ctx.history().size(), "the non-system, non-final turns are prior history");
        assertNull(DurableRunScopeHolder.get(RUN_ID), "the run is finalized and its scope removed");
        assertTrue(journal.claimLease(RUN_ID, "other", TTL), "the lease is released after the re-drive");
    }

    @Test
    void refusesForeignPrincipalWithoutDriving() {
        var journal = new InMemoryEffectJournal();
        recordSeed(journal, "alice");
        var runtime = new CapturingRuntime();

        var status = new DurableRunResumer(spine(journal))
                .resume(RUN_ID, "bob", List.of(), null, runtime, new RunSession());

        assertEquals(DurableRunResumer.Status.REFUSED, status);
        assertNull(runtime.captured, "a refused resume never drives the runtime");
        assertTrue(journal.claimLease(RUN_ID, "other", TTL), "the lease is released on refusal");
    }

    @Test
    void notFoundWhenNoSeedRecorded() {
        var journal = new InMemoryEffectJournal();
        var runtime = new CapturingRuntime();

        var status = new DurableRunResumer(spine(journal))
                .resume(RUN_ID, "alice", List.of(), null, runtime, new RunSession());

        assertEquals(DurableRunResumer.Status.NOT_FOUND, status);
        assertNull(runtime.captured);
    }

    /** Captures the reconstructed context and completes the session. */
    private static final class CapturingRuntime implements AgentRuntime {
        private AgentExecutionContext captured;

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            this.captured = context;
            session.complete();
        }

        @Override
        public String name() {
            return "capturing";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }
    }

    private static final class RunSession implements StreamingSession {
        private boolean completed;

        @Override
        public Optional<String> runId() {
            return Optional.of(RUN_ID);
        }

        @Override
        public void emit(AiEvent event) {
        }

        @Override
        public String sessionId() {
            return "session";
        }

        @Override
        public void send(String text) {
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void complete(String summary) {
            completed = true;
        }

        @Override
        public void error(Throwable t) {
        }

        @Override
        public boolean isClosed() {
            return completed;
        }
    }
}
