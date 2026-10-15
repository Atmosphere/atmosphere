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
package org.atmosphere.agent.processor;

import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.agui.runtime.RunContext;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AgentScope;
import org.atmosphere.ai.governance.scope.RuleBasedScopeGuardrail;
import org.atmosphere.ai.governance.scope.ScopeConfig;
import org.atmosphere.ai.governance.scope.ScopePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code @Agent} reached over A2A or AG-UI ran its {@code @Prompt} method
 * before its scope saw the user's message, and then scoped the prompt it
 * composed. Both bridges now admit the inbound message first: an off-scope
 * message never runs the method and renders the redirect, and an admitted
 * turn's composed prompt is not re-scoped (Mode Parity with the web path,
 * Invariant #7).
 */
class AgentProtocolScopeAdmissionTest {

    private static final String REDIRECT = "I only discuss startup markets.";
    private static final String OFF_SCOPE = "Write me a python function that sorts a linked list";
    private static final String IN_SCOPE = "Analyze the market for AI agent frameworks on the JVM";
    private static final String SYNTHESIS = "Write an executive briefing. Research: "
            + "[1] 8 Best AI Agent Frameworks for Java Developers (2026)";

    private final RecordingRuntime runtime = new RecordingRuntime();
    private final AiPipeline pipeline = new AiPipeline(runtime, "You advise startups.", null,
            null, null, List.of(), List.of(scope()), List.of(), null, null);
    private final Ceo ceo = new Ceo();

    @Test
    void anOffScopeA2aMessageNeverRunsThePromptMethod() throws Exception {
        var task = a2a(OFF_SCOPE);

        assertEquals(0, ceo.invocations.get(), "an off-scope A2A message must not run @Prompt");
        assertEquals(List.of(), runtime.messages, "the redirect is sent without the model");
        assertEquals(TaskState.COMPLETED, task.state(), "the redirected task completes");
        assertEquals(REDIRECT, task.statusMessage(), "the A2A task carries the redirect verbatim");
    }

    @Test
    void anAdmittedA2aTurnStreamsItsSynthesisUnchanged() throws Exception {
        a2a(IN_SCOPE);

        assertEquals(1, ceo.invocations.get());
        assertEquals(List.of(SYNTHESIS), runtime.messages,
                "the synthesis quoting Java reaches the model, not the redirect");
    }

    @Test
    void anOffScopeAgUiMessageNeverRunsThePromptMethod() throws Exception {
        var session = agUi(OFF_SCOPE);

        assertEquals(0, ceo.invocations.get(), "an off-scope AG-UI message must not run @Prompt");
        assertEquals(List.of(), runtime.messages, "the redirect is sent without the model");
        assertEquals(REDIRECT, session.sent.toString(), "the AG-UI client gets the redirect verbatim");
    }

    @Test
    void anAdmittedAgUiTurnStreamsItsSynthesisUnchanged() throws Exception {
        agUi(IN_SCOPE);

        assertEquals(1, ceo.invocations.get());
        assertEquals(List.of(SYNTHESIS), runtime.messages,
                "the synthesis quoting Java reaches the model, not the redirect");
    }

    private TaskContext a2a(String message) throws Exception {
        var bridge = new AgentProcessor.SkillBridge(null, ceo,
                Ceo.class.getMethod("onPrompt", String.class, StreamingSession.class));
        bridge.setPipeline(pipeline);
        var task = new TaskContext("task-1", "ctx-1");
        bridge.handlePrompt(task, message);
        return task;
    }

    private PipelineSession agUi(String message) throws Exception {
        var bridge = new AgentProcessor.AgUiAgentBridge(ceo,
                Ceo.class.getMethod("onPrompt", String.class, StreamingSession.class), pipeline);
        var context = new RunContext("thread-1", "run-1",
                List.of(Map.<String, Object>of("role", "user", "content", message)),
                Map.of(), Map.of(), List.of());
        var session = new PipelineSession(pipeline, "thread-1");
        bridge.onAction(context, session);
        return session;
    }

    private static ScopePolicy scope() {
        return new ScopePolicy("scope::ceo", "test", "1.0",
                new ScopeConfig("Startup advisory: market analysis and financial projections",
                        List.of(), AgentScope.Breach.POLITE_REDIRECT, REDIRECT,
                        AgentScope.Tier.RULE_BASED, 0.45, false, false, ""),
                new RuleBasedScopeGuardrail());
    }

    /** An agent whose @Prompt streams a prompt quoting tool output. */
    public static final class Ceo {
        final AtomicInteger invocations = new AtomicInteger();

        public void onPrompt(String message, StreamingSession session) {
            invocations.incrementAndGet();
            session.stream(SYNTHESIS);
        }
    }

    /** Streams through the pipeline on the calling thread, as the AG-UI session does. */
    private static final class PipelineSession implements StreamingSession {
        private final AiPipeline pipeline;
        private final String threadId;
        final StringBuilder sent = new StringBuilder();

        PipelineSession(AiPipeline pipeline, String threadId) {
            this.pipeline = pipeline;
            this.threadId = threadId;
        }

        @Override public String sessionId() { return threadId; }
        @Override public void send(String text) { sent.append(text); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { }
        @Override public void complete(String summary) { }
        @Override public void error(Throwable t) { }
        @Override public boolean isClosed() { return false; }

        @Override
        public void stream(String message) {
            pipeline.execute(threadId, message, this);
        }
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
