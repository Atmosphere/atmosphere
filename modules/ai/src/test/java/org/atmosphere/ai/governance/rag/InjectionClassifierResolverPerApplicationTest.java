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
package org.atmosphere.ai.governance.rag;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.atmosphere.ai.decision.DecisionModelResolver;
import org.atmosphere.ai.decision.TestDecisionModels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two applications with different embedding and decision models each get their
 * own injection classifier per tier, over their own embedding runtime and
 * decision model, and an application's are dropped when it closes. The
 * resolver used to keep one classifier per tier for the JVM.
 */
class InjectionClassifierResolverPerApplicationTest {

    private AiEnvironment first;
    private AiEnvironment second;

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
        TestDecisionModels.reset();
        InjectionClassifierResolver.reset();
        first = AiEnvironment.create(key -> null);
        second = AiEnvironment.create(key -> null);
        first.configure("remote", "chat-first", "key-first", "http://127.0.0.1:9/first/v1");
        second.configure("remote", "chat-second", "key-second", "http://127.0.0.1:9/second/v1");
    }

    @AfterEach
    void tearDown() {
        second.close();
        first.close();
        InjectionClassifierResolver.reset();
        TestDecisionModels.reset();
        EmbeddingRuntimeResolver.resetCache();
        AgentRuntimeResolver.reset();
        AiConfig.resetHostConfig();
    }

    @Test
    void theEmbeddingTierRunsOnEachApplicationsOwnRuntime() throws Exception {
        var inFirst = InjectionClassifierResolver.resolve(first, InjectionClassifier.Tier.EMBEDDING_SIMILARITY);
        var inSecond = InjectionClassifierResolver.resolve(second, InjectionClassifier.Tier.EMBEDDING_SIMILARITY);

        assertEquals(InjectionClassifier.Tier.EMBEDDING_SIMILARITY, inFirst.tier());
        assertNotSame(inFirst, inSecond, "the second application must not share the first's classifier");
        assertSame(inFirst, InjectionClassifierResolver.resolve(first,
                InjectionClassifier.Tier.EMBEDDING_SIMILARITY), "an application keeps its classifier");
        var embedding = assertInstanceOf(EmbeddingInjectionClassifier.class, layer(inFirst, 1));
        assertSame(first.embeddings().resolve().orElseThrow(), field(embedding, "runtime"),
                "the embedding tier must use the application's runtime");
    }

    @Test
    void theLlmTierAsksEachApplicationsOwnDecisionModel() throws Exception {
        var inFirst = InjectionClassifierResolver.resolve(first, InjectionClassifier.Tier.LLM_CLASSIFIER);
        var inSecond = InjectionClassifierResolver.resolve(second, InjectionClassifier.Tier.LLM_CLASSIFIER);

        assertEquals(InjectionClassifier.Tier.LLM_CLASSIFIER, inFirst.tier());
        assertNotSame(inFirst, inSecond);
        var llm = assertInstanceOf(LlmClassifierInjectionClassifier.class, layer(inFirst, 1));
        assertSame(DecisionModelResolver.resolve(first).orElseThrow(), field(llm, "model"),
                "the LLM tier must ask the application's own decision model");
        assertNotSame(field(llm, "model"), field(layer(inSecond, 1), "model"));
    }

    @Test
    void anApplicationsClassifiersAreDroppedWhenItCloses() {
        var before = InjectionClassifierResolver.resolve(first, InjectionClassifier.Tier.RULE_BASED);
        assertTrue(InjectionClassifierResolver.caches(first));

        first.close();

        assertFalse(InjectionClassifierResolver.caches(first), "a closed application's classifiers are released");
        var after = InjectionClassifierResolver.resolve(first, InjectionClassifier.Tier.EMBEDDING_SIMILARITY);
        assertEquals(InjectionClassifier.Tier.RULE_BASED, after.tier(),
                "a closed application loads no runtime, so the tier downgrades");
        assertNotSame(before, InjectionClassifierResolver.resolve(first, InjectionClassifier.Tier.RULE_BASED));
        assertFalse(InjectionClassifierResolver.caches(first));
    }

    @Test
    void aSafetyProviderBuiltForAnApplicationUsesItsClassifier() throws Exception {
        var provider = SafetyContextProvider.wrapping((query, max) -> List.of())
                .tier(InjectionClassifier.Tier.EMBEDDING_SIMILARITY)
                .environment(first)
                .build();

        assertSame(InjectionClassifierResolver.resolve(first, InjectionClassifier.Tier.EMBEDDING_SIMILARITY),
                field(provider, "classifier"));
    }

    private static InjectionClassifier layer(InjectionClassifier composite, int index) throws Exception {
        var layers = (List<?>) field(assertInstanceOf(CompositeInjectionClassifier.class, composite), "layers");
        return (InjectionClassifier) layers.get(index);
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
