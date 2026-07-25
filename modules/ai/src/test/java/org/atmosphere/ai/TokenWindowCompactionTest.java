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
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TokenWindowCompactionTest {

    /** Twelve ~10-token (40-char) user/assistant messages, newest last. */
    private static List<ChatMessage> history(int count) {
        var msgs = new ArrayList<ChatMessage>(count);
        for (int i = 0; i < count; i++) {
            var content = "x".repeat(40); // 40 chars / 4 = 10 estimated tokens
            msgs.add(i % 2 == 0 ? ChatMessage.user(content) : ChatMessage.assistant(content));
        }
        return msgs;
    }

    @Test
    public void largerWindowRetainsMoreHistory() {
        var messages = history(12); // ~120 estimated tokens total
        var small = new TokenWindowCompaction(40);      // ~4 messages fit
        var large = new TokenWindowCompaction(1_000);   // all 12 fit

        var smallResult = small.compact(messages, 100);
        var largeResult = large.compact(messages, 100);

        assertTrue(largeResult.size() > smallResult.size(),
                "a larger token window must retain more history (later compaction trigger)");
        assertEquals(12, largeResult.size(), "1000-token budget fits all 12 messages");
    }

    @Test
    public void modelAwareBudgetTriggersLaterThanFlatDefault() {
        // A 200k-window model keeps far more than the historical flat 4000 budget.
        var flat = new TokenWindowCompaction(TokenWindowStrategy.DEFAULT_MAX_TOKENS);
        var claude = new TokenWindowCompaction(ModelWindowCatalog.contextWindow("claude-sonnet-4-6"));
        assertTrue(claude.budgetTokens() > flat.budgetTokens());
        assertEquals(200_000, claude.budgetTokens());
    }

    @Test
    public void systemMessagesAlwaysPreserved() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system-prompt"));
        messages.addAll(history(8));

        // Tiny budget: only the system message plus a couple of recent turns fit.
        var result = new TokenWindowCompaction(30).compact(messages, 100);

        assertTrue(result.contains(messages.getFirst()), "system message must survive compaction");
        assertEquals("system", result.getFirst().role());
    }

    @Test
    public void messageCountCapHonoredEvenWithHugeWindow() {
        var messages = history(10);
        // Huge budget fits everything, but the hard message cap must still apply.
        var result = new TokenWindowCompaction(1_000_000).compact(messages, 3);
        assertEquals(3, result.size());
        // Most-recent messages retained verbatim.
        assertEquals(messages.getLast(), result.getLast());
    }

    @Test
    public void nameIsTokenWindow() {
        assertEquals("token-window", new TokenWindowCompaction(4000).name());
    }
}
