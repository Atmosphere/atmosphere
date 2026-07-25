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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmReranker} — single batched scoring call, strict index parsing,
 * and fail-open (original retriever order, trimmed to top-k) on every
 * error / timeout / malformed-output path.
 */
public class LlmRerankerTest {

    private static List<ContextProvider.Document> docs(int count) {
        var result = new ArrayList<ContextProvider.Document>(count);
        for (var i = 0; i < count; i++) {
            result.add(new ContextProvider.Document(
                    "content-" + i, "doc-" + i + ".md", 1.0 - i * 0.05));
        }
        return List.copyOf(result);
    }

    private static List<String> sources(List<ContextProvider.Document> documents) {
        return documents.stream().map(ContextProvider.Document::source).toList();
    }

    @Test
    public void reordersPerModelRanking() {
        var runtime = new ReplyingRuntime("[2,0,1]");
        var reranker = new LlmReranker(() -> runtime, 5_000);

        var out = reranker.rerank("query", docs(3), 3);

        assertEquals(List.of("doc-2.md", "doc-0.md", "doc-1.md"), sources(out));
        assertEquals(1, runtime.requests.size(), "reranking must be ONE batched call");
    }

    @Test
    public void trimsRankingToTopK() {
        var reranker = new LlmReranker(() -> new ReplyingRuntime("[4,3,2,1,0]"), 5_000);

        var out = reranker.rerank("query", docs(5), 2);

        assertEquals(List.of("doc-4.md", "doc-3.md"), sources(out));
    }

    @Test
    public void subsetRankingBackfillsFromRetrieverOrder() {
        // Model names only doc 2 — the rest back-fills in retriever order so
        // the reranked list is never smaller than the un-reranked top-k.
        var reranker = new LlmReranker(() -> new ReplyingRuntime("[2]"), 5_000);

        var out = reranker.rerank("query", docs(4), 3);

        assertEquals(List.of("doc-2.md", "doc-0.md", "doc-1.md"), sources(out));
    }

    @Test
    public void emptyRankingBackfillsFromRetrieverOrder() {
        var reranker = new LlmReranker(() -> new ReplyingRuntime("[]"), 5_000);

        var out = reranker.rerank("query", docs(4), 2);

        assertEquals(List.of("doc-0.md", "doc-1.md"), sources(out));
    }

    @Test
    public void fencedAnswerIsAccepted() {
        var reranker = new LlmReranker(() -> new ReplyingRuntime("```json\n[1,0]\n```"), 5_000);

        var out = reranker.rerank("query", docs(2), 2);

        assertEquals(List.of("doc-1.md", "doc-0.md"), sources(out));
    }

    @Test
    public void proseWrappedArrayIsAccepted() {
        var reranker = new LlmReranker(
                () -> new ReplyingRuntime("The ranking is [1,0] based on relevance."), 5_000);

        var out = reranker.rerank("query", docs(2), 2);

        assertEquals(List.of("doc-1.md", "doc-0.md"), sources(out));
    }

    @Test
    public void proseWithoutArrayFailsOpen() {
        var reranker = new LlmReranker(
                () -> new ReplyingRuntime("Document two is clearly the best match."), 5_000);

        var out = reranker.rerank("query", docs(3), 2);

        assertEquals(List.of("doc-0.md", "doc-1.md"), sources(out));
    }

    @Test
    public void outOfRangeIndexFailsOpen() {
        var reranker = new LlmReranker(() -> new ReplyingRuntime("[9,0]"), 5_000);

        assertEquals(List.of("doc-0.md", "doc-1.md"),
                sources(reranker.rerank("query", docs(3), 2)));
    }

    @Test
    public void duplicateIndexFailsOpen() {
        var reranker = new LlmReranker(() -> new ReplyingRuntime("[0,0]"), 5_000);

        assertEquals(List.of("doc-0.md", "doc-1.md"),
                sources(reranker.rerank("query", docs(3), 2)));
    }

    @Test
    public void nonIntegerIndexFailsOpen() {
        var reranker = new LlmReranker(() -> new ReplyingRuntime("[first, second]"), 5_000);

        assertEquals(List.of("doc-0.md", "doc-1.md"),
                sources(reranker.rerank("query", docs(3), 2)));
    }

    @Test
    public void runtimeErrorFailsOpen() {
        var failing = new ReplyingRuntime(null) {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.error(new RuntimeException("provider unavailable"));
            }
        };
        var reranker = new LlmReranker(() -> failing, 5_000);

        assertEquals(List.of("doc-0.md", "doc-1.md"),
                sources(reranker.rerank("query", docs(3), 2)));
    }

    @Test
    public void runtimeThrowFailsOpen() {
        var throwing = new ReplyingRuntime(null) {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                throw new IllegalStateException("no runtime configured");
            }
        };
        var reranker = new LlmReranker(() -> throwing, 5_000);

        assertEquals(List.of("doc-0.md", "doc-1.md"),
                sources(reranker.rerank("query", docs(3), 2)));
    }

    @Test
    public void timeoutFailsOpenWithinBound() {
        var silent = new ReplyingRuntime(null) {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                // never completes the session — forces the bounded await to expire
            }
        };
        var reranker = new LlmReranker(() -> silent, 100);

        var start = System.nanoTime();
        var out = reranker.rerank("query", docs(3), 2);
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(List.of("doc-0.md", "doc-1.md"), sources(out));
        assertTrue(elapsedMs < 5_000, "fail-open must honor the bounded timeout, took " + elapsedMs + "ms");
    }

    @Test
    public void interruptFailsOpenAndPreservesFlag() {
        var silent = new ReplyingRuntime(null) {
            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                // never completes; the pre-set interrupt aborts the await
            }
        };
        var reranker = new LlmReranker(() -> silent, 10_000);

        Thread.currentThread().interrupt();
        var out = reranker.rerank("query", docs(2), 2);

        assertTrue(Thread.interrupted(), "interrupt flag must be restored (and cleared here for the runner)");
        assertEquals(List.of("doc-0.md", "doc-1.md"), sources(out));
    }

    @Test
    public void singleDocumentSkipsModelCall() {
        var runtime = new ReplyingRuntime("[0]");
        var reranker = new LlmReranker(() -> runtime, 5_000);

        var single = docs(1);
        assertEquals(single, reranker.rerank("query", single, 3));
        assertEquals(0, runtime.requests.size(), "one document has nothing to reorder");
    }

    @Test
    public void emptyInputReturnsEmptyWithoutModelCall() {
        var runtime = new ReplyingRuntime("[0]");
        var reranker = new LlmReranker(() -> runtime, 5_000);

        assertEquals(List.of(), reranker.rerank("query", List.of(), 3));
        assertEquals(List.of(), reranker.rerank("query", null, 3));
        assertEquals(0, runtime.requests.size());
    }

    @Test
    public void promptBatchesQueryAndNumberedDocuments() {
        var runtime = new ReplyingRuntime("[0,1,2]");
        var reranker = new LlmReranker(() -> runtime, 5_000);

        reranker.rerank("how do I secure Atmosphere?", docs(3), 3);

        assertEquals(1, runtime.requests.size());
        var prompt = runtime.requests.get(0).message();
        assertTrue(prompt.contains("how do I secure Atmosphere?"));
        assertTrue(prompt.contains("[0] (doc-0.md)"));
        assertTrue(prompt.contains("[1] (doc-1.md)"));
        assertTrue(prompt.contains("[2] (doc-2.md)"));
    }

    /** Runtime whose completion is a fixed reply — one recording per execute. */
    static class ReplyingRuntime implements AgentRuntime {
        final List<AgentExecutionContext> requests = new ArrayList<>();
        private final String reply;

        ReplyingRuntime(String reply) {
            this.reply = reply;
        }

        @Override public String name() { return "replying"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            requests.add(context);
            session.send(reply);
            session.complete();
        }
    }
}
