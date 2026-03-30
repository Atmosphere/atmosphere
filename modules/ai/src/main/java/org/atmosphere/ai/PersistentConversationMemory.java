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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link AiConversationMemory} implementation backed by a
 * {@link ConversationPersistence} store. Maintains a write-through cache
 * in memory and persists after each mutation.
 *
 * <p>This bridges Atmosphere's durable session infrastructure to AI
 * conversation memory. Plug in a {@code ConversationPersistence} backed
 * by Redis, SQLite, or DurableSession metadata, and conversations survive
 * server restarts.</p>
 *
 * <p>The sliding-window eviction logic is identical to
 * {@link InMemoryConversationMemory} — oldest non-system messages are
 * evicted when the limit is exceeded.</p>
 */
public class PersistentConversationMemory implements AiConversationMemory {

    private static final Logger logger = LoggerFactory.getLogger(PersistentConversationMemory.class);

    private final ConversationPersistence persistence;
    private final int maxMessages;
    private final ConcurrentMap<String, List<ChatMessage>> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public PersistentConversationMemory(ConversationPersistence persistence) {
        this(persistence, 20);
    }

    public PersistentConversationMemory(ConversationPersistence persistence, int maxMessages) {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("maxMessages must be >= 2, got " + maxMessages);
        }
        this.persistence = persistence;
        this.maxMessages = maxMessages;
    }

    @Override
    public List<ChatMessage> getHistory(String conversationId) {
        var messages = cache.get(conversationId);
        if (messages != null) {
            var lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
            lock.lock();
            try {
                return List.copyOf(messages);
            } finally {
                lock.unlock();
            }
        }

        // Try loading from persistence
        var stored = persistence.load(conversationId);
        if (stored.isEmpty()) {
            return List.of();
        }

        var loaded = deserialize(stored.get());
        cache.put(conversationId, new ArrayList<>(loaded));
        locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        return List.copyOf(loaded);
    }

    @Override
    public void addMessage(String conversationId, ChatMessage message) {
        var lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        var messages = cache.computeIfAbsent(conversationId, k -> {
            var stored = persistence.load(k);
            if (stored.isPresent()) {
                return new ArrayList<>(deserialize(stored.get()));
            }
            return new ArrayList<>();
        });

        lock.lock();
        try {
            messages.add(message);
            while (messages.size() > maxMessages) {
                boolean removed = false;
                for (int i = 0; i < messages.size(); i++) {
                    if (!"system".equals(messages.get(i).role())) {
                        messages.remove(i);
                        removed = true;
                        break;
                    }
                }
                if (!removed) {
                    break; // All remaining messages are system — cannot evict further
                }
            }
            persist(conversationId, messages);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear(String conversationId) {
        cache.remove(conversationId);
        locks.remove(conversationId);
        persistence.remove(conversationId);
    }

    @Override
    public int maxMessages() {
        return maxMessages;
    }

    private void persist(String conversationId, List<ChatMessage> messages) {
        try {
            persistence.save(conversationId, serialize(messages));
        } catch (Exception e) {
            logger.warn("Failed to persist conversation {}: {}", conversationId, e.getMessage());
        }
    }

    /**
     * Serialize chat messages to a simple JSON array format.
     * Uses manual JSON to avoid requiring a JSON library dependency.
     */
    static String serialize(List<ChatMessage> messages) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            var msg = messages.get(i);
            sb.append("{\"role\":\"")
                    .append(escapeJson(msg.role()))
                    .append("\",\"content\":\"")
                    .append(escapeJson(msg.content()))
                    .append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialize chat messages from JSON array format.
     */
    static List<ChatMessage> deserialize(String json) {
        var messages = new ArrayList<ChatMessage>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return messages;
        }

        // Simple JSON array parser for {"role":"...","content":"..."} objects
        int i = 0;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart == -1) {
                break;
            }
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd == -1) {
                break;
            }
            var obj = json.substring(objStart, objEnd + 1);
            var role = extractJsonValue(obj, "role");
            var content = extractJsonValue(obj, "content");
            if (role != null && content != null) {
                messages.add(new ChatMessage(role, content));
            }
            i = objEnd + 1;
        }
        return messages;
    }

    private static int findMatchingBrace(String json, int openBrace) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = openBrace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static String extractJsonValue(String obj, String key) {
        var search = "\"" + key + "\":\"";
        int start = obj.indexOf(search);
        if (start == -1) {
            return null;
        }
        start += search.length();
        var sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (escaped) {
                switch (c) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> {
                        sb.append('\\');
                        sb.append(c);
                    }
                }
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        var sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
