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

public class LlmSummarizingCompactionTest {

    private static AgentRuntime streamingRuntime(String reply) {
        return new AgentRuntime() {
            @Override public String name() { return "fake-summarizer"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send(reply);
                session.complete();
            }
        };
    }

    private static AgentRuntime erroringRuntime() {
        return new AgentRuntime() {
            @Override public String name() { return "fake-erroring"; }
            @Override public boolean isAvailable() { return true; }
            @Override public int priority() { return 0; }
            @Override public void configure(AiConfig.LlmSettings settings) { }
            @Override public void execute(AgentExecutionContext context, StreamingSession session) {
                session.error(new IllegalStateException("no model configured"));
            }
        };
    }

    private static final List<ChatMessage> OLD_MESSAGES = List.of(
            ChatMessage.user("hello"),
            ChatMessage.assistant("hi, how can I help?"));

    @Test
    public void usesLlmSummaryWhenRuntimeStreams() {
        var compaction = new LlmSummarizingCompaction(2,
                () -> streamingRuntime("A concise summary."));

        assertEquals("A concise summary.", compaction.summarize(OLD_MESSAGES));
    }

    @Test
    public void failsOpenWhenRuntimeSupplierThrows() {
        // No runtime configured (resolver throwing) must degrade to the local
        // concat-and-truncate summary, never propagate.
        var compaction = new LlmSummarizingCompaction(2, () -> {
            throw new IllegalStateException("no runtime configured");
        });

        var summary = compaction.summarize(OLD_MESSAGES);
        assertTrue(summary.contains("user: hello"),
                "fallback must be the local concat summary, got: " + summary);
    }

    @Test
    public void failsOpenWhenRuntimeErrors() {
        var compaction = new LlmSummarizingCompaction(2,
                LlmSummarizingCompactionTest::erroringRuntime);

        var summary = compaction.summarize(OLD_MESSAGES);
        assertTrue(summary.contains("user: hello"),
                "runtime error must fall back to the local summary, got: " + summary);
    }

    @Test
    public void failsOpenWhenLlmReturnsBlank() {
        var compaction = new LlmSummarizingCompaction(2, () -> streamingRuntime(""));

        var summary = compaction.summarize(OLD_MESSAGES);
        assertTrue(summary.contains("user: hello"),
                "blank LLM output must fall back to the local summary, got: " + summary);
    }

    @Test
    public void compactEmbedsLlmSummaryMessage() {
        var compaction = new LlmSummarizingCompaction(2,
                () -> streamingRuntime("Discussed greetings."));
        var history = List.of(
                ChatMessage.user("m1"), ChatMessage.assistant("m2"),
                ChatMessage.user("m3"), ChatMessage.assistant("m4"),
                ChatMessage.user("m5"), ChatMessage.assistant("m6"));

        var compacted = compaction.compact(history, 4);

        assertTrue(compacted.size() <= 4, "compaction must respect the cap");
        var summaryMessage = compacted.stream()
                .filter(m -> "system".equals(m.role()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("compaction must insert a system summary message"));
        assertTrue(summaryMessage.content().contains("Discussed greetings."),
                "the summary message must carry the LLM output");
        assertEquals("m6", compacted.getLast().content(),
                "recent messages must be preserved verbatim");
    }
}
