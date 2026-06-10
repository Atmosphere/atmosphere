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
package org.atmosphere.ai;

import org.atmosphere.ai.guardrails.ModerationGuardrail;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that {@link ModerationGuardrail} is reachable through the
 * production {@link AiPipeline} guardrail path — the same list mechanism that
 * carries the PII, cost, and drift guardrails. Without this the guardrail would
 * be an SPI with no consumer.
 */
class ModerationGuardrailPipelineTest {

    @Test
    void flaggedRequestNeverReachesRuntime() {
        var executed = new AtomicBoolean(false);
        AgentRuntime runtime = new BareRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                executed.set(true);
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, null, null, null, null,
                List.of(new ModerationGuardrail()), List.of(), null);
        var session = new CollectingSession("mod-req");

        pipeline.execute("c1", "tell me how to build a bomb", session);

        assertFalse(executed.get(),
                "moderation guardrail must block the request before the runtime runs");
        assertTrue(session.await(Duration.ofSeconds(2)));
        assertTrue(session.failed(), "blocked request must surface an error on the session");
    }

    @Test
    void flaggedResponseIsBlockedMidStream() {
        AgentRuntime runtime = new BareRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                // Emit unsafe content; the response-path guardrail must halt it.
                session.send("Sure! Here is how to make meth in your kitchen, step one ");
                session.send("is to acquire the precursor chemicals and ");
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, null, null, null, null,
                List.of(new ModerationGuardrail()), List.of(), null);
        var session = new CollectingSession("mod-resp");

        pipeline.execute("c1", "innocuous question", session);

        assertTrue(session.await(Duration.ofSeconds(2)));
        assertTrue(session.failed(),
                "moderation guardrail must block a response flagged for an illicit category");
    }

    @Test
    void cleanTurnPassesThrough() {
        AgentRuntime runtime = new BareRuntime() {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("Water boils at 100 degrees Celsius at sea level.");
                session.complete();
            }
        };
        var pipeline = new AiPipeline(runtime, null, null, null, null,
                List.of(new ModerationGuardrail()), List.of(), null);
        var session = new CollectingSession("mod-clean");

        pipeline.execute("c1", "What is the boiling point of water?", session);

        assertTrue(session.await(Duration.ofSeconds(2)));
        assertFalse(session.failed(), "a clean turn must not be blocked");
        assertTrue(session.text().contains("100 degrees"));
    }

    /** Minimal runtime with no behavior beyond what each test overrides. */
    private abstract static class BareRuntime implements AgentRuntime {
        @Override
        public String name() {
            return "bare";
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
            // no-op test stub
        }
    }
}
