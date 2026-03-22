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
package org.atmosphere.ai;

import org.atmosphere.ai.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link StreamingSession} wrapper that captures streamed streaming texts and saves
 * the full conversation turn (user message + assistant response) to an
 * {@link AiConversationMemory} when streaming completes.
 *
 * <p>This solves the core challenge of async streaming: the LLM response arrives
 * streaming-text-by-streaming-text via callbacks, so we need to accumulate the full
 * response before we can store it in memory.</p>
 */
class MemoryCapturingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(MemoryCapturingSession.class);

    private final StreamingSession delegate;
    private final AiConversationMemory memory;
    private final String conversationId;
    private final String userMessage;
    private final StringBuilder accumulated = new StringBuilder();

    MemoryCapturingSession(StreamingSession delegate, AiConversationMemory memory,
                           String conversationId, String userMessage) {
        this.delegate = delegate;
        this.memory = memory;
        this.conversationId = conversationId;
        this.userMessage = userMessage;
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String text) {
        accumulated.append(text);
        delegate.send(text);
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void complete() {
        saveToMemory(accumulated.toString());
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        saveToMemory(summary);
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        // On error, save only the user message (no partial assistant response)
        try {
            memory.addMessage(conversationId, ChatMessage.user(userMessage));
        } catch (Exception e) {
            logger.error("Failed to save user message to memory on error", e);
        }
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void emit(AiEvent event) {
        if (event instanceof AiEvent.TextDelta delta) {
            accumulated.append(delta.text());
        }
        delegate.emit(event);
    }

    @Override
    public void stream(String message) {
        delegate.stream(message);
    }

    private void saveToMemory(String assistantResponse) {
        memory.addMessage(conversationId, ChatMessage.user(userMessage));
        if (!assistantResponse.isEmpty()) {
            memory.addMessage(conversationId, ChatMessage.assistant(assistantResponse));
        }
    }
}
