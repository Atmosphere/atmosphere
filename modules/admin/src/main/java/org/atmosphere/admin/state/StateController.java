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
package org.atmosphere.admin.state;

import org.atmosphere.ai.state.AgentState;
import org.atmosphere.ai.state.MemoryEntry;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admin control-plane surface for inspecting and editing {@link AgentState}.
 * Provides read / write / delete access to the four state categories
 * (conversation transcripts, durable facts, daily notes, hierarchical rules)
 * so end users can see and control what their agent remembers.
 *
 * <p>All mutating methods must be behind authentication and authorization at
 * the transport layer (per Correctness Invariant #6 — admin endpoints default
 * deny). Transport wiring — Quarkus admin extension, Spring Boot admin
 * extension — is responsible for enforcing auth; this class is a pure
 * POJO controller that treats every call as already-authorized.</p>
 */
public final class StateController {

    private final AgentState state;

    public StateController(AgentState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    // ---------- Facts ----------

    /**
     * Return all durable facts the agent remembers about this user. Returns
     * a list of entry maps with keys {@code id}, {@code content}, {@code createdAt}.
     */
    public List<Map<String, Object>> listFacts(String agentId, String userId) {
        return state.getFacts(requireSegment("userId", userId), requireSegment("agentId", agentId))
                .stream()
                .map(StateController::toMap)
                .toList();
    }

    /**
     * Append a new fact. Returns the stored entry.
     */
    public Map<String, Object> addFact(String agentId, String userId, String content) {
        var entry = state.addFact(
                requireSegment("userId", userId),
                requireSegment("agentId", agentId),
                requireContent(content));
        return toMap(entry);
    }

    /**
     * Remove a single fact by identifier. Silently no-op if the identifier
     * is unknown.
     */
    public void removeFact(String agentId, String userId, String factId) {
        state.removeFact(
                requireSegment("userId", userId),
                requireSegment("agentId", agentId),
                requireSegment("factId", factId));
    }

    // ---------- Daily notes ----------

    /**
     * Return daily notes for a given date. {@code date} must be ISO-8601
     * format (YYYY-MM-DD).
     */
    public List<Map<String, Object>> listDailyNotes(String agentId, String userId, String date) {
        var localDate = LocalDate.parse(requireSegment("date", date));
        return state.getDailyNotes(
                        requireSegment("userId", userId),
                        requireSegment("agentId", agentId),
                        localDate)
                .stream()
                .map(StateController::toMap)
                .toList();
    }

    /**
     * Append a daily note to today's notes.
     */
    public Map<String, Object> addDailyNote(String agentId, String userId, String content) {
        var entry = state.addDailyNote(
                requireSegment("userId", userId),
                requireSegment("agentId", agentId),
                requireContent(content));
        return toMap(entry);
    }

    // ---------- Conversation transcripts ----------

    /**
     * Return the conversation transcript for a session. Each message is
     * surfaced as a map with keys {@code role}, {@code content}, and
     * optionally {@code toolCallId}.
     */
    public List<Map<String, Object>> getConversation(String agentId, String sessionId) {
        return state.getConversation(
                        requireSegment("agentId", agentId),
                        requireSegment("sessionId", sessionId))
                .stream()
                .map(message -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("role", message.role());
                    map.put("content", message.content());
                    if (message.toolCallId() != null) {
                        map.put("toolCallId", message.toolCallId());
                    }
                    return (Map<String, Object>) map;
                })
                .toList();
    }

    /**
     * Clear the conversation transcript for a session.
     */
    public void clearConversation(String agentId, String sessionId) {
        state.clearConversation(
                requireSegment("agentId", agentId),
                requireSegment("sessionId", sessionId));
    }

    // ---------- Rules ----------

    /**
     * Return the assembled hierarchical rules (identity + persona + user
     * profile + operating rules). The composed system prompt is included
     * as {@code systemPrompt} so UIs can render both the whole prompt and
     * its individual components.
     */
    public Map<String, Object> getRules(String agentId, String userId) {
        var rules = state.getRules(
                requireSegment("userId", userId),
                requireSegment("agentId", agentId));
        var map = new LinkedHashMap<String, Object>();
        map.put("systemPrompt", rules.systemPrompt());
        map.put("identity", rules.identity());
        map.put("persona", rules.persona());
        map.put("userProfile", rules.userProfile());
        map.put("operatingRules", rules.operatingRules());
        return map;
    }

    /**
     * Return the agent workspace root path as a string, or empty map if the
     * backend has no filesystem concept.
     */
    public Map<String, Object> getWorkspaceRoot(String agentId) {
        var map = new LinkedHashMap<String, Object>();
        state.workspaceRoot(requireSegment("agentId", agentId))
                .ifPresent(path -> map.put("workspaceRoot", path.toString()));
        return map;
    }

    // ---------- Helpers ----------

    private static String requireSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        return content;
    }

    private static Map<String, Object> toMap(MemoryEntry entry) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", entry.id());
        map.put("content", entry.content());
        map.put("createdAt", entry.createdAt().toString());
        return map;
    }
}
