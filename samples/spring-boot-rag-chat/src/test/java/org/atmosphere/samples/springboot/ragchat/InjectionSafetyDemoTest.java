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
package org.atmosphere.samples.springboot.ragchat;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.governance.rag.RuleBasedInjectionClassifier;
import org.atmosphere.ai.governance.rag.SafetyContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the rag-chat injection-safety demo. The headline
 * interaction — ask "how do I secure Atmosphere?" and watch the framework drop a
 * poisoned community document — only works if the poisoned document actually
 * ranks into the retrieved top-K (so the screen sees it) AND the default-on
 * {@link SafetyContextProvider} then drops it.
 *
 * <p>The word-overlap retriever is exact-match (no stemming): if the poisoned
 * document's wording stops matching the headline query, it sinks below the
 * {@code maxResults} cut, the screen never sees it, and the demo becomes hollow
 * (the doc is absent for the wrong reason — ranking, not screening). This test
 * pins both halves so that failure mode breaks the build instead of shipping a
 * demo that only looks safe.</p>
 */
class InjectionSafetyDemoTest {

    private static final String HEADLINE = "how do I secure Atmosphere?";
    private static final String POISON_SOURCE = "docs/community-security-tips.md";

    private static final List<String> CLEAN_CORPUS = List.of(
            "docs/atmosphere-overview.md",
            "docs/atmosphere-transports.md",
            "docs/atmosphere-ai-module.md",
            "docs/atmosphere-getting-started.md",
            "docs/atmosphere-agents.md");

    @BeforeEach
    void loadCleanCorpus() throws IOException {
        // Mirror VectorStoreConfig so the poisoned doc must out-rank the real
        // trusted corpus, not an empty one — that realistic tie is exactly the
        // condition under which a weak match would lose the maxResults cut.
        if (!KnowledgeBase.instance().documents().isEmpty()) {
            return;
        }
        var docs = new ArrayList<ContextProvider.Document>();
        for (var source : CLEAN_CORPUS) {
            try (var in = getClass().getClassLoader().getResourceAsStream(source)) {
                assertNotNull(in, "test corpus resource missing: " + source);
                var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                docs.add(new ContextProvider.Document(content, source, 1.0));
            }
        }
        KnowledgeBase.instance().addDocuments(docs);
    }

    @Test
    void poisonedDocIsRetrievedThenDroppedByTheDefaultScreen() {
        var provider = new KnowledgeBaseContextProvider();

        // 1. Unscreened retrieval must surface the poisoned doc in the top-K, or
        //    the screen would never see it (the demo would be hollow).
        var unscreened = provider.retrieve(HEADLINE, 5);
        assertTrue(unscreened.stream().anyMatch(d -> POISON_SOURCE.equals(d.source())),
                "poisoned doc must rank into the retrieved set for the headline query, was: "
                        + unscreened.stream().map(ContextProvider.Document::source).toList());

        // 2. Screened exactly as the framework wraps it (default RULE_BASED / DROP):
        //    the poisoned doc is gone, the trusted docs survive.
        var screened = SafetyContextProvider.wrapping(provider)
                .classifier(new RuleBasedInjectionClassifier())
                .onBreach(SafetyContextProvider.Breach.DROP)
                .build()
                .retrieve(HEADLINE, 5);
        assertFalse(screened.stream().anyMatch(d -> POISON_SOURCE.equals(d.source())),
                "injection-safety screen must drop the poisoned doc from retrieval");
        assertTrue(screened.stream().anyMatch(d -> d.source().startsWith("docs/atmosphere-")),
                "trusted docs must survive the screen: "
                        + screened.stream().map(ContextProvider.Document::source).toList());
    }
}
