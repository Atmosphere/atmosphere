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
package org.atmosphere.ai.state;

import org.atmosphere.ai.llm.ChatMessage;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Unified state primitive for Atmosphere AI agents. One SPI covers the four
 * concerns every agent has:
 *
 * <ul>
 *   <li><b>Conversation history</b> — the ordered turn-by-turn message stream
 *       per {@code agentId} × {@code sessionId}. Replaces the existing
 *       {@code AiConversationMemory} surface.</li>
 *   <li><b>Durable facts</b> — long-lived things the agent has learned about
 *       the user (e.g. "ChefFamille prefers bun over npm"). Keyed by
 *       {@code userId} × {@code agentId}.</li>
 *   <li><b>Daily notes</b> — running per-day notes the agent maintains as it
 *       works. Keyed by {@code userId} × {@code agentId} × {@link LocalDate}.</li>
 *   <li><b>Working memory</b> — transient per-session scratch space. Cleared
 *       on session close.</li>
 *   <li><b>Hierarchical rules</b> — the assembled identity + persona + user
 *       profile + operating rules that prefix every conversation.</li>
 * </ul>
 *
 * <h2>File-first design</h2>
 *
 * The default backend is {@link FileSystemAgentState}, which reads and writes
 * plain Markdown and JSONL under an OpenClaw-compatible workspace root. The
 * model only remembers what is saved to disk; users can {@code cat}, {@code grep},
 * {@code vim}, or {@code git commit} their agent's memory.
 *
 * <p>A structured {@code DatabaseAgentState} backend exists for enterprise
 * multi-tenant or audit-grade deployments, but the file-backed default is the
 * recommended shape for personal agents and zero-config compatibility with the
 * OpenClaw ecosystem.</p>
 *
 * <h2>Runtime-agnostic</h2>
 *
 * {@code AgentState} lives in the Atmosphere platform layer above every
 * {@link org.atmosphere.ai.AgentRuntime}. Six of the seven runtimes already
 * consume {@code AgentExecutionContext.history()} (which this SPI supplies);
 * the ADK runtime gains the same behavior via the contract-test-enforced fix.
 *
 * @see FileSystemAgentState
 * @see AutoMemoryStrategy
 * @see MemoryEntry
 * @see RuleSet
 */
public interface AgentState {

    // ---------- Conversation history ----------

    /**
     * Return the ordered conversation history for the given session, oldest
     * message first.
     *
     * @param agentId   the agent identity (typically the {@code @Agent} name)
     * @param sessionId the durable session identifier
     * @return an unmodifiable list of prior messages, never {@code null}
     */
    List<ChatMessage> getConversation(String agentId, String sessionId);

    /**
     * Append a message to the conversation history. Implementations may apply
     * size caps or retention policies; callers should not rely on unlimited
     * retention.
     */
    void appendConversation(String agentId, String sessionId, ChatMessage message);

    /**
     * Clear the conversation history for a session. Called on session close
     * when retention policy requires it; does not affect durable facts.
     */
    void clearConversation(String agentId, String sessionId);

    // ---------- Durable facts ----------

    /**
     * Return the durable facts the agent has learned about this user.
     * Loaded from {@code MEMORY.md} in the workspace by the file backend.
     *
     * @return facts in insertion order (oldest first), never {@code null}
     */
    List<MemoryEntry> getFacts(String userId, String agentId);

    /**
     * Record a new durable fact. Returns the stored entry including its
     * assigned identifier, which admin endpoints use to reference it for
     * edit or delete operations.
     */
    MemoryEntry addFact(String userId, String agentId, String content);

    /**
     * Remove a single fact by identifier. Silently no-op if the identifier is
     * unknown.
     */
    void removeFact(String userId, String agentId, String factId);

    // ---------- Daily notes ----------

    /**
     * Return the daily notes for a given date. Loaded from
     * {@code memory/YYYY-MM-DD.md} in the workspace by the file backend.
     */
    List<MemoryEntry> getDailyNotes(String userId, String agentId, LocalDate date);

    /**
     * Append a daily note to today's notes file.
     */
    MemoryEntry addDailyNote(String userId, String agentId, String content);

    // ---------- Working memory (session-scoped, ephemeral) ----------

    /**
     * Read a session-scoped working-memory value by key. Working memory is
     * transient and not persisted beyond the session; use it for in-flight
     * scratch state the agent builds up during a run.
     */
    Optional<String> getWorkingMemory(String sessionId, String key);

    /**
     * Write a session-scoped working-memory value.
     */
    void setWorkingMemory(String sessionId, String key, String value);

    /**
     * Clear all working memory for a session. Called on session close.
     */
    void clearWorkingMemory(String sessionId);

    // ---------- Hierarchical rules ----------

    /**
     * Return the assembled rule set for this agent and user. The rule set
     * is the identity + persona + user profile + operating rules drawn from
     * (in the file backend) {@code IDENTITY.md} + {@code SOUL.md} +
     * {@code USER.md} + {@code AGENTS.md} respectively.
     *
     * <p>The returned {@link RuleSet#systemPrompt()} is the composed system
     * prompt that should prefix every conversation turn.</p>
     */
    RuleSet getRules(String userId, String agentId);

    // ---------- Workspace metadata ----------

    /**
     * Return the on-disk workspace root for this agent, or {@link Optional#empty()}
     * if the backend has no filesystem concept (e.g. {@code DatabaseAgentState}).
     * Admin inspection endpoints use this to surface the workspace tree to
     * end users.
     */
    Optional<Path> workspaceRoot(String agentId);
}
