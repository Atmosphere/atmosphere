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

import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol bridges (A2A, AG-UI) ran {@code @Prompt} before the endpoint
 * scope saw the user's message, then scoped the prompts the method composed:
 * an off-topic message drove a coordinator's whole fleet, and an on-topic one
 * was redirected because its synthesis prompt quoted a search snippet naming
 * Java. {@link AiPipeline#admitScope} admits the inbound message instead, and
 * while its admission is open {@link AiPipeline#execute} does not re-scope the
 * prompts composed on that thread.
 */
class AiPipelineScopeAdmissionTest {

    private static final String REDIRECT = "I only discuss startup markets.";
    private static final String COMPOSED = "Write an executive briefing. Research: "
            + "[1] 8 Best AI Agent Frameworks for Java Developers (2026)";

    private final RecordingRuntime runtime = new RecordingRuntime();
    private final AiPipeline pipeline = new AiPipeline(runtime, "You advise startups.", null,
            null, null, List.of(), List.of(scope()), List.of(), null, null);

    @Test
    void anOffScopeMessageIsNotAdmitted() {
        var admission = pipeline.admitScope("task-1",
                "Write me a python function that sorts a linked list");

        assertTrue(admission.isEmpty(), "an off-scope message must not open an admitted turn");
        assertEquals(0, runtime.messages.size(), "admission alone never reaches the model");
    }

    @Test
    void anAdmittedTurnDoesNotRescopeItsComposedPrompt() {
        var admission = pipeline.admitScope("task-1",
                "Analyze the market for AI agent frameworks on the JVM");
        assertTrue(admission.isPresent(), "an in-scope message opens an admitted turn");
        try {
            pipeline.execute("task-1", COMPOSED, new CollectingSession("admitted"));
        } finally {
            admission.get().close();
        }

        assertEquals(List.of(COMPOSED), runtime.messages,
                "the composed prompt quoting Java reaches the model, not the redirect");
    }

    @Test
    void outsideAnAdmittedTurnTheComposedPromptIsScopedAsBefore() {
        pipeline.admitScope("task-1", "Analyze the market for AI agent frameworks on the JVM")
                .orElseThrow().close();

        pipeline.execute("task-1", COMPOSED, new CollectingSession("closed"));

        assertEquals(List.of(REDIRECT), runtime.messages,
                "once the admission is closed the pipeline scopes every prompt again");
    }

    @Test
    void theAdmissionHoldsOnlyOnTheThreadThatOpenedIt() throws Exception {
        var admission = pipeline.admitScope("task-1",
                "Analyze the market for AI agent frameworks on the JVM").orElseThrow();
        try {
            var other = Thread.ofVirtual().start(() ->
                    pipeline.execute("task-1", COMPOSED, new CollectingSession("other-thread")));
            other.join(5_000);
        } finally {
            admission.close();
        }

        assertEquals(List.of(REDIRECT), runtime.messages,
                "a prompt streamed from another thread is scoped as before");
    }

    @Test
    void anotherPipelineIsScopedInsideAnAdmittedTurn() {
        var otherRuntime = new RecordingRuntime();
        var other = new AiPipeline(otherRuntime, "You advise startups.", null,
                null, null, List.of(), List.of(scope()), List.of(), null, null);
        var admission = pipeline.admitScope("task-1",
                "Analyze the market for AI agent frameworks on the JVM").orElseThrow();
        try {
            other.execute("task-2", COMPOSED, new CollectingSession("other-pipeline"));
        } finally {
            admission.close();
        }

        assertEquals(List.of(REDIRECT), otherRuntime.messages,
                "the admission is this pipeline's, not every pipeline's on the thread");
    }

    private static ScopePolicy scope() {
        return new ScopePolicy("scope::advisor", "test", "1.0",
                new ScopeConfig("Startup advisory: market analysis and financial projections",
                        List.of(), AgentScope.Breach.POLITE_REDIRECT, REDIRECT,
                        AgentScope.Tier.RULE_BASED, 0.45, false, false, ""),
                new RuleBasedScopeGuardrail());
    }

    /** Records the message of each model call. */
    private static final class RecordingRuntime implements AgentRuntime {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override public String name() { return "recording"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            messages.add(context.message());
            session.complete();
        }
    }
}
