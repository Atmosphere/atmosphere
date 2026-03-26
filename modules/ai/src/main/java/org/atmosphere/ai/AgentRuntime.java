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

import java.util.Set;

/**
 * SPI for AI agent execution. Replaces {@code AiSupport} with a richer contract
 * that dispatches the entire agent loop — tool calling, memory, RAG, retries —
 * to the AI framework on the classpath.
 *
 * <p>Drop a framework adapter JAR on the classpath and Atmosphere auto-detects
 * it via {@link java.util.ServiceLoader}. When multiple implementations are
 * available, the one with the highest {@link #priority()} that reports
 * {@link #isAvailable()} wins.</p>
 *
 * <p>This is the Servlet model for AI agents: write your {@code @Agent} once,
 * run it on LangChain4j, Google ADK, Spring AI, or standalone — determined
 * by classpath.</p>
 *
 * @see AgentExecutionContext
 * @see AgentRuntimeResolver
 */
public interface AgentRuntime {

    /**
     * Human-readable name (e.g., "langchain4j", "spring-ai", "google-adk", "built-in").
     */
    String name();

    /**
     * Whether this runtime's required dependencies are on the classpath.
     */
    boolean isAvailable();

    /**
     * Priority for auto-detection. Higher values win.
     * The built-in runtime uses priority {@code 0}.
     */
    int priority();

    /**
     * Configure this runtime with LLM settings. Called once after resolution.
     *
     * @param settings the resolved LLM settings
     */
    void configure(AiConfig.LlmSettings settings);

    /**
     * Capabilities supported by this runtime. Used for smart model routing,
     * tool calling negotiation, and feature discovery.
     *
     * @return the set of capabilities this runtime supports
     */
    default Set<AiCapability> capabilities() {
        return Set.of(AiCapability.TEXT_STREAMING);
    }

    /**
     * Execute the full agent loop for a message. The runtime owns tool calling,
     * memory management, RAG augmentation, retries, and streaming. Results are
     * pushed through the session.
     *
     * <p>Guardrails and interceptors are <em>not</em> the runtime's
     * responsibility — they wrap the runtime call externally in the Atmosphere
     * pipeline.</p>
     *
     * @param context the execution context (message, tools, memory, RAG providers, history)
     * @param session the streaming session to push results through
     */
    void execute(AgentExecutionContext context, StreamingSession session);
}
