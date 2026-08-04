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
package org.atmosphere.samples.springboot.dentist;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demo-mode contract for the dentist sample: the {@code @Prompt} handler must
 * always dispatch through the framework pipeline ({@code session.stream}),
 * and {@link DemoResponseProducer} must install Dr. Molar's keyword-branched
 * persona as the {@link DemoAgentRuntime} strategy so no-key demo traffic
 * flows through the same guardrail/memory/metrics seam as a real provider.
 */
class DentistAgentTest {

    @AfterEach
    void resetStrategy() {
        // The installer mutates DemoAgentRuntime's process-wide strategy;
        // restore the built-in default so no state leaks across tests.
        DemoAgentRuntime.setResponseStrategy(null);
    }

    // ── @Prompt routes through the pipeline (no demo bypass) ──

    @Test
    void onPromptAlwaysStreamsThroughPipeline() {
        var agent = new DentistAgent();
        var session = new RecordingSession();

        agent.onPrompt("I chipped my tooth", session);

        assertEquals(List.of("I chipped my tooth"), session.streamed,
                "@Prompt must delegate to session.stream so the framework "
                        + "pipeline (guardrails, memory, metrics, tape) drives dispatch");
        assertTrue(session.sent.isEmpty(),
                "@Prompt must not hand-stream a demo response around the pipeline");
        assertFalse(session.completed,
                "@Prompt must not complete the session itself — the pipeline does");
    }

    // ── Strategy installation → DemoAgentRuntime streams Dr. Molar ──

    @Test
    void installedStrategyDrivesDemoRuntimeInPersona() {
        new DemoResponseProducer().installDentistStrategy();

        var runtime = new DemoAgentRuntime();
        var session = new RecordingSession();
        runtime.execute(contextFor("hello"), session);

        var full = String.join("", session.sent);
        assertTrue(full.contains("Hello! I'm Dr. Molar, your emergency dental assistant."),
                "expected the Dr. Molar greeting, got: " + full);
        assertTrue(session.sent.size() > 1, "response must stream word-by-word");
        assertTrue(session.completed, "runtime must complete the session");
        assertEquals(full, session.completionSummary,
                "completion summary must carry the full response");
    }

    // ── Persona branches, keyed exactly like the pre-pipeline producer ──

    @Test
    void brokenToothBranch() {
        var response = DemoResponseProducer.generateFor(contextFor("I broke a molar"));
        assertTrue(response.contains("I'm sorry to hear about your broken tooth!"));
        assertTrue(response.contains("save it in milk"));
    }

    @Test
    void crackedKeywordJoinsBrokenToothBranch() {
        var response = DemoResponseProducer.generateFor(contextFor("cracked incisor"));
        assertTrue(response.contains("I'm sorry to hear about your broken tooth!"));
    }

    @Test
    void painBranch() {
        var response = DemoResponseProducer.generateFor(contextFor("it really hurts"));
        assertTrue(response.contains("I understand you're in pain"));
        assertTrue(response.contains("On a scale of 1-10"));
    }

    @Test
    void bleedingBranch() {
        var response = DemoResponseProducer.generateFor(contextFor("there is blood"));
        assertTrue(response.contains("Bleeding from a dental injury needs attention."));
    }

    @Test
    void helloBranch() {
        var response = DemoResponseProducer.generateFor(contextFor("hello doctor"));
        assertTrue(response.contains("Hello! I'm Dr. Molar, your emergency dental assistant."));
    }

    @Test
    void defaultBranchListsCommands() {
        var response = DemoResponseProducer.generateFor(contextFor("test message"));
        assertTrue(response.contains("running in demo mode"));
        assertTrue(response.contains("/firstaid"));
        assertTrue(response.contains("/urgency"));
        assertTrue(response.contains("Set LLM_API_KEY for full AI-powered responses!"));
    }

    @Test
    void nullMessageFallsToDefaultBranch() {
        var response = DemoResponseProducer.generateFor(contextFor(null));
        assertTrue(response.contains("running in demo mode"));
    }

    private static AgentExecutionContext contextFor(String message) {
        return new AgentExecutionContext(
                message, null, null, "dentist", "sess-1", null, "conv-1",
                null, null, null, null, null, null, null, null);
    }

    /** Minimal recording stub — no framework machinery behind it. */
    private static final class RecordingSession implements StreamingSession {
        final List<String> streamed = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        boolean completed;
        String completionSummary;

        @Override
        public String sessionId() {
            return "test-session";
        }

        @Override
        public void stream(String message) {
            streamed.add(message);
        }

        @Override
        public void send(String text) {
            sent.add(text);
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
            completionSummary = summary;
        }

        @Override
        public void error(Throwable t) {
            throw new AssertionError("unexpected session error", t);
        }

        @Override
        public boolean isClosed() {
            return completed;
        }
    }
}
