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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageCapturingChatModelTest {

    @AfterEach
    void clearCollector() {
        UsageCapturingChatModel.endCapture();
    }

    @Test
    void accumulatesUsageAcrossMultipleCalls() {
        var delegate = new RecordingChatModel(
                makeResponse("hello", 10, 5, 15, "qwen-test"),
                makeResponse(" world", 8, 4, 12, "qwen-test"));
        var wrapper = new UsageCapturingChatModel(delegate);

        var collector = UsageCapturingChatModel.beginCapture();
        wrapper.call(new Prompt(List.of(new UserMessage("first"))));
        wrapper.call(new Prompt(List.of(new UserMessage("second"))));

        assertTrue(collector.hasCounts());
        assertEquals(18L, collector.promptTokens());
        assertEquals(9L, collector.completionTokens());
        assertEquals(27L, collector.totalTokens());
        assertEquals("qwen-test", collector.model());
        assertEquals(2L, collector.callCount());
        assertEquals(2, delegate.callCount.get());
    }

    @Test
    void streamingAccumulatesUsagePerEmission() {
        var delegate = new RecordingChatModel(
                makeResponse("a", 3, 1, 4, "qwen-stream"));
        var wrapper = new UsageCapturingChatModel(delegate);

        var collector = UsageCapturingChatModel.beginCapture();
        wrapper.stream(new Prompt(List.of(new UserMessage("first")))).blockLast();
        wrapper.stream(new Prompt(List.of(new UserMessage("second")))).blockLast();

        assertEquals(6L, collector.promptTokens());
        assertEquals(2L, collector.completionTokens());
        assertEquals(8L, collector.totalTokens());
        assertEquals(2L, collector.callCount());
    }

    @Test
    void totalDerivedWhenProviderReportsZero() {
        var delegate = new RecordingChatModel(
                makeResponse("x", 7, 3, 0, null));
        var wrapper = new UsageCapturingChatModel(delegate);

        var collector = UsageCapturingChatModel.beginCapture();
        wrapper.call(new Prompt(List.of(new UserMessage("u"))));

        assertEquals(7L, collector.promptTokens());
        assertEquals(3L, collector.completionTokens());
        assertEquals(10L, collector.totalTokens(),
                "total must be derived from prompt+completion when provider returns 0");
    }

    @Test
    void usageMissingLeavesCollectorEmpty() {
        var delegate = new RecordingChatModel(makeResponseNoUsage("anything"));
        var wrapper = new UsageCapturingChatModel(delegate);

        var collector = UsageCapturingChatModel.beginCapture();
        wrapper.call(new Prompt(List.of(new UserMessage("u"))));

        assertFalse(collector.hasCounts(),
                "wrapper must not invent usage when the response carries none");
        assertEquals(1L, collector.callCount(),
                "call count still records the invocation");
    }

    @Test
    void delegateAccessorExposesUnwrappedModel() {
        var delegate = new RecordingChatModel(makeResponse("x", 1, 1, 2, "m"));
        var wrapper = new UsageCapturingChatModel(delegate);
        assertSame(delegate, wrapper.delegate());
    }

    @Test
    void nullDelegateRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new UsageCapturingChatModel(null));
    }

    @Test
    void endCaptureClearsThreadLocal() {
        var first = UsageCapturingChatModel.beginCapture();
        first.accumulate(10, 5, 15, "m");
        UsageCapturingChatModel.endCapture();

        var second = UsageCapturingChatModel.beginCapture();
        assertFalse(second.hasCounts(),
                "endCapture must reset so the next dispatch starts at zero");
    }

    private static ChatResponse makeResponse(String text, int prompt, int completion,
                                             int total, String model) {
        var metadata = ChatResponseMetadata.builder()
                .usage(new SimpleUsage(prompt, completion, total))
                .model(model != null ? model : "")
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private static ChatResponse makeResponseNoUsage(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static final class RecordingChatModel implements ChatModel {
        private final ChatResponse[] responses;
        final AtomicInteger callCount = new AtomicInteger();

        RecordingChatModel(ChatResponse... responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int index = Math.min(callCount.getAndIncrement(), responses.length - 1);
            return responses[index];
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    private record SimpleUsage(Integer promptTokens, Integer completionTokens,
                               Integer totalTokens) implements Usage {
        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Integer getTotalTokens() {
            return totalTokens;
        }

        @Override
        public Object getNativeUsage() {
            return null;
        }
    }
}
