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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-1 follow-up contract: {@link AgentRuntime#generateResult} wraps the
 * existing {@link CollectingSession}-based generate path and also captures
 * any {@link TokenUsage} events the runtime emits, returning a typed
 * {@link AgentExecutionResult}.
 */
class AgentExecutionResultTest {

    /** Minimal runtime that emits one text chunk + one usage event. */
    private static final class FakeRuntime implements AgentRuntime {
        @Override public String name() { return "fake"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            session.send("hello ");
            session.send("world");
            session.usage(new TokenUsage(10L, 20L, 0L, 30L, "fake-model"));
            session.complete();
        }
    }

    private static AgentExecutionContext ctx() {
        return new AgentExecutionContext(
                "say hi", null, "fake-model", null, null, null, null,
                List.of(), null, null, List.of(), Map.of(),
                List.<ChatMessage>of(), null, null);
    }

    @Test
    void generateResultCapturesTextAndTokenUsage() {
        var result = new FakeRuntime().generateResult(ctx());

        assertEquals("hello world", result.text());
        assertTrue(result.usage().isPresent());
        assertEquals(10L, result.usage().get().input());
        assertEquals(20L, result.usage().get().output());
        assertEquals(30L, result.usage().get().total());
        assertNotNull(result.duration());
        assertTrue(result.model().isPresent());
        assertEquals("fake-model", result.model().get());
    }

    @Test
    void resultRecordDefaultsFillInForNullArguments() {
        var r = new AgentExecutionResult(null, null, null, null);
        assertEquals("", r.text());
        assertFalse(r.usage().isPresent());
        assertEquals(Duration.ZERO, r.duration());
        assertFalse(r.model().isPresent());
    }

    @Test
    void ofFactoryWithoutUsage() {
        var r = AgentExecutionResult.of("response", Duration.ofMillis(250));
        assertEquals("response", r.text());
        assertFalse(r.usage().isPresent());
        assertEquals(Duration.ofMillis(250), r.duration());
    }

    @Test
    void ofFactoryWithUsage() {
        var usage = TokenUsage.of(5L, 8L);
        var r = AgentExecutionResult.of("response", usage, Duration.ofMillis(100));
        assertTrue(r.usage().isPresent());
        assertEquals(13L, r.usage().get().total());
    }
}
