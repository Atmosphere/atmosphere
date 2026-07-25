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

import org.atmosphere.cpr.AtmosphereConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed configuration for the OpenAI-compatible serving endpoint.
 *
 * <p>All values come from framework init-params (bridged from Spring
 * properties by the Spring Boot starter, or set directly via
 * {@code framework.addInitParameter(...)} in embedded deployments):</p>
 *
 * <ul>
 *   <li>{@code atmosphere.ai.openai.enabled} — master switch,
 *       <strong>{@code false} by default</strong> (Correctness Invariant #6:
 *       a new inbound surface never ships on silently).</li>
 *   <li>{@code atmosphere.ai.openai.models.<name>=<agent>} — maps an inbound
 *       {@code model} field value to a registered agent / endpoint name. When
 *       at least one mapping is configured, the map is a whitelist: only
 *       mapped names (plus the default agent, when set) are served.</li>
 *   <li>{@code atmosphere.ai.openai.default-agent} — agent served when the
 *       request's {@code model} is blank or not otherwise resolvable. Unset
 *       by default, so an unknown model yields a 404
 *       {@code model_not_found} error.</li>
 *   <li>{@code atmosphere.ai.openai.api-key} — when set, requests must carry
 *       {@code Authorization: Bearer <key>}; a missing or mismatched key is
 *       rejected with a 401 OpenAI error envelope. When unset, the endpoint
 *       itself performs no authentication (a startup warning is logged) and
 *       relies on whatever framework-level interceptors — e.g. the
 *       {@code AuthInterceptor} installed by a {@code TokenValidator} — gate
 *       the deployment.</li>
 * </ul>
 *
 * @param enabled      whether the endpoint is registered at all
 * @param models       inbound model name → registered agent name (may be empty)
 * @param defaultAgent fallback agent name, or {@code null} for strict 404s
 * @param apiKey       static bearer key, or {@code null} for no endpoint-level auth
 */
public record OpenAiServing(
        boolean enabled,
        Map<String, String> models,
        String defaultAgent,
        String apiKey) {

    /** Master switch init-param; default {@code false}. */
    public static final String ENABLED_PARAM = "atmosphere.ai.openai.enabled";

    /** Prefix for model-name → agent-name mapping init-params. */
    public static final String MODELS_PARAM_PREFIX = "atmosphere.ai.openai.models.";

    /** Fallback agent init-param; unset means unknown models 404. */
    public static final String DEFAULT_AGENT_PARAM = "atmosphere.ai.openai.default-agent";

    /** Optional static bearer key init-param. */
    public static final String API_KEY_PARAM = "atmosphere.ai.openai.api-key";

    /** Path the chat-completions handler is registered at. */
    public static final String CHAT_COMPLETIONS_PATH = "/atmosphere/v1/chat/completions";

    /** Path the model-listing handler is registered at. */
    public static final String MODELS_PATH = "/atmosphere/v1/models";

    public OpenAiServing {
        models = models != null ? Map.copyOf(models) : Map.of();
        defaultAgent = blankToNull(defaultAgent);
        apiKey = blankToNull(apiKey);
    }

    /**
     * Parse the serving configuration from the framework's init-params.
     */
    public static OpenAiServing from(AtmosphereConfig config) {
        var enabled = config.getInitParameter(ENABLED_PARAM, false);
        var models = new LinkedHashMap<String, String>();
        var names = config.getInitParameterNames();
        while (names != null && names.hasMoreElements()) {
            var name = names.nextElement();
            if (name != null && name.startsWith(MODELS_PARAM_PREFIX)) {
                var modelName = name.substring(MODELS_PARAM_PREFIX.length());
                var agentName = config.getInitParameter(name);
                if (!modelName.isBlank() && agentName != null && !agentName.isBlank()) {
                    models.put(modelName.strip(), agentName.strip());
                }
            }
        }
        return new OpenAiServing(enabled, models,
                config.getInitParameter(DEFAULT_AGENT_PARAM),
                config.getInitParameter(API_KEY_PARAM));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
