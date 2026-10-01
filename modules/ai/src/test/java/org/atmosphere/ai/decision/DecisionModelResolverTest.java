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
    void theFallbacksConcurrencyIsSizedByTheSystemProperty() {
        AgentRuntimeResolver.markExplicitClientBinding();
        try {
            System.setProperty(DecisionModelResolver.MAX_CONCURRENCY_PROPERTY, "48");
            DecisionModelResolver.reset();
            var sized = assertInstanceOf(RuntimeDecisionModel.class, DecisionModelResolver.resolve().orElseThrow());
            assertEquals(48, sized.maxConcurrency());

            for (var invalid : new String[] {"0", "-3", "many", " "}) {
                System.setProperty(DecisionModelResolver.MAX_CONCURRENCY_PROPERTY, invalid);
                DecisionModelResolver.reset();
                var fallback = assertInstanceOf(RuntimeDecisionModel.class,
                        DecisionModelResolver.resolve().orElseThrow());
                assertEquals(RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY, fallback.maxConcurrency(),
                        "'" + invalid + "' falls back to the default");
            }
        } finally {
            System.clearProperty(DecisionModelResolver.MAX_CONCURRENCY_PROPERTY);
            DecisionModelResolver.reset();
        }
        assertEquals(RuntimeDecisionModel.DEFAULT_MAX_CONCURRENCY, assertInstanceOf(RuntimeDecisionModel.class,
                DecisionModelResolver.resolve().orElseThrow()).maxConcurrency(), "unset means the default");
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
    void unselectedCloseableRegistrationsAreClosedAndTheSelectedOneIsNot() {
        // Unavailable: instantiated by the scan, not selected, closed.
        TestDecisionModels.Lower.available = true;
        DecisionModelResolver.resolve().orElseThrow();
        assertEquals(1, TestDecisionModels.Closeable.CLOSED.get());

        // Available but outranked by Preferred: closed too.
        TestDecisionModels.Closeable.available = true;
        TestDecisionModels.Preferred.available = true;
        DecisionModelResolver.reset();
        assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve().orElseThrow());
        assertEquals(2, TestDecisionModels.Closeable.CLOSED.get());

        // Selected: handed out open.
        TestDecisionModels.Preferred.available = false;
        DecisionModelResolver.reset();
        assertInstanceOf(TestDecisionModels.Closeable.class, DecisionModelResolver.resolve().orElseThrow());
        assertEquals(2, TestDecisionModels.Closeable.CLOSED.get());
    }

    @Test
    void aSelectionDisplacedByAHigherPriorityRegistrationIsClosed() {
        // Displaced is scanned before Preferred: it is selected, then displaced.
        TestDecisionModels.Displaced.available = true;
        TestDecisionModels.Preferred.available = true;
        assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve().orElseThrow());
        assertEquals(1, TestDecisionModels.Displaced.CLOSED.get(), "the displaced selection is closed");

        // Alone, it is selected and handed out open.
        TestDecisionModels.Preferred.available = false;
        DecisionModelResolver.reset();
        assertInstanceOf(TestDecisionModels.Displaced.class, DecisionModelResolver.resolve().orElseThrow());
        assertEquals(1, TestDecisionModels.Displaced.CLOSED.get());
    }

    @Test
    void resetClosesTheCachedRegistrationOnce() {
        TestDecisionModels.Closeable.available = true;
        assertInstanceOf(TestDecisionModels.Closeable.class, DecisionModelResolver.resolve().orElseThrow());
        assertEquals(0, TestDecisionModels.Closeable.CLOSED.get());

        DecisionModelResolver.reset();
        assertEquals(1, TestDecisionModels.Closeable.CLOSED.get(), "reset() closes what the resolver created");
        DecisionModelResolver.reset();
        assertEquals(1, TestDecisionModels.Closeable.CLOSED.get(), "nothing cached, nothing closed");
    }

    @Test
    void aFallbackChosenWhileARegistrationWasDownYieldsToItOnRecheck() {
        AgentRuntimeResolver.markExplicitClientBinding();
        var fallback = assertInstanceOf(RuntimeDecisionModel.class, DecisionModelResolver.resolve().orElseThrow());

        // The registration comes up; within the recheck interval the fallback stays.
        TestDecisionModels.Preferred.available = true;
        assertSame(fallback, DecisionModelResolver.resolve().orElseThrow());

        DecisionModelResolverTestAccess.expireFallbackRecheck();
        assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve().orElseThrow(),
                "the recheck selects the registration that came up");

        // A selected registration is not rechecked: it stays until reset().
        TestDecisionModels.Lower.available = true;
        DecisionModelResolverTestAccess.expireFallbackRecheck();
        assertInstanceOf(TestDecisionModels.Preferred.class, DecisionModelResolver.resolve().orElseThrow());
    }

    @Test
    void aRecheckThatStillEndsAtTheFallbackKeepsTheSameInstance() {
        AgentRuntimeResolver.markExplicitClientBinding();
        var fallback = DecisionModelResolver.resolve().orElseThrow();
        DecisionModelResolverTestAccess.expireFallbackRecheck();
        assertSame(fallback, DecisionModelResolver.resolve().orElseThrow(),
                "one shared fallback, so its concurrency bound stays shared");
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
