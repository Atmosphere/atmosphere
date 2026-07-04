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
package org.atmosphere.ai.spring.alibaba;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator over a Spring AI {@link ChatModel} that captures
 * {@link ChatResponse#getMetadata()} usage into a per-thread accumulator
 * before delegating. {@link SpringAiAlibabaAgentRuntime} reads the
 * accumulator after {@code ReactAgent.call(...)} returns and emits the
 * typed {@link org.atmosphere.ai.TokenUsage} record via
 * {@link org.atmosphere.ai.StreamingSession#usage(org.atmosphere.ai.TokenUsage)}.
 *
 * <p>Why a per-thread accumulator and not a per-invocation parameter:
 * Spring AI Alibaba's {@code ReactAgent.call(messages)} runs one or more
 * underlying {@code ChatModel.call(prompt)} steps inside its ReAct graph,
 * and the surface API returns only the final {@code AssistantMessage} —
 * there is no per-step hook to ride. A {@link ThreadLocal} on the calling
 * thread accumulates every step's usage across the entire graph run, and
 * the agent runtime resets it on entry and reads it on exit.</p>
 */
public final class UsageCapturingChatModel implements ChatModel {

    private static final ThreadLocal<UsageCollector> COLLECTOR =
            ThreadLocal.withInitial(UsageCollector::new);

    private final ChatModel delegate;

    UsageCapturingChatModel(ChatModel delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate ChatModel must not be null");
        }
        this.delegate = delegate;
    }

    /** Returns the underlying {@link ChatModel} for tests and reflection. */
    public ChatModel delegate() {
        return delegate;
    }

    /** Reset the calling-thread accumulator before a runtime dispatch. */
    static UsageCollector beginCapture() {
        var collector = new UsageCollector();
        COLLECTOR.set(collector);
        return collector;
    }

    /** Clear the calling-thread accumulator after dispatch (success or failure). */
    static void endCapture() {
        COLLECTOR.remove();
    }

    static UsageCollector currentCollector() {
        return COLLECTOR.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var response = delegate.call(prompt);
        recordUsage(response);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt).doOnNext(this::recordUsage);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        // Spring AI 1.1.x (the line the Alibaba agent framework hard-pins):
        // the decorator passes the delegate's options through instead of the
        // interface default (an empty ChatOptions).
        return delegate.getDefaultOptions();
    }

    private void recordUsage(ChatResponse response) {
        if (response == null) {
            return;
        }
        var metadata = response.getMetadata();
        if (metadata == null) {
            return;
        }
        Usage usage = metadata.getUsage();
        if (usage == null) {
            return;
        }
        var collector = COLLECTOR.get();
        collector.accumulate(
                nullToZero(usage.getPromptTokens()),
                nullToZero(usage.getCompletionTokens()),
                nullToZero(usage.getTotalTokens()),
                metadata.getModel());
    }

    private static long nullToZero(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    /** Mutable accumulator scoped to a single agent dispatch. */
    static final class UsageCollector {
        private final AtomicLong promptTokens = new AtomicLong();
        private final AtomicLong completionTokens = new AtomicLong();
        private final AtomicLong totalTokens = new AtomicLong();
        private volatile String model;
        private final AtomicLong callCount = new AtomicLong();

        void accumulate(long prompt, long completion, long total, String model) {
            if (prompt > 0) {
                promptTokens.addAndGet(prompt);
            }
            if (completion > 0) {
                completionTokens.addAndGet(completion);
            }
            if (total > 0) {
                totalTokens.addAndGet(total);
            } else if (prompt > 0 || completion > 0) {
                totalTokens.addAndGet(prompt + completion);
            }
            if (model != null && !model.isBlank()) {
                this.model = model;
            }
            callCount.incrementAndGet();
        }

        long promptTokens() {
            return promptTokens.get();
        }

        long completionTokens() {
            return completionTokens.get();
        }

        long totalTokens() {
            return totalTokens.get();
        }

        String model() {
            return model;
        }

        long callCount() {
            return callCount.get();
        }

        boolean hasCounts() {
            return promptTokens.get() > 0
                    || completionTokens.get() > 0
                    || totalTokens.get() > 0;
        }
    }
}
