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
package org.atmosphere.ai.openai;

import org.atmosphere.ai.AiConversationMemory;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in wiring for the OpenAI-compatible serving endpoint. Annotation
 * processors ({@code AgentProcessor} for {@code @Agent},
 * {@code AiEndpointProcessor} for {@code @AiEndpoint}) call
 * {@link #registerAgent} for every pipeline they build; the first call on an
 * enabled framework registers a single shared {@link OpenAiChatHandler} at
 * {@link OpenAiServing#CHAT_COMPLETIONS_PATH} and
 * {@link OpenAiServing#MODELS_PATH}, and every call adds the pipeline to the
 * handler's serving registry.
 *
 * <p>When {@code atmosphere.ai.openai.enabled} is unset or {@code false}
 * (the default), this is a no-op: no handler, no new inbound surface
 * (Correctness Invariant #6).</p>
 */
public final class OpenAiServingRegistrar {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiServingRegistrar.class);

    /** Framework-properties key holding the per-framework shared handler. */
    static final String HANDLER_PROPERTY = "org.atmosphere.ai.openai.handler";

    private OpenAiServingRegistrar() {
    }

    /**
     * Register a pipeline as an OpenAI-servable target named {@code name}.
     *
     * @param framework the framework the agent belongs to
     * @param name      the serving name (agent name, or the last segment of an
     *                  {@code @AiEndpoint} path)
     * @param pipeline  the governed dispatch pipeline for the agent
     * @param memory    the agent's conversation memory (may be {@code null};
     *                  client-supplied history is then not threaded)
     * @return {@code true} when serving is enabled and the pipeline was
     *         registered, {@code false} when the endpoint is disabled
     */
    public static synchronized boolean registerAgent(AtmosphereFramework framework, String name,
                                                     AiPipeline pipeline,
                                                     AiConversationMemory memory) {
        var config = framework.getAtmosphereConfig();
        var serving = OpenAiServing.from(config);
        if (!serving.enabled()) {
            return false;
        }
        var properties = config.properties();
        var handler = (OpenAiChatHandler) properties.get(HANDLER_PROPERTY);
        if (handler == null) {
            handler = new OpenAiChatHandler(serving);
            List<AtmosphereInterceptor> interceptors = new ArrayList<>();
            framework.addAtmosphereHandler(OpenAiServing.CHAT_COMPLETIONS_PATH,
                    handler, interceptors);
            framework.addAtmosphereHandler(OpenAiServing.MODELS_PATH,
                    handler, new ArrayList<>());
            properties.put(HANDLER_PROPERTY, handler);
            logger.info("OpenAI-compatible chat completions endpoint enabled at {} "
                            + "(model listing at {}, configured model mappings: {})",
                    OpenAiServing.CHAT_COMPLETIONS_PATH, OpenAiServing.MODELS_PATH,
                    serving.models().isEmpty() ? "none — registered agent names are served"
                            : serving.models().keySet());
            if (serving.apiKey() == null) {
                logger.warn("OpenAI-compatible endpoint has no atmosphere.ai.openai.api-key "
                        + "configured — the endpoint performs no authentication of its own and "
                        + "relies on framework-level interceptors (e.g. AuthInterceptor). Do not "
                        + "expose it publicly without one of the two.");
            }
        }
        handler.register(name, pipeline, memory);
        logger.debug("Agent '{}' registered with the OpenAI-compatible serving endpoint", name);
        return true;
    }

    /**
     * Whether serving is enabled for the given framework — lets callers skip
     * building a pipeline entirely when the endpoint is off.
     */
    public static boolean enabled(AtmosphereFramework framework) {
        return OpenAiServing.from(framework.getAtmosphereConfig()).enabled();
    }
}
