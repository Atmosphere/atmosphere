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

/**
 * SPI for AI framework backends. Analogous to {@code AsyncSupport} for transports:
 * drop an adapter JAR on the classpath and Atmosphere auto-detects it via
 * {@link java.util.ServiceLoader}.
 *
 * <p>Implementations bridge framework-agnostic {@link AiRequest} objects to
 * framework-specific streaming APIs (Spring AI ChatClient, LangChain4j
 * StreamingChatLanguageModel, Google ADK Runner, etc.).</p>
 *
 * <p>When multiple implementations are on the classpath, the one with the
 * highest {@link #priority()} that reports {@link #isAvailable()} wins.</p>
 *
 * @see DefaultAiSupportResolver
 */
public interface AiSupport {

    /**
     * Human-readable name (e.g., "spring-ai", "langchain4j", "built-in").
     */
    String name();

    /**
     * Whether this implementation's required dependencies are on the classpath.
     */
    boolean isAvailable();

    /**
     * Priority for auto-detection. Higher values win.
     * The built-in implementation uses priority {@code 0}.
     */
    int priority();

    /**
     * Configure this support with LLM settings (model, API key, base URL).
     * Called once after resolution.
     *
     * @param settings the resolved LLM settings
     */
    void configure(AiConfig.LlmSettings settings);

    /**
     * Stream an AI response for the given request, pushing tokens through
     * the session.
     *
     * @param request the framework-agnostic AI request
     * @param session the streaming session to push tokens through
     */
    void stream(AiRequest request, StreamingSession session);
}
