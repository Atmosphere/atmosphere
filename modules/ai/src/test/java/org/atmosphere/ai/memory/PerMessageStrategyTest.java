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

import static org.junit.jupiter.api.Assertions.assertTrue;

class PerMessageStrategyTest {

    private final PerMessageStrategy strategy = new PerMessageStrategy();

    @Test
    void shouldExtractReturnsTrueForZeroMessageCount() {
        assertTrue(strategy.shouldExtract("conv", "msg", 0));
    }

    @Test
    void shouldExtractReturnsTrueForPositiveMessageCount() {
        assertTrue(strategy.shouldExtract("conv", "msg", 1));
        assertTrue(strategy.shouldExtract("conv", "msg", 50));
        assertTrue(strategy.shouldExtract("conv", "msg", 1000));
    }

    @Test
    void shouldExtractReturnsTrueForNullInputs() {
        assertTrue(strategy.shouldExtract(null, null, 0));
    }

    @Test
    void shouldExtractReturnsTrueForEmptyStrings() {
        assertTrue(strategy.shouldExtract("", "", 0));
    }

    @Test
    void shouldExtractReturnsTrueRegardlessOfConversationId() {
        assertTrue(strategy.shouldExtract("conv-1", "hello", 5));
        assertTrue(strategy.shouldExtract("conv-2", "world", 5));
        assertTrue(strategy.shouldExtract("different-id", "test", 5));
    }
}
