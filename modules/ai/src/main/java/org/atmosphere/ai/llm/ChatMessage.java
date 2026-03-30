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
 * A chat message with a role and content, following the OpenAI chat completions format.
 *
 * @param role       the message role ("system", "user", "assistant", "tool")
 * @param content    the message text
 * @param toolCallId the tool call ID this message responds to (only for role "tool")
 */
public record ChatMessage(String role, String content, String toolCallId) {

    public ChatMessage(String role, String content) {
        this(role, content, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage tool(String content, String toolCallId) {
        return new ChatMessage("tool", content, toolCallId);
    }
}
