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
package org.atmosphere.ai.filter;

import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link AiStreamBroadcastFilter} that tracks token counts per session and per
 * broadcaster (user/topic), and can enforce token budgets by aborting streams
 * that exceed their allocation.
 *
 * <p>This filter does <b>not</b> modify token content — it only counts tokens,
 * injects cost metadata on stream completion, and optionally enforces limits.</p>
 *
 * <h3>Token counting</h3>
 * <p>Each "token" message increments a per-session counter and a per-broadcaster
 * counter. The token count here is the number of streaming chunks, not LLM tokens.
 * For precise LLM token counts, use the {@code usage.totalTokens} metadata emitted
 * by the LLM client.</p>
 *
 * <h3>Budget enforcement</h3>
 * <p>When a per-broadcaster budget is set via {@link #setBudget(String, long)},
 * the filter will {@code ABORT} any token message that would exceed the limit.
 * An error message is injected to notify the client.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var metering = new CostMeteringFilter();
 * metering.setBudget("user-123-broadcaster", 10000); // max 10K tokens
 * broadcaster.getBroadcasterConfig().addFilter(metering);
 * }</pre>
 */
public class CostMeteringFilter extends AiStreamBroadcastFilter {

    private static final Logger logger = LoggerFactory.getLogger(CostMeteringFilter.class);

    private final ConcurrentHashMap<String, AtomicLong> sessionTokenCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> broadcasterTokenCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> budgets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> exceededSessions = new ConcurrentHashMap<>();

    @Override
    protected BroadcastAction filterAiMessage(
            String broadcasterId, AiStreamMessage msg, String originalJson, RawMessage rawMessage) {

        if (msg.isToken()) {
            return handleToken(broadcasterId, msg, rawMessage);
        }

        if (msg.isComplete()) {
            return handleComplete(broadcasterId, msg, rawMessage);
        }

        if (msg.isError()) {
            cleanup(msg.sessionId());
            return new BroadcastAction(rawMessage);
        }

        return new BroadcastAction(rawMessage);
    }

    private BroadcastAction handleToken(String broadcasterId, AiStreamMessage msg, RawMessage rawMessage) {
        // If this session already exceeded its budget, silently drop subsequent tokens
        if (exceededSessions.containsKey(msg.sessionId())) {
            return new BroadcastAction(BroadcastAction.ACTION.ABORT, rawMessage);
        }

        var sessionCount = sessionTokenCounts
                .computeIfAbsent(msg.sessionId(), k -> new AtomicLong())
                .incrementAndGet();

        var broadcasterCount = broadcasterTokenCounts
                .computeIfAbsent(broadcasterId, k -> new AtomicLong())
                .incrementAndGet();

        // Check budget
        var budget = budgets.get(broadcasterId);
        if (budget != null && broadcasterCount > budget) {
            logger.warn("Token budget exceeded for broadcaster {}: {} > {}",
                    broadcasterId, broadcasterCount, budget);

            // Mark session as exceeded so subsequent tokens are silently dropped
            exceededSessions.put(msg.sessionId(), Boolean.TRUE);

            // Inject a single error message to notify the client
            var errorMsg = new AiStreamMessage("error",
                    "Token budget exceeded (" + budget + " tokens)",
                    msg.sessionId(), msg.seq(), null, null);
            return new BroadcastAction(BroadcastAction.ACTION.SKIP, new RawMessage(errorMsg.toJson()));
        }

        return new BroadcastAction(rawMessage);
    }

    private BroadcastAction handleComplete(String broadcasterId, AiStreamMessage msg, RawMessage rawMessage) {
        var sessionCount = sessionTokenCounts.getOrDefault(msg.sessionId(), new AtomicLong(0)).get();
        var broadcasterCount = broadcasterTokenCounts.getOrDefault(broadcasterId, new AtomicLong(0)).get();

        logger.debug("Session {} completed: {} tokens (broadcaster {} total: {})",
                msg.sessionId(), sessionCount, broadcasterId, broadcasterCount);

        cleanup(msg.sessionId());
        return new BroadcastAction(rawMessage);
    }

    private void cleanup(String sessionId) {
        sessionTokenCounts.remove(sessionId);
        exceededSessions.remove(sessionId);
    }

    /**
     * Set a token budget for a broadcaster.
     *
     * @param broadcasterId the broadcaster ID
     * @param maxTokens     maximum number of token messages allowed
     */
    public void setBudget(String broadcasterId, long maxTokens) {
        budgets.put(broadcasterId, maxTokens);
    }

    /**
     * Remove a token budget for a broadcaster.
     *
     * @param broadcasterId the broadcaster ID
     */
    public void removeBudget(String broadcasterId) {
        budgets.remove(broadcasterId);
    }

    /**
     * Get the current token count for a session.
     *
     * @param sessionId the session ID
     * @return the number of token messages seen for this session
     */
    public long getSessionTokenCount(String sessionId) {
        var counter = sessionTokenCounts.get(sessionId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Get the current token count for a broadcaster.
     *
     * @param broadcasterId the broadcaster ID
     * @return the cumulative number of token messages across all sessions
     */
    public long getBroadcasterTokenCount(String broadcasterId) {
        var counter = broadcasterTokenCounts.get(broadcasterId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Reset the token counter for a broadcaster. Useful for rolling windows.
     *
     * @param broadcasterId the broadcaster ID
     */
    public void resetBroadcasterCount(String broadcasterId) {
        broadcasterTokenCounts.remove(broadcasterId);
    }
}
