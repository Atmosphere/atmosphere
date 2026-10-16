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
package org.atmosphere.ai.governance.scope;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.atmosphere.ai.annotation.AgentScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two applications with different embedding and decision models each get their
 * own scope guardrail per tier, built over their own embedding runtime, and an
 * application's guardrails are dropped when it closes. The resolver used to
 * keep one guardrail per tier for the JVM, built from whatever the static API
 * resolved first.
 */
class ScopeGuardrailResolverPerApplicationTest {

    private AiEnvironment first;
    private AiEnvironment second;

    @BeforeEach
    void setUp() {
        AiConfig.resetHostConfig();
        ScopeGuardrailResolver.reset();
        first = AiEnvironment.create(key -> null);
        second = AiEnvironment.create(key -> null);
        first.configure("remote", "chat-first", "key-first", "http://127.0.0.1:9/first/v1");
        second.configure("remote", "chat-second", "key-second", "http://127.0.0.1:9/second/v1");
    }

    @AfterEach
    void tearDown() {
        second.close();
        first.close();
        ScopeGuardrailResolver.reset();
        EmbeddingRuntimeResolver.resetCache();
        AgentRuntimeResolver.reset();
        AiConfig.resetHostConfig();
    }

    @Test
    void eachApplicationGetsItsOwnGuardrailPerTier() {
        for (var tier : new AgentScope.Tier[] {AgentScope.Tier.EMBEDDING_SIMILARITY,
                AgentScope.Tier.SEMANTIC_INTENT, AgentScope.Tier.LLM_CLASSIFIER}) {
            var inFirst = ScopeGuardrailResolver.resolve(first, tier);
            var inSecond = ScopeGuardrailResolver.resolve(second, tier);
            assertNotSame(inFirst, inSecond, tier + ": the second application must not share the first's");
            assertSame(inFirst, ScopeGuardrailResolver.resolve(first, tier),
                    tier + ": an application keeps its own guardrail");
            assertSame(tier, inFirst.tier());
        }
    }

    @Test
    void theEmbeddingTiersRunOnTheApplicationsOwnRuntime() throws Exception {
        var embedding = assertInstanceOf(EmbeddingScopeGuardrail.class,
                ScopeGuardrailResolver.resolve(first, AgentScope.Tier.EMBEDDING_SIMILARITY));
        var semantic = assertInstanceOf(SemanticIntentScopeGuardrail.class,
                ScopeGuardrailResolver.resolve(first, AgentScope.Tier.SEMANTIC_INTENT));
        var own = first.embeddings().resolve().orElseThrow();
        assertSame(own, runtimeOf(embedding), "the embedding tier must use the application's runtime");
        assertSame(own, runtimeOf(semantic), "the semantic tier must use the application's runtime");
        assertNotSame(runtimeOf(embedding), runtimeOf(ScopeGuardrailResolver.resolve(second,
                AgentScope.Tier.EMBEDDING_SIMILARITY)), "each application embeds with its own runtime");
    }

    @Test
    void theNoApplicationFacadeResolvesForTheBoundApplication() {
        var binding = AiEnvironment.bind(first);
        try {
            assertSame(ScopeGuardrailResolver.resolve(first, AgentScope.Tier.EMBEDDING_SIMILARITY),
                    ScopeGuardrailResolver.resolve(AgentScope.Tier.EMBEDDING_SIMILARITY));
        } finally {
            binding.close();
        }
    }

    @Test
    void anApplicationsGuardrailsAreDroppedWhenItCloses() {
        var before = ScopeGuardrailResolver.resolve(first, AgentScope.Tier.EMBEDDING_SIMILARITY);
        var others = ScopeGuardrailResolver.resolve(second, AgentScope.Tier.EMBEDDING_SIMILARITY);
        assertTrue(ScopeGuardrailResolver.caches(first));

        first.close();

        assertFalse(ScopeGuardrailResolver.caches(first), "a closed application's guardrails are released");
        var after = ScopeGuardrailResolver.resolve(first, AgentScope.Tier.EMBEDDING_SIMILARITY);
        assertNotSame(before, after, "a closed application's guardrail is not the cached one");
        assertFalse(ScopeGuardrailResolver.caches(first), "a closed application is never cached again");
        assertSame(others, ScopeGuardrailResolver.resolve(second, AgentScope.Tier.EMBEDDING_SIMILARITY),
                "the other application keeps its guardrail");
    }

    private static Object runtimeOf(ScopeGuardrail guardrail) throws Exception {
        var field = guardrail.getClass().getDeclaredField("runtime");
        field.setAccessible(true);
        return field.get(guardrail);
    }
}
