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
import com.google.adk.tools.BaseTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    private volatile boolean toolsRegistered;
    private final ReentrantLock toolsLock = new ReentrantLock();

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
     * Create and set a Runner with Atmosphere tool definitions bridged to ADK tools.
     * ADK requires tools to be registered at agent construction time, so this must
     * be called before streaming begins.
     *
     * @param settings the LLM settings (model, API key, etc.)
     * @param tools    Atmosphere tool definitions to bridge to ADK
     */
    public static void configureWithTools(AiConfig.LlmSettings settings,
                                          List<ToolDefinition> tools) {
        var apiKey = settings.apiKey();
        var gemini = new Gemini(settings.model(), apiKey);

        var adkTools = AdkToolBridge.toAdkTools(tools);
        var agentBuilder = LlmAgent.builder()
                .name("atmosphere-agent")
                .model(gemini)
                .instruction("You are a helpful assistant.");

        if (!adkTools.isEmpty()) {
            agentBuilder.tools((Object[]) adkTools.toArray(new BaseTool[0]));
        }

        staticRunner = new InMemoryRunner(agentBuilder.build(), "atmosphere");
        logger.info("ADK configured with {} tools: model={}", adkTools.size(), settings.model());
    }

    /**
     * Lazily rebuild the ADK runner with tools from the AgentExecutionContext. Called on the
     * first request that contains tool definitions. The runner is replaced in-place
     * and used for all subsequent requests.
     */
    private void rebuildRunnerWithTools(AgentExecutionContext context) {
        toolsLock.lock();
        try {
            if (toolsRegistered) {
                return;
            }
            var settings = AiConfig.get();
            if (settings == null) {
                settings = AiConfig.fromEnvironment();
            }
            var apiKey = settings.apiKey();
            var gemini = new Gemini(settings.model(), apiKey);

            var adkTools = AdkToolBridge.toAdkTools(context.tools());
            var instruction = context.systemPrompt() != null && !context.systemPrompt().isEmpty()
                    ? context.systemPrompt() : "You are a helpful assistant.";

            var agentBuilder = LlmAgent.builder()
                    .name("atmosphere-agent")
                    .model(gemini)
                    .instruction(instruction);

            if (!adkTools.isEmpty()) {
                agentBuilder.tools((Object[]) adkTools.toArray(new BaseTool[0]));
            }

            var runner = new InMemoryRunner(agentBuilder.build(), "atmosphere");
            setNativeClient(runner);
            knownSessions.clear();
            toolsRegistered = true;
            logger.info("ADK rebuilt with {} tools and system prompt ({} chars)",
                    adkTools.size(), instruction.length());
        } finally {
            toolsLock.unlock();
        }
    }

    @Override
    protected void doExecute(Runner adkRunner, AgentExecutionContext context, StreamingSession session) {
        // ADK requires tools at agent construction time. If the AgentExecutionContext contains
        // tools that haven't been registered yet, rebuild the runner with those tools.
        var tools = context.tools();
        if (!tools.isEmpty() && !toolsRegistered) {
            rebuildRunnerWithTools(context);
            adkRunner = getNativeClient();
        }

        var userId = context.userId() != null ? context.userId() : defaultUserId;
        var sessionId = context.sessionId() != null ? context.sessionId() : defaultSessionId;

        ensureSession(adkRunner, userId, sessionId);

        var events = adkRunner.runAsync(
                userId,
                sessionId,
                Content.fromParts(Part.fromText(context.message()))
        );
        AdkEventAdapter.bridge(events, session);
    }

    @SuppressWarnings("deprecation") // ADK 0.2.0 createSession API; updated in later versions
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
                    .createSession(adkRunner.appName(), userId, new ConcurrentHashMap<>(), sessionId)
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
                AiCapability.SYSTEM_PROMPT
        );
    }
}
