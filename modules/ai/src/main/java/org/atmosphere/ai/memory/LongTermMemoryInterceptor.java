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

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.memory.MemorySafetyConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interceptor that injects long-term facts into the system prompt (pre)
 * and extracts new facts from conversation history (post) using a
 * configurable {@link MemoryExtractionStrategy}.
 *
 * <p>Configure on {@code @AiEndpoint}:</p>
 * <pre>{@code
 * @AiEndpoint(path = "/chat", interceptors = LongTermMemoryInterceptor.class)
 * }</pre>
 */
public class LongTermMemoryInterceptor implements AiInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryInterceptor.class);

    private final LongTermMemory memory;
    private final MemoryExtractionStrategy strategy;
    private final AgentRuntime extractionRuntime;
    private final int maxFactsToInject;
    private final MemoryConsolidationStrategy consolidation;
    private final ConcurrentMap<String, AtomicInteger> messageCounts = new ConcurrentHashMap<>();

    /**
     * @param memory            the long-term memory store
     * @param strategy          when/how to extract facts
     * @param extractionRuntime the runtime to use for fact extraction (can be cheap/fast model)
     * @param maxFactsToInject  max facts to inject into system prompt
     * @param consolidation     how/when to consolidate the accumulated fact set
     *                          ({@link MemoryConsolidationStrategy#disabled()} to keep
     *                          the prior behaviour)
     */
    public LongTermMemoryInterceptor(LongTermMemory memory,
                                     MemoryExtractionStrategy strategy,
                                     AgentRuntime extractionRuntime,
                                     int maxFactsToInject,
                                     MemoryConsolidationStrategy consolidation) {
        // Default-on injection-safety screen on the write path (OWASP Agentic
        // A03 — Memory Poisoning): extracted facts are screened before they are
        // persisted and later re-injected into the system prompt. The framework
        // resolves the policy at startup via MemorySafetyConfig.installDefault;
        // it begins fail-closed-on, so memory is screened even before the
        // Spring / Quarkus bridge runs. No-op when already screened.
        this.memory = MemorySafetyConfig.installedDefault().wrap(memory);
        this.strategy = strategy;
        this.extractionRuntime = extractionRuntime;
        this.maxFactsToInject = maxFactsToInject;
        this.consolidation = consolidation != null
                ? consolidation : MemoryConsolidationStrategy.disabled();
    }

    public LongTermMemoryInterceptor(LongTermMemory memory,
                                     MemoryExtractionStrategy strategy,
                                     AgentRuntime extractionRuntime,
                                     int maxFactsToInject) {
        this(memory, strategy, extractionRuntime, maxFactsToInject,
                MemoryConsolidationStrategy.disabled());
    }

    public LongTermMemoryInterceptor(LongTermMemory memory,
                                     MemoryExtractionStrategy strategy,
                                     AgentRuntime extractionRuntime) {
        this(memory, strategy, extractionRuntime, 20, MemoryConsolidationStrategy.disabled());
    }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        var userId = request.userId();
        if (userId == null || userId.isBlank()) {
            return request;
        }

        var facts = memory.getFacts(userId, maxFactsToInject);
        if (facts.isEmpty()) {
            return request;
        }

        var factsBlock = String.join("\n- ", facts);
        var augmentedPrompt = (request.systemPrompt() != null ? request.systemPrompt() + "\n\n" : "")
                + "Known facts about this user:\n- " + factsBlock;

        logger.debug("Injected {} long-term facts for user {}", facts.size(), userId);
        return request.withSystemPrompt(augmentedPrompt);
    }

    @Override
    public void postProcess(AiRequest request, AtmosphereResource resource) {
        var userId = request.userId();
        if (userId == null || userId.isBlank()) {
            return;
        }

        var conversationId = request.conversationId() != null
                ? request.conversationId() : resource.uuid();
        var count = messageCounts
                .computeIfAbsent(conversationId, k -> new AtomicInteger())
                .incrementAndGet();

        if (strategy.shouldExtract(conversationId, request.message(), count)) {
            var conversationText = buildConversationText(request);
            if (!conversationText.isBlank()) {
                try {
                    var facts = strategy.extractFacts(conversationText, extractionRuntime);
                    if (!facts.isEmpty()) {
                        memory.saveFacts(userId, facts);
                        logger.debug("Extracted {} facts for user {} (strategy: {})",
                                facts.size(), userId, strategy.getClass().getSimpleName());
                        maybeConsolidate(userId);
                    }
                } catch (Exception e) {
                    logger.warn("Fact extraction failed for user {}: {}", userId, e.getMessage());
                }
            }
        }
    }

    /**
     * Trigger fact extraction on session disconnect. Called by the handler
     * when the client disconnects. This is where {@link OnSessionCloseStrategy}
     * does its work — it returns false from {@code shouldExtract()} (per-message)
     * but extraction happens here at session end.
     *
     * @param userId         the user identifier
     * @param conversationId the conversation that just ended
     * @param history        the full conversation history
     */
    public void onDisconnect(String userId, String conversationId,
                             java.util.List<org.atmosphere.ai.llm.ChatMessage> history) {
        if (userId == null || userId.isBlank() || history.isEmpty()) {
            return;
        }

        var sb = new StringBuilder();
        for (var msg : history) {
            sb.append(msg.role()).append(": ").append(msg.content()).append('\n');
        }
        var conversationText = sb.toString();

        try {
            var facts = strategy.extractFacts(conversationText, extractionRuntime);
            if (!facts.isEmpty()) {
                memory.saveFacts(userId, facts);
                logger.info("Extracted {} facts for user {} on disconnect",
                        facts.size(), userId);
                maybeConsolidate(userId);
            }
        } catch (Exception e) {
            logger.warn("Fact extraction on disconnect failed for user {}: {}",
                    userId, e.getMessage());
        }
        messageCounts.remove(conversationId);
    }

    /**
     * Consolidate a user's accumulated facts when the configured
     * {@link MemoryConsolidationStrategy} says so. Best-effort: a model error,
     * timeout, or empty/garbled result leaves the store untouched, and the
     * consolidated set is applied only when it is non-empty and no larger than
     * the original (so consolidation can never grow or empty the store).
     */
    private void maybeConsolidate(String userId) {
        if (consolidation == MemoryConsolidationStrategy.disabled()) {
            return;
        }
        try {
            var count = memory.factCount(userId);
            if (!consolidation.shouldConsolidate(userId, count)) {
                return;
            }
            var all = memory.getFacts(userId, Integer.MAX_VALUE);
            var consolidated = consolidation.consolidate(all, extractionRuntime);
            if (!consolidated.isEmpty() && consolidated.size() <= all.size()
                    && !consolidated.equals(all)) {
                memory.replaceFacts(userId, consolidated);
                logger.info("Consolidated long-term memory for user {}: {} -> {} facts",
                        userId, all.size(), consolidated.size());
            }
        } catch (Exception e) {
            logger.warn("Memory consolidation failed for user {}: {}", userId, e.getMessage());
        }
    }

    private static String buildConversationText(AiRequest request) {
        var sb = new StringBuilder();
        for (var msg : request.history()) {
            sb.append(msg.role()).append(": ").append(msg.content()).append('\n');
        }
        sb.append("user: ").append(request.message());
        return sb.toString();
    }
}
