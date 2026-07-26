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
package org.atmosphere.ai.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Defensive parser for provider model-list responses, shared by the three
 * hand-rolled HTTP clients ({@code OpenAiCompatibleClient},
 * {@code AnthropicMessagesClient}, {@code CohereChatClient}) behind
 * {@link org.atmosphere.ai.AiCapability#MODEL_ENUMERATION}.
 *
 * <p>Accepted shapes (verified against the public API conventions):</p>
 * <ul>
 *   <li>OpenAI / Anthropic {@code GET /v1/models}:
 *       {@code {"data":[{"id":"..."} , ...]}} — Anthropic additionally carries
 *       {@code "type":"model"} and pagination fields, all ignored here.</li>
 *   <li>Cohere {@code GET /v1/models}:
 *       {@code {"models":[{"name":"..."} , ...]}}.</li>
 * </ul>
 *
 * <p>Parsing is best-effort at a trust boundary (Correctness Invariant #4):
 * a malformed body, an unexpected shape, or entries missing both {@code id}
 * and {@code name} yield an empty (or partial) list — never an exception.
 * The result is bounded at {@link #MAX_MODELS} entries so a misbehaving
 * endpoint cannot inflate the discovery surface (Invariant #3).</p>
 */
public final class ModelListJson {

    private static final Logger logger = LoggerFactory.getLogger(ModelListJson.class);

    /** Upper bound on parsed entries — a defensive cap, far above any real provider list. */
    static final int MAX_MODELS = 500;

    private ModelListJson() {
    }

    /**
     * Parse a model-list response body into model identifiers.
     *
     * @param mapper the caller's JSON mapper
     * @param body   the raw response body (may be {@code null} or malformed)
     * @return immutable list of model ids in response order, de-duplicated;
     *         empty when nothing parseable is present — never {@code null}
     */
    public static List<String> parse(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            logger.debug("Model-list response not parseable as JSON", e);
            return List.of();
        }
        var entries = root.path("data");
        if (!entries.isArray()) {
            entries = root.path("models");
        }
        if (!entries.isArray()) {
            logger.debug("Model-list response carries neither 'data' nor 'models' array");
            return List.of();
        }
        var ids = new ArrayList<String>();
        for (var entry : entries) {
            if (ids.size() >= MAX_MODELS) {
                logger.debug("Model-list response truncated at {} entries", MAX_MODELS);
                break;
            }
            var id = entry.path("id").asString("");
            if (id.isBlank()) {
                id = entry.path("name").asString("");
            }
            if (!id.isBlank() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }
}
