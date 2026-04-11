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
package org.atmosphere.ai.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.Gemini;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link org.atmosphere.ai.AiSupport} implementation backed by Google ADK's {@link Runner}.
 *
 * <p>Auto-detected when {@code google-adk} is on the classpath.
 * The runner must be configured via {@link #setRunner} — typically done
 * by application configuration or Spring auto-configuration.</p>
 */
public class AdkAgentRuntime extends AbstractAgentRuntime<Runner> {

    private static final Logger logger = LoggerFactory.getLogger(AdkAgentRuntime.class);

    private static volatile String defaultUserId = "atmosphere-user";
    private static volatile String defaultSessionId = "atmosphere-session";
    private static final Set<String> knownSessions = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return "google-adk";
    }

    @Override
    protected String nativeClientClassName() {
        return "com.google.adk.runner.Runner";
    }

    @Override
    protected String clientDescription() {
        return "Runner";
    }

    @Override
    protected String configurationHint() {
        return "Call AdkAgentRuntime.setRunner() or use Spring auto-configuration.";
    }

    @Override
    protected Runner createNativeClient(AiConfig.LlmSettings settings) {
        var apiKey = settings.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }

        if (settings.model() != null && !settings.model().startsWith("gemini")) {
            logger.warn("ADK only supports Gemini models natively. '{}' may not work. "
                    + "Consider atmosphere-spring-ai or atmosphere-langchain4j for other providers.",
                    settings.model());
        }

        var gemini = new Gemini(settings.model(), apiKey);
        var agent = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction("You are a helpful assistant.")
                .build();
        var runner = new InMemoryRunner(agent, "atmosphere");
        logger.info("ADK auto-configured: model={}", settings.model());
        return runner;
    }

    /**
     * Set the {@link Runner} to use for streaming.
     */
    public static void setRunner(Runner adkRunner) {
        staticRunner = adkRunner;
    }

    // Held for static setter compatibility with Spring auto-configuration
    private static volatile Runner staticRunner;

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        // If a static runner was set via Spring auto-configuration, use it
        if (getNativeClient() == null && staticRunner != null) {
            setNativeClient(staticRunner);
        }
        super.configure(settings);
    }

    /**
     * Set default user and session IDs for ADK invocations.
     */
    public static void setDefaults(String userId, String sessionId) {
        defaultUserId = userId;
        defaultSessionId = sessionId;
    }

    /**
     * Create and set a tool-less Runner from the given settings. Kept for
     * Spring auto-configuration; tool-calling flows rebuild the runner per request
     * inside {@link #doExecute} so each invocation captures its own
     * {@link StreamingSession} and {@link org.atmosphere.ai.approval.ApprovalStrategy}.
     *
     * @param settings the LLM settings (model, API key, etc.)
     * @param tools    ignored — tool registration is now per-request for HITL correctness
     */
    public static void configureWithTools(AiConfig.LlmSettings settings,
                                          List<ToolDefinition> tools) {
        var apiKey = settings.apiKey();
        var gemini = new Gemini(settings.model(), apiKey);
        var agent = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction("You are a helpful assistant.")
                .build();
        staticRunner = new InMemoryRunner(agent, "atmosphere");
        if (tools != null && !tools.isEmpty()) {
            logger.info("ADK configured (model={}); {} tools will be registered per-request",
                    settings.model(), tools.size());
        } else {
            logger.info("ADK configured: model={}", settings.model());
        }
    }

    /**
     * Build a per-request {@link Runner} whose tools capture the caller's
     * {@link StreamingSession} and {@link org.atmosphere.ai.approval.ApprovalStrategy}.
     * ADK requires tools at agent construction time, so each streaming request rebuilds
     * the agent and runner — this is the only way to bind HITL context per invocation
     * without a ThreadLocal that ADK's reactive scheduler would not propagate.
     */
    private Runner buildRequestRunner(AgentExecutionContext context, StreamingSession session) {
        var settings = AiConfig.get();
        if (settings == null) {
            settings = AiConfig.fromEnvironment();
        }
        var gemini = new Gemini(settings.model(), settings.apiKey());

        var adkTools = AdkToolBridge.toAdkTools(
                context.tools(), session, context.approvalStrategy());
        var instruction = context.systemPrompt() != null && !context.systemPrompt().isEmpty()
                ? context.systemPrompt() : "You are a helpful assistant.";

        var agentBuilder = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction(instruction);

        if (!adkTools.isEmpty()) {
            agentBuilder.tools(adkTools);
        }

        logger.debug("ADK per-request runner built with {} tools", adkTools.size());
        return new InMemoryRunner(agentBuilder.build(), "atmosphere");
    }

    @Override
    protected void doExecute(Runner adkRunner, AgentExecutionContext context, StreamingSession session) {
        // Delegate to the cancellation-aware variant and block on whenDone() so the
        // legacy void contract holds. This keeps the Flowable subscription logic in
        // one place instead of drifting between execute and executeWithHandle.
        var handle = doExecuteWithHandle(adkRunner, context, session);
        try {
            handle.whenDone().join();
        } catch (Exception e) {
            if (!session.isClosed()) {
                session.error(e);
            }
        }
    }

    @Override
    protected ExecutionHandle doExecuteWithHandle(
            Runner adkRunner, AgentExecutionContext context, StreamingSession session) {
        // For tool-calling requests, build a fresh runner whose tools capture this
        // invocation's session + ApprovalStrategy. The tool-less fallback runner held
        // in getNativeClient() is only used for prompts without tools.
        var tools = context.tools();
        Runner requestRunner = tools.isEmpty() ? adkRunner : buildRequestRunner(context, session);

        var userId = context.userId() != null ? context.userId() : defaultUserId;
        var sessionId = context.sessionId() != null ? context.sessionId() : defaultSessionId;

        ensureSession(requestRunner, userId, sessionId);

        var events = requestRunner.runAsync(
                userId,
                sessionId,
                Content.fromParts(Part.fromText(context.message()))
        );

        // D-6 follow-up: wire the AdkEventAdapter's existing cancel() + whenDone()
        // into the Phase 2 ExecutionHandle SPI so external callers can abort an
        // in-flight ADK run via the unified API.
        var adapter = AdkEventAdapter.bridge(events, session);
        return new ExecutionHandle() {
            private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    adapter.cancel();
                }
            }

            @Override
            public boolean isDone() {
                return adapter.whenDone().isDone();
            }

            @Override
            public java.util.concurrent.CompletableFuture<Void> whenDone() {
                return adapter.whenDone();
            }
        };
    }

    private static void ensureSession(Runner adkRunner, String userId, String sessionId) {
        var key = userId + ":" + sessionId;
        if (knownSessions.contains(key)) {
            return;
        }

        var existing = adkRunner.sessionService()
                .getSession(adkRunner.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        if (existing == null) {
            adkRunner.sessionService()
                    .createSession(adkRunner.appName(), userId, Map.of(), sessionId)
                    .blockingGet();
            logger.debug("Created ADK session: userId={}, sessionId={}", userId, sessionId);
        }
        knownSessions.add(key);
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOOL_CALLING,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.AGENT_ORCHESTRATION,
                AiCapability.CONVERSATION_MEMORY,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.TOOL_APPROVAL
        );
    }
}
