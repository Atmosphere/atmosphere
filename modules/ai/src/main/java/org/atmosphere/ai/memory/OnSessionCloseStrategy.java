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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Default strategy: extract facts on session close only.
 * {@link #shouldExtract} always returns false (extraction is triggered
 * externally by the interceptor's disconnect handler, not per-message).
 */
final class OnSessionCloseStrategy implements MemoryExtractionStrategy {

    private static final String EXTRACTION_PROMPT = """
            Extract key facts about the user from this conversation.
            Return ONLY a JSON array of short factual statements. Example:
            ["User's name is Alice", "Lives in Montreal", "Has a golden retriever named Max"]
            If no notable facts, return [].
            Do not include opinions, questions, or conversational filler.

            Conversation:
            %s""";

    @Override
    public boolean shouldExtract(String conversationId, String message, int messageCount) {
        return false;
    }

    @Override
    public List<String> extractFacts(String conversationText, AgentRuntime runtime) {
        if (conversationText == null || conversationText.isBlank()) {
            return List.of();
        }

        var prompt = EXTRACTION_PROMPT.formatted(conversationText);
        var context = new AgentExecutionContext(
                prompt,
                "You are a fact extraction assistant. Respond with JSON only.",
                null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null);

        var text = new StringBuilder();
        var latch = new CountDownLatch(1);

        runtime.execute(context, new StreamingSession() {
            @Override public String sessionId() { return "fact-extraction"; }
            @Override public void send(String chunk) { text.append(chunk); }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { latch.countDown(); }
            @Override public void complete(String summary) {
                if (summary != null) { text.setLength(0); text.append(summary); }
                latch.countDown();
            }
            @Override public void error(Throwable t) { latch.countDown(); }
            @Override public boolean isClosed() { return latch.getCount() == 0; }
        });

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        return parseJsonArray(text.toString());
    }

    static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        var trimmed = json.trim();
        // Handle markdown code blocks
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        }
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return List.of();
        }
        // Simple JSON array parser for string arrays
        var inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        var facts = new ArrayList<String>();
        int i = 0;
        while (i < inner.length()) {
            // Skip whitespace and commas
            while (i < inner.length() && (inner.charAt(i) == ',' || inner.charAt(i) == ' '
                    || inner.charAt(i) == '\n' || inner.charAt(i) == '\r')) {
                i++;
            }
            if (i >= inner.length() || inner.charAt(i) != '"') {
                break;
            }
            int start = i + 1;
            i = start;
            while (i < inner.length()) {
                if (inner.charAt(i) == '\\') {
                    i += 2;
                } else if (inner.charAt(i) == '"') {
                    break;
                } else {
                    i++;
                }
            }
            if (i < inner.length()) {
                facts.add(inner.substring(start, i));
                i++; // skip closing quote
            }
        }
        return List.copyOf(facts);
    }
}
