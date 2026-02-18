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

import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.OpenAiCompatibleClient;
import org.atmosphere.room.Room;
import org.atmosphere.room.VirtualRoomMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * A {@link VirtualRoomMember} backed by an LLM. When a message is broadcast
 * in a {@link Room}, this member sends it to the configured model and streams
 * the response back to all room members.
 *
 * <pre>{@code
 * var settings = AiConfig.get();
 * var assistant = new LlmRoomMember("assistant", settings.client(), settings.model());
 * room.joinVirtual(assistant);
 *
 * // Now any room.broadcast("question") triggers an LLM response
 * // streamed back to all human members
 * }</pre>
 *
 * @since 4.0
 */
public class LlmRoomMember implements VirtualRoomMember {

    private static final Logger logger = LoggerFactory.getLogger(LlmRoomMember.class);

    private final String id;
    private final OpenAiCompatibleClient client;
    private final String model;
    private final String systemPrompt;

    /**
     * Create an LLM room member with the default system prompt.
     */
    public LlmRoomMember(String id, OpenAiCompatibleClient client, String model) {
        this(id, client, model, "You are a helpful assistant participating in a chat room. "
                + "Keep responses concise and conversational.");
    }

    /**
     * Create an LLM room member with a custom system prompt.
     */
    public LlmRoomMember(String id, OpenAiCompatibleClient client, String model, String systemPrompt) {
        this.id = Objects.requireNonNull(id, "id");
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
        this.systemPrompt = systemPrompt;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void onMessage(Room room, String senderId, Object message) {
        // Don't respond to our own messages (avoid loops)
        if (id.equals(senderId)) return;

        var text = message.toString();
        if (text.isBlank()) return;

        Thread.startVirtualThread(() -> {
            try {
                var request = ChatCompletionRequest.builder(model)
                        .system(systemPrompt)
                        .user(text)
                        .build();

                var sb = new StringBuilder();
                client.streamChatCompletion(request, new StreamingSession() {
                    private volatile boolean closed;

                    @Override
                    public String sessionId() {
                        return id + "-" + System.nanoTime();
                    }

                    @Override
                    public void send(String token) {
                        sb.append(token);
                    }

                    @Override
                    public void sendMetadata(String key, Object value) {
                        // no-op for room broadcast
                    }

                    @Override
                    public void progress(String msg) {
                        // no-op for room broadcast
                    }

                    @Override
                    public void complete() {
                        closed = true;
                        if (!sb.isEmpty()) {
                            room.broadcast(sb.toString());
                        }
                    }

                    @Override
                    public void complete(String summary) {
                        closed = true;
                        room.broadcast(summary != null ? summary : sb.toString());
                    }

                    @Override
                    public void error(Throwable t) {
                        closed = true;
                        logger.error("LLM error for virtual member '{}' in room '{}'", id, room.name(), t);
                    }

                    @Override
                    public boolean isClosed() {
                        return closed;
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to process message for virtual member '{}'", id, e);
            }
        });
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of("type", "llm", "model", model);
    }
}
