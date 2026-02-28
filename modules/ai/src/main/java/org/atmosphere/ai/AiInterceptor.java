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

import org.atmosphere.cpr.AtmosphereResource;

/**
 * Interceptor for AI requests. Allows cross-cutting concerns like RAG context
 * augmentation, guardrails, logging, and cost tracking to be applied without
 * modifying the {@code @Prompt} method.
 *
 * <p>Interceptors are specified on {@link org.atmosphere.ai.annotation.AiEndpoint#interceptors()}
 * and are executed in declaration order:</p>
 * <ul>
 *   <li>{@link #preProcess} runs FIFO (first declared → first executed)</li>
 *   <li>{@link #postProcess} runs LIFO (last declared → first executed),
 *       matching the {@code AtmosphereInterceptor} convention</li>
 * </ul>
 */
public interface AiInterceptor {

    /**
     * Called before the request is sent to the {@link AiSupport}.
     * Return a modified request (e.g., with augmented message or different model)
     * or the original request unchanged.
     *
     * @param request  the AI request
     * @param resource the atmosphere resource for this client
     * @return the (possibly modified) request
     */
    default AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        return request;
    }

    /**
     * Called after the {@link AiSupport} has finished streaming.
     *
     * @param request  the AI request (as modified by preProcess)
     * @param resource the atmosphere resource for this client
     */
    default void postProcess(AiRequest request, AtmosphereResource resource) {
    }
}
