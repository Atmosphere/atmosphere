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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for the {@link ModelRouter.FallbackStrategy} enum.
 */
class ModelRouterTest {

    @Test
    void fallbackStrategyHasExpectedValues() {
        var values = ModelRouter.FallbackStrategy.values();
        assertEquals(4, values.length);
    }

    @Test
    void fallbackStrategyNone() {
        var strategy = ModelRouter.FallbackStrategy.NONE;
        assertNotNull(strategy);
        assertEquals("NONE", strategy.name());
        assertEquals(0, strategy.ordinal());
    }

    @Test
    void fallbackStrategyFailover() {
        var strategy = ModelRouter.FallbackStrategy.FAILOVER;
        assertNotNull(strategy);
        assertEquals("FAILOVER", strategy.name());
        assertEquals(1, strategy.ordinal());
    }

    @Test
    void fallbackStrategyRoundRobin() {
        var strategy = ModelRouter.FallbackStrategy.ROUND_ROBIN;
        assertNotNull(strategy);
        assertEquals("ROUND_ROBIN", strategy.name());
        assertEquals(2, strategy.ordinal());
    }

    @Test
    void fallbackStrategyContentBased() {
        var strategy = ModelRouter.FallbackStrategy.CONTENT_BASED;
        assertNotNull(strategy);
        assertEquals("CONTENT_BASED", strategy.name());
        assertEquals(3, strategy.ordinal());
    }

    @Test
    void fallbackStrategyValueOf() {
        assertEquals(ModelRouter.FallbackStrategy.NONE,
                ModelRouter.FallbackStrategy.valueOf("NONE"));
        assertEquals(ModelRouter.FallbackStrategy.FAILOVER,
                ModelRouter.FallbackStrategy.valueOf("FAILOVER"));
        assertEquals(ModelRouter.FallbackStrategy.ROUND_ROBIN,
                ModelRouter.FallbackStrategy.valueOf("ROUND_ROBIN"));
        assertEquals(ModelRouter.FallbackStrategy.CONTENT_BASED,
                ModelRouter.FallbackStrategy.valueOf("CONTENT_BASED"));
    }
}
