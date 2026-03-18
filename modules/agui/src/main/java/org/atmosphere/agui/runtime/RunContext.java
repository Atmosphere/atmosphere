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
package org.atmosphere.agui.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Per-run state received from the AG-UI client. Contains the thread ID, run ID,
 * conversation messages, shared state, forwarded properties, and available tool
 * definitions.
 *
 * <p>Immutable once constructed — all collection fields are defensively copied
 * in the compact constructor.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunContext(
        String threadId,
        String runId,
        List<Map<String, Object>> messages,
        Map<String, Object> state,
        Map<String, Object> forwardedProps,
        List<Map<String, Object>> tools
) {
    public RunContext {
        messages = messages != null ? List.copyOf(messages) : List.of();
        state = state != null ? Map.copyOf(state) : Map.of();
        forwardedProps = forwardedProps != null ? Map.copyOf(forwardedProps) : Map.of();
        tools = tools != null ? List.copyOf(tools) : List.of();
    }

    /**
     * Extracts the content of the last user message from the conversation history.
     *
     * @return the last user message content, or an empty string if none found
     */
    public String lastUserMessage() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                var content = msg.get("content");
                if (content instanceof String s) {
                    return s;
                }
                return String.valueOf(content);
            }
        }
        return "";
    }
}
