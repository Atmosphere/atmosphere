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

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SPI for intelligent model routing and failover. Routes requests to the
 * best available AI backend based on capabilities, health, and strategy.
 *
 * <p>Mirrors Atmosphere's transport failover pattern (WebSocket → SSE → long-polling)
 * applied to the AI layer (GPT-4 → Claude → Gemini).</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * @AiEndpoint(path = "/chat",
 *             models = {"gpt-4o", "claude-sonnet-4-6"},
 *             fallbackStrategy = FallbackStrategy.FAILOVER)
 * }</pre>
 */
public interface ModelRouter {

    /**
     * Select the best {@link AiSupport} for the given request.
     *
     * @param request            the AI request
     * @param availableBackends  the available AI backends
     * @param requiredCapabilities capabilities the backend must support
     * @return the selected backend, or empty if none are suitable
     */
    Optional<AiSupport> route(
            AiRequest request,
            List<AiSupport> availableBackends,
            Set<AiCapability> requiredCapabilities
    );

    /**
     * Report that a backend failed, so the router can track health.
     *
     * @param backend the backend that failed
     * @param error   the error that occurred
     */
    void reportFailure(AiSupport backend, Throwable error);

    /**
     * Report that a backend succeeded, so the router can track health.
     *
     * @param backend the backend that succeeded
     */
    void reportSuccess(AiSupport backend);

    /**
     * Fallback strategies for model routing.
     */
    enum FallbackStrategy {

        /** Use the primary model only. No fallback. */
        NONE,

        /** On failure, try the next backend in priority order. */
        FAILOVER,

        /** Distribute requests across backends (weighted by priority). */
        ROUND_ROBIN,

        /** Route based on request characteristics (e.g., model hint, message length). */
        CONTENT_BASED
    }
}
