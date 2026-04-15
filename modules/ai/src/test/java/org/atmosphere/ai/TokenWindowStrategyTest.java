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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenWindowStrategyTest {

    @Test
    void nameIsTokenWindow() {
        assertEquals("token-window", new TokenWindowStrategy().name());
    }

    @Test
    void defaultMaxTokensIs4000() {
        var strategy = new TokenWindowStrategy();
        // A message of 16000 chars = ~4000 tokens should fit
        var messages = List.of(ChatMessage.user("x".repeat(16000)));
        var result = strategy.select(messages, 100);
        assertEquals(1, result.size());
    }

    @Test
    void withinBudgetReturnsAll() {
        var strategy = new TokenWindowStrategy(1000);
        var messages = List.of(
                ChatMessage.user("hello"),
                ChatMessage.assistant("world"));
        var result = strategy.select(messages, 100);
        assertEquals(2, result.size());
    }

    @Test
    void exceedsBudgetDropsOldest() {
        // 5 tokens max, each message ~2 tokens (8 chars / 4)
        var strategy = new TokenWindowStrategy(5);
        var messages = List.of(
                ChatMessage.user("aaaaaaaa"),
                ChatMessage.user("bbbbbbbb"),
                ChatMessage.user("cccccccc"),
                ChatMessage.user("dddddddd"),
                ChatMessage.user("eeeeeeee"));
        var result = strategy.select(messages, 100);
        // Budget only fits ~2 messages, should drop older ones
        assertTrue(result.size() < 5, "Should not keep all messages: " + result.size());
        assertEquals("eeeeeeee", result.getLast().content());
    }

    @Test
    void systemMessagesAlwaysIncluded() {
        var strategy = new TokenWindowStrategy(20);
        var messages = List.of(
                ChatMessage.system("be nice"),
                ChatMessage.user("hello"),
                ChatMessage.user("world"));
        var result = strategy.select(messages, 100);
        assertTrue(result.stream().anyMatch(m -> "system".equals(m.role())));
        assertEquals("be nice", result.getFirst().content());
    }

    @Test
    void systemMessagesExhaustBudget() {
        // System message is 4000 chars = 1000 tokens, budget is 1000
        var strategy = new TokenWindowStrategy(1000);
        var messages = List.of(
                ChatMessage.system("s".repeat(4000)),
                ChatMessage.user("hello"));
        var result = strategy.select(messages, 100);
        // Should keep system, no budget left for user
        assertEquals(1, result.size());
        assertEquals("system", result.getFirst().role());
    }

    @Test
    void emptyHistoryReturnsEmpty() {
        var strategy = new TokenWindowStrategy(100);
        var result = strategy.select(List.of(), 100);
        assertTrue(result.isEmpty());
    }

    @Test
    void returnedListIsUnmodifiable() {
        var strategy = new TokenWindowStrategy(100);
        var result = strategy.select(List.of(ChatMessage.user("hi")), 100);
        assertThrows(UnsupportedOperationException.class, () -> result.add(ChatMessage.user("x")));
    }

    @Test
    void nullContentMessageCountsAsOneToken() {
        var strategy = new TokenWindowStrategy(5);
        var messages = List.of(
                new ChatMessage("user", null),
                ChatMessage.user("abcd"));
        var result = strategy.select(messages, 100);
        assertEquals(2, result.size());
    }
}
