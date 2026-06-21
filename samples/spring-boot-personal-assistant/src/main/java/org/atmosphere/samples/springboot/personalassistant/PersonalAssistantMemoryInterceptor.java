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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.List;

/**
 * Gives the assistant a real long-term memory: recalls stored user facts into
 * the system prompt before the runtime sees a request ({@link #preProcess}),
 * and extracts new facts from the conversation when the session closes
 * ({@link #onDisconnect}). Facts persist across reconnects for the same user,
 * so "Alice has a golden retriever named Max" mentioned in one session is
 * recalled automatically in a later one.
 *
 * <p>The {@code @AiEndpoint(interceptors=...)} scanner instantiates this class
 * via its no-arg constructor, so the heavy lifting lives in the framework's
 * {@link org.atmosphere.ai.memory.LongTermMemoryInterceptor}, built by
 * {@link LongTermMemoryConfig} (which resolves the live {@code AgentRuntime}
 * for extraction) and published into {@link LongTermMemoryHolder}. This is the
 * same no-arg-interceptor + static-holder pattern {@link McpToolsInterceptor}
 * uses for the remote MCP tool source — the only way to bridge a
 * reflectively-created interceptor to Spring-managed dependencies.</p>
 *
 * <p>Best-effort: if {@link LongTermMemoryConfig} could not build the backend
 * (e.g. no runtime available), the holder is empty and every method falls
 * through, leaving the request untouched.</p>
 */
public class PersonalAssistantMemoryInterceptor implements AiInterceptor {

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var delegate = LongTermMemoryHolder.interceptor();
        if (delegate == null) {
            return request;
        }
        return delegate.preProcess(request, resource);
    }

    @Override
    public void postProcess(AiRequest request, AtmosphereResource resource) {
        var delegate = LongTermMemoryHolder.interceptor();
        if (delegate == null) {
            return;
        }
        delegate.postProcess(request, resource);
    }

    @Override
    public void onDisconnect(String userId, String conversationId, List<ChatMessage> history) {
        var delegate = LongTermMemoryHolder.interceptor();
        if (delegate == null) {
            return;
        }
        delegate.onDisconnect(userId, conversationId, history);
    }
}
