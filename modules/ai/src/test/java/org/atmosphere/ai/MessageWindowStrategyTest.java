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
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageWindowStrategyTest {

    private final MessageWindowStrategy strategy = new MessageWindowStrategy();

    @Test
    void nameIsMessageWindow() {
        assertEquals("message-window", strategy.name());
    }

    @Test
    void returnsAllWhenUnderLimit() {
        var messages = List.of(
                ChatMessage.user("a"),
                ChatMessage.user("b"));
        var result = strategy.select(messages, 10);
        assertEquals(2, result.size());
    }

    @Test
    void keepsSystemAndTailWhenOverLimit() {
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("old"),
                ChatMessage.user("mid"),
                ChatMessage.user("new"));
        var result = strategy.select(messages, 3);
        assertEquals(3, result.size());
        assertEquals("system", result.get(0).role());
        assertEquals("mid", result.get(1).content());
        assertEquals("new", result.get(2).content());
    }

    @Test
    void preservesMultipleSystemMessages() {
        var messages = List.of(
                ChatMessage.system("s1"),
                ChatMessage.system("s2"),
                ChatMessage.user("u1"),
                ChatMessage.user("u2"),
                ChatMessage.user("u3"));
        var result = strategy.select(messages, 4);
        assertEquals(4, result.size());
        assertEquals("s1", result.get(0).content());
        assertEquals("s2", result.get(1).content());
    }

    @Test
    void onlySystemWhenBudgetExhausted() {
        var messages = List.of(
                ChatMessage.system("s1"),
                ChatMessage.system("s2"),
                ChatMessage.user("u1"));
        var result = strategy.select(messages, 2);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(m -> "system".equals(m.role())));
    }

    @Test
    void returnsImmutableList() {
        var messages = List.of(ChatMessage.user("a"));
        var result = strategy.select(messages, 5);
        try {
            result.add(ChatMessage.user("b"));
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }
}
