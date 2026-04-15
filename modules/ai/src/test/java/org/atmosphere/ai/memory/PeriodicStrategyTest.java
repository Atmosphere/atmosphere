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
package org.atmosphere.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeriodicStrategyTest {

    @Test
    void constructorRejectsZeroInterval() {
        assertThrows(IllegalArgumentException.class, () -> new PeriodicStrategy(0));
    }

    @Test
    void constructorRejectsNegativeInterval() {
        assertThrows(IllegalArgumentException.class, () -> new PeriodicStrategy(-1));
    }

    @Test
    void shouldExtractReturnsFalseAtMessageCountZero() {
        var strategy = new PeriodicStrategy(3);
        assertFalse(strategy.shouldExtract("conv", "msg", 0));
    }

    @Test
    void shouldExtractReturnsTrueAtIntervalBoundary() {
        var strategy = new PeriodicStrategy(5);
        assertTrue(strategy.shouldExtract("conv", "msg", 5));
        assertTrue(strategy.shouldExtract("conv", "msg", 10));
        assertTrue(strategy.shouldExtract("conv", "msg", 15));
    }

    @Test
    void shouldExtractReturnsFalseBetweenIntervals() {
        var strategy = new PeriodicStrategy(5);
        assertFalse(strategy.shouldExtract("conv", "msg", 1));
        assertFalse(strategy.shouldExtract("conv", "msg", 2));
        assertFalse(strategy.shouldExtract("conv", "msg", 3));
        assertFalse(strategy.shouldExtract("conv", "msg", 4));
    }

    @Test
    void shouldExtractWithIntervalOfOne() {
        var strategy = new PeriodicStrategy(1);
        assertFalse(strategy.shouldExtract("conv", "msg", 0));
        assertTrue(strategy.shouldExtract("conv", "msg", 1));
        assertTrue(strategy.shouldExtract("conv", "msg", 2));
        assertTrue(strategy.shouldExtract("conv", "msg", 100));
    }

    @Test
    void shouldExtractIgnoresConversationIdAndMessage() {
        var strategy = new PeriodicStrategy(3);
        assertTrue(strategy.shouldExtract(null, null, 3));
        assertTrue(strategy.shouldExtract("", "", 6));
    }
}
