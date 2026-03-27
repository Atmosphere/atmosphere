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

import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;

import java.util.ArrayList;
import java.util.Set;

/**
 * Default fallback {@link org.atmosphere.ai.AgentRuntime} that uses Atmosphere's
 * built-in OpenAI-compatible HTTP client. Priority 0 — always available, used
 * when no framework-specific runtime is on the classpath.
 */
public class BuiltInAgentRuntime extends AbstractAgentRuntime<LlmClient> {

    @Override
    public String name() {
        return "built-in";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    protected String nativeClientClassName() {
        return "org.atmosphere.ai.llm.LlmClient";
    }

    @Override
    protected String clientDescription() {
        return "LlmClient";
    }

    @Override
    protected LlmClient createNativeClient(AiConfig.LlmSettings settings) {
        return settings != null ? settings.client() : null;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (getNativeClient() == null && settings != null) {
            setNativeClient(settings.client());
        }
    }

    @Override
    protected void doExecute(LlmClient client,
                             AgentExecutionContext context, StreamingSession session) {
        var messages = new ArrayList<ChatMessage>();
        if (context.systemPrompt() != null && !context.systemPrompt().isEmpty()) {
            messages.add(ChatMessage.system(context.systemPrompt()));
        }
        for (var h : context.history()) {
            messages.add(new ChatMessage(h.role(), h.content()));
        }
        messages.add(ChatMessage.user(context.message()));

        var builder = ChatCompletionRequest.builder(context.model());
        for (var msg : messages) {
            builder.message(msg);
        }
        if (context.responseType() != null) {
            builder.jsonMode(true);
        }
        var request = builder.build();
        client.streamChatCompletion(request, session);
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(AiCapability.TEXT_STREAMING, AiCapability.SYSTEM_PROMPT);
    }
}
