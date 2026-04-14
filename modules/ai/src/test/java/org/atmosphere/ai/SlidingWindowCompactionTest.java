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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlidingWindowCompactionTest {

    private final SlidingWindowCompaction compaction = new SlidingWindowCompaction();

    @Test
    void nameIsSlidingWindow() {
        assertEquals("sliding-window", compaction.name());
    }

    @Test
    void noCompactionNeeded() {
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("hi"));
        var result = compaction.compact(messages, 5);
        assertEquals(2, result.size());
    }

    @Test
    void dropsOldestNonSystemMessages() {
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("old"),
                ChatMessage.user("mid"),
                ChatMessage.user("new"));
        var result = compaction.compact(messages, 3);
        assertEquals(3, result.size());
        assertEquals("system", result.get(0).role());
        assertEquals("mid", result.get(1).content());
        assertEquals("new", result.get(2).content());
    }

    @Test
    void preservesSystemMessages() {
        var messages = List.of(
                ChatMessage.system("sys1"),
                ChatMessage.system("sys2"),
                ChatMessage.user("user1"),
                ChatMessage.user("user2"));
        var result = compaction.compact(messages, 3);
        assertEquals(3, result.size());
        long systemCount = result.stream().filter(m -> "system".equals(m.role())).count();
        assertEquals(2, systemCount);
    }

    @Test
    void handlesAllSystemMessages() {
        var messages = List.of(
                ChatMessage.system("s1"),
                ChatMessage.system("s2"),
                ChatMessage.system("s3"));
        var result = compaction.compact(messages, 2);
        assertEquals(2, result.size());
    }

    @Test
    void compactToOne() {
        var messages = List.of(
                ChatMessage.user("a"),
                ChatMessage.user("b"),
                ChatMessage.user("c"));
        var result = compaction.compact(messages, 1);
        assertEquals(1, result.size());
        assertEquals("c", result.getFirst().content());
    }

    @Test
    void returnsNewList() {
        var messages = new ArrayList<>(List.of(ChatMessage.user("a")));
        var result = compaction.compact(messages, 5);
        messages.add(ChatMessage.user("b"));
        assertEquals(1, result.size());
    }
}
