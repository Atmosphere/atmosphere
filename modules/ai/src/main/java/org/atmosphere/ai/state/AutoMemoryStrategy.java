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

import java.util.List;

/**
 * Policy for deciding when (and how) to promote conversation turns into durable
 * facts or daily notes. Pluggable so agent authors can pick the shape that fits
 * their agent.
 *
 * <p>Atmosphere ships four built-in strategies:</p>
 * <ul>
 *   <li>{@link #everyNTurns(int)} — summarize every N turns into a daily note</li>
 *   <li>{@link #llmDecided()} — let the agent call a {@code remember} tool to decide</li>
 *   <li>{@link #sessionEnd()} — summarize once at session close</li>
 *   <li>{@link #hybrid(AutoMemoryStrategy...)} — apply multiple strategies together</li>
 * </ul>
 *
 * <p>Strategies observe turn boundaries and session end; they write to
 * {@link AgentState} via the public surface, same as any other caller.</p>
 */
public interface AutoMemoryStrategy {

    /**
     * Invoked after each user → assistant exchange. The strategy may write
     * facts, daily notes, or working-memory entries via {@code state}.
     *
     * @param state     the mutable state surface to write to
     * @param userId    user identity for fact scoping
     * @param agentId   agent identity for scoping
     * @param sessionId session identity for working memory
     * @param userMsg   the most recent user message
     * @param assistant the most recent assistant response
     */
    void onTurn(AgentState state,
                String userId,
                String agentId,
                String sessionId,
                ChatMessage userMsg,
                ChatMessage assistant);

    /**
     * Invoked once when a session closes. Default no-op; strategies that need
     * end-of-session summarization override this.
     */
    default void onSessionEnd(AgentState state,
                              String userId,
                              String agentId,
                              String sessionId) {
        // No-op by default
    }

    // ---------- Built-in factories ----------

    /**
     * Summarize every N complete turns into a daily note. When {@code n <= 0}
     * the strategy disables itself.
     */
    static AutoMemoryStrategy everyNTurns(int n) {
        return new EveryNTurnsStrategy(n);
    }

    /**
     * Strategy that delegates all memory decisions to the agent itself. The
     * agent is expected to invoke a {@code remember} tool when it wants to
     * record a fact — this strategy only observes, it never writes.
     *
     * <p>This is the OpenClaw / Claude Code default behavior.</p>
     */
    static AutoMemoryStrategy llmDecided() {
        return new LlmDecidedStrategy();
    }

    /**
     * Summarize the conversation once when the session ends.
     */
    static AutoMemoryStrategy sessionEnd() {
        return new SessionEndStrategy();
    }

    /**
     * Compose multiple strategies; each delegate runs in order.
     */
    static AutoMemoryStrategy hybrid(AutoMemoryStrategy... delegates) {
        return new HybridStrategy(List.of(delegates));
    }

    // ---------- Built-in implementations ----------

    final class EveryNTurnsStrategy implements AutoMemoryStrategy {
        private final int n;
        // Intentionally not thread-safe at the counter level: turn cadence is
        // serialized by session; cross-session counters would need per-session
        // state, which we avoid here for simplicity. Strategies needing strict
        // per-session cadence can inherit from this class.
        private int turn;

        EveryNTurnsStrategy(int n) {
            this.n = n;
        }

        @Override
        public void onTurn(AgentState state, String userId, String agentId, String sessionId,
                           ChatMessage userMsg, ChatMessage assistant) {
            if (n <= 0) {
                return;
            }
            turn++;
            if (turn % n == 0) {
                var summary = "Turn " + turn + ": user asked '" + userMsg.content()
                        + "', assistant replied '" + preview(assistant.content()) + "'";
                state.addDailyNote(userId, agentId, summary);
            }
        }

        private static String preview(String content) {
            if (content == null) {
                return "";
            }
            return content.length() > 120 ? content.substring(0, 120) + "..." : content;
        }
    }

    final class LlmDecidedStrategy implements AutoMemoryStrategy {
        @Override
        public void onTurn(AgentState state, String userId, String agentId, String sessionId,
                           ChatMessage userMsg, ChatMessage assistant) {
            // No automatic writes: the agent itself calls a `remember` tool
            // when it wants to store a fact. This strategy exists so agent
            // authors can declare the intended semantics explicitly rather
            // than leaving a default-no-op strategy undocumented.
        }
    }

    final class SessionEndStrategy implements AutoMemoryStrategy {
        @Override
        public void onTurn(AgentState state, String userId, String agentId, String sessionId,
                           ChatMessage userMsg, ChatMessage assistant) {
            // Accumulate silently; writes happen on session end.
        }

        @Override
        public void onSessionEnd(AgentState state, String userId, String agentId, String sessionId) {
            var history = state.getConversation(agentId, sessionId);
            if (history.isEmpty()) {
                return;
            }
            state.addDailyNote(userId, agentId,
                    "Session " + sessionId + " ended after " + history.size() + " messages.");
        }
    }

    final class HybridStrategy implements AutoMemoryStrategy {
        private final List<AutoMemoryStrategy> delegates;

        HybridStrategy(List<AutoMemoryStrategy> delegates) {
            this.delegates = List.copyOf(delegates);
        }

        @Override
        public void onTurn(AgentState state, String userId, String agentId, String sessionId,
                           ChatMessage userMsg, ChatMessage assistant) {
            for (var delegate : delegates) {
                delegate.onTurn(state, userId, agentId, sessionId, userMsg, assistant);
            }
        }

        @Override
        public void onSessionEnd(AgentState state, String userId, String agentId, String sessionId) {
            for (var delegate : delegates) {
                delegate.onSessionEnd(state, userId, agentId, sessionId);
            }
        }
    }
}
