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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery: a registered model wins by priority, an unavailable or throwing
 * one is skipped, the fallback is a {@link RuntimeDecisionModel} over a real
 * runtime, and the demo runtime alone resolves to nothing — which is never
 * cached, so a model configured later is still found.
 */
class DecisionModelResolverTest {

    @BeforeEach
    void setUp() {
        TestDecisionModels.reset();
        AgentRuntimeResolver.clearExplicitClientBinding();
    }

    @AfterEach
    void tearDown() {
        TestDecisionModels.reset();
        DecisionModelResolverTestAccess.restore();
        AgentRuntimeResolver.clearExplicitClientBinding();
    }

    @Test
    void highestPriorityAvailableRegistrationWins() {
        TestDecisionModels.Lower.available = true;
        TestDecisionModels.Preferred.available = true;
        TestDecisionModels.Throwing.throwing = true;
        assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve().orElseThrow());
    }

    @Test
    void unavailableRegistrationIsSkipped() {
        TestDecisionModels.Lower.available = true;
        assertInstanceOf(TestDecisionModels.Lower.class, DecisionModelResolver.resolve().orElseThrow());
    }

    @Test
    void fallbackIsARuntimeDecisionModelOverARealRuntime() {
        // A bound client makes the demo runtime yield, as a configured model does.
        AgentRuntimeResolver.markExplicitClientBinding();
        var model = DecisionModelResolver.resolve().orElseThrow();
        assertInstanceOf(RuntimeDecisionModel.class, model);
        assertNotEquals("runtime:demo", model.name());
        assertTrue(model.isAvailable());
    }

    @Test
    void demoOnlyResolvesEmptyAndIsNotCached() {
        DecisionModelResolverTestAccess.forceDemoOnly();
        assertTrue(DecisionModelResolver.resolve().isEmpty(),
                "the canned demo runtime cannot answer a decision");
        // A model that appears later (AiConfig installed, a module registered)
        // must be found without a reset: the empty answer was not cached.
        TestDecisionModels.Preferred.available = true;
        assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve().orElseThrow());
    }

    @Test
    void nonEmptyResultIsCachedUntilReset() {
        TestDecisionModels.Lower.available = true;
        var first = DecisionModelResolver.resolve().orElseThrow();
        TestDecisionModels.Preferred.available = true;
        assertSame(first, DecisionModelResolver.resolve().orElseThrow(), "a found model is cached");
        DecisionModelResolver.reset();
        assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve().orElseThrow(),
                "reset() rescans");
        assertEquals("test-preferred", DecisionModelResolver.resolve().orElseThrow().name());
    }
}
