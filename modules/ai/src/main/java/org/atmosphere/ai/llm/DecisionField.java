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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The decision whose value distribution {@link OpenAiCompatibleClient} scores
 * from {@code top_logprobs}: one top-level property of the structured response
 * together with the values the schema allows it to take. Resolved by
 * {@code BuiltInAgentRuntime} from the response type's JSON Schema — the same
 * schema the model is told to follow — via {@link #fromSchema(String, String)}.
 *
 * @param name   the JSON property name
 * @param values the allowed values, as they appear in the JSON text (enum
 *               constants as the schema lists them; {@code true}/{@code false}
 *               for a boolean)
 * @param quoted {@code true} when the value is a JSON string (enum),
 *               {@code false} when it is a bare literal (boolean)
 */
public record DecisionField(String name, List<String> values, boolean quoted) {

    private static final Logger logger = LoggerFactory.getLogger(DecisionField.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Provider ceiling on {@code top_logprobs} (OpenAI chat completions: 0..20). */
    static final int MAX_TOP_LOGPROBS = 20;

    /**
     * Upper bound on allowed values — beyond it the provider's top
     * alternatives cannot cover the value set and the distribution would be
     * mostly unobserved, so {@link #fromSchema} declines such a field. The
     * constructor enforces it for every caller: the scorer keeps a value set
     * in an {@code int} bit mask and its worst-case assignment is exponential
     * in the number of values (Invariant #3).
     */
    static final int MAX_VALUES = 16;

    public DecisionField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        values = List.copyOf(new LinkedHashSet<>(values));
        if (name.isBlank() || values.isEmpty()) {
            throw new IllegalArgumentException("name and values must not be empty");
        }
        if (values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("a decision field allows at most " + MAX_VALUES
                    + " values, got " + values.size());
        }
    }

    /**
     * {@code top_logprobs} to request: one alternative slot per allowed value
     * plus three for formatting variants (leading space, quote, a merged
     * delimiter), capped at the provider maximum of {@value #MAX_TOP_LOGPROBS}
     * (not reached while {@link #MAX_VALUES} holds; kept as a guard should
     * either bound change).
     */
    public int topLogprobs() {
        return Math.min(MAX_TOP_LOGPROBS, values.size() + 3);
    }

    /**
     * Resolve a decision field from a response type's JSON Schema. Only a
     * top-level property qualifies, and only when it is a string {@code enum}
     * (every constant a string) or a {@code boolean}; anything else — a free
     * string, a number, a nested property, a malformed schema — returns empty,
     * because there is no closed value set to build a distribution over.
     *
     * @param jsonSchema the raw JSON Schema of the response type
     * @param field      the designated property name
     * @return the decision field, or empty when {@code field} is not a
     *         top-level enum or boolean property of the schema
     */
    public static Optional<DecisionField> fromSchema(String jsonSchema, String field) {
        if (jsonSchema == null || jsonSchema.isBlank() || field == null || field.isBlank()) {
            return Optional.empty();
        }
        try {
            var property = MAPPER.readTree(jsonSchema).path("properties").path(field);
            if (!property.isObject()) {
                return Optional.empty();
            }
            var enumNode = property.get("enum");
            if (enumNode != null && enumNode.isArray()) {
                var values = new ArrayList<String>();
                for (var v : enumNode) {
                    if (!v.isString()) {
                        return Optional.empty();
                    }
                    values.add(v.stringValue());
                }
                if (values.isEmpty() || values.size() > MAX_VALUES) {
                    return Optional.empty();
                }
                return Optional.of(new DecisionField(field, values, true));
            }
            var type = property.get("type");
            if (type != null && type.isString() && "boolean".equals(type.stringValue())) {
                return Optional.of(new DecisionField(field, List.of("true", "false"), false));
            }
            return Optional.empty();
        } catch (JacksonException e) {
            logger.debug("Response schema is not parseable JSON; no decision field resolved", e);
            return Optional.empty();
        }
    }
}
