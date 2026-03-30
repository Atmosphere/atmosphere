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

/**
 * Accumulates streamed tool call fragments from the OpenAI-compatible
 * SSE format. Each tool call arrives incrementally across multiple
 * {@code delta.tool_calls} chunks: the first chunk carries the function
 * name and tool call ID, subsequent chunks append to the arguments string.
 */
final class ToolCallAccumulator {

    private String id;
    private String functionName;
    private final StringBuilder arguments = new StringBuilder();

    String id() {
        return id;
    }

    void setId(String id) {
        this.id = id;
    }

    String functionName() {
        return functionName;
    }

    void setFunctionName(String name) {
        this.functionName = name;
    }

    void appendArguments(String chunk) {
        arguments.append(chunk);
    }

    String arguments() {
        return arguments.toString();
    }
}
