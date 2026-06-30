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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the admin-triggered re-drive: an authenticated admin re-drives a run it
 * does not own (the owner check is bypassed at the spine; authz is the admin
 * endpoint's job), with the runtime and tools resolved from the endpoint
 * registry by the seed's path. A run whose endpoint is no longer registered
 * cannot be re-driven and is finalized rather than left leased.
 */
class DurableRunAdminResumeTest {

    private static final String RUN_ID = "run-admin";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String PATH = "/chat";

    @AfterEach
    void cleanup() {
        DurableRunScopeHolder.clear();
        ResumableEndpointRegistry.clear();
    }

    private static DurableRunSpine spine(EffectJournal journal) {
        return new DurableRunSpine(journal, new DurableRunConfig(true, TTL, true), "proc");
    }

    private static void recordSeed(EffectJournal journal, String userId, String endpointPath) {
        var seed = new EffectRecord.RunSeed("m",
                List.of(ChatMessage.system("sys"), ChatMessage.user("hi")),
                "td", null, null, userId, endpointPath);
        var key = EffectKeys.runInput(RUN_ID);
        journal.appendPending(RUN_ID, EffectKind.RUN_INPUT, key, "d");
        journal.commit(RUN_ID, key, RunSeeds.serialize(seed));
    }

    @Test
    void adminReDrivesARunItDoesNotOwn() {
        var journal = new InMemoryEffectJournal();
        recordSeed(journal, "alice", PATH);
        var runtime = new CapturingRuntime();
        ResumableEndpointRegistry.register(PATH, runtime, List::of);

        // The admin principal is NOT "alice", yet the re-drive proceeds.
        var status = new DurableRunResumer(spine(journal)).resumeAsAdmin(RUN_ID, new RunSession());

        assertEquals(DurableRunResumer.Status.RESUMED, status);
        assertNotNull(runtime.captured, "the run is re-driven through the registered endpoint runtime");
        assertEquals("hi", runtime.captured.message());
        assertNull(DurableRunScopeHolder.get(RUN_ID), "the run is finalized and its scope removed");
        assertTrue(journal.claimLease(RUN_ID, "other", TTL), "the lease is released after the admin re-drive");
    }

    @Test
    void adminResumeFinalizesWhenEndpointNotRegistered() {
        var journal = new InMemoryEffectJournal();
        recordSeed(journal, "alice", "/gone");
        // No endpoint registered for "/gone".

        var status = new DurableRunResumer(spine(journal)).resumeAsAdmin(RUN_ID, new RunSession());

        assertEquals(DurableRunResumer.Status.NOT_FOUND, status);
        assertTrue(journal.claimLease(RUN_ID, "other", TTL),
                "an un-redrivable run is finalized, never left leased");
    }

    @Test
    void adminResumeNotFoundWhenNoSeed() {
        var journal = new InMemoryEffectJournal();
        var status = new DurableRunResumer(spine(journal)).resumeAsAdmin(RUN_ID, new RunSession());
        assertEquals(DurableRunResumer.Status.NOT_FOUND, status);
    }

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
