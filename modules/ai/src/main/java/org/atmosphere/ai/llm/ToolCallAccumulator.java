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

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Accumulates streamed tool call fragments from the OpenAI-compatible
 * SSE format. Each tool call arrives incrementally across multiple
 * {@code delta.tool_calls} chunks: the first chunk carries the function
 * name and tool call ID, subsequent chunks append to the arguments string.
 *
 * <p>Provider-neutral: the OpenAI-compatible, Anthropic, and Cohere
 * streaming clients all accumulate the same shape (id + function name +
 * a {@link StringBuilder} of raw-JSON argument fragments) and parse the
 * completed buffer to a {@code Map<String, Object>}. Any provider-specific
 * bookkeeping (e.g. a content-block index) is kept by the client in a
 * local map keyed by that index, not on this type.</p>
 */
public final class ToolCallAccumulator {

    private String id;
    private String functionName;
    private final StringBuilder arguments = new StringBuilder();

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String functionName() {
        return functionName;
    }

    public void setFunctionName(String name) {
        this.functionName = name;
    }

    public void appendArguments(String chunk) {
        arguments.append(chunk);
    }

    public String arguments() {
        return arguments.toString();
    }

    /**
     * Parse the accumulated raw-JSON argument buffer into a
     * {@code Map<String, Object>}. Returns an empty map when nothing was
     * accumulated (the tool takes no arguments), and a single-entry
     * {@code {"__raw": <buffer>}} map when the buffer is not valid JSON, so
     * the caller can still forward the partial fragment to the tool rather
     * than dropping the call. This is the shared behaviour formerly carried
     * by the Anthropic {@code parseInput} and Cohere {@code parseArguments}
     * helpers.
     *
     * @param mapper Jackson mapper supplied by the client (the inherited
     *               {@code MAPPER} of the shared SSE base)
     * @return parsed arguments, {@code Map.of()} when blank, or
     *         {@code Map.of("__raw", arguments())} on a parse failure
     */
    public Map<String, Object> argumentsAsMap(ObjectMapper mapper) {
        var raw = arguments.toString();
        if (raw.isBlank()) {
            return Map.of();
        }
        try {
            // Jackson erases the generic type at runtime, so readValue to
            // Map.class yields a Map<Object, Object> the compiler can only
            // narrow with an unchecked cast — unavoidable for the JSON-object
            // parse, and safe because the source is always a JSON object.
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(raw, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            return Map.of("__raw", raw);
        }
    }
}
