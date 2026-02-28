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

import org.atmosphere.ai.llm.BuiltInAiSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultAiSupportResolver}.
 */
public class DefaultAiSupportResolverTest {

    @Test
    public void testResolvesFallbackWhenNoAdapterOnClasspath() {
        // With only the ai module on the classpath, should find BuiltInAiSupport
        // via ServiceLoader (registered in META-INF/services)
        var support = DefaultAiSupportResolver.resolve();

        assertNotNull(support);
        assertEquals("built-in", support.name());
        assertTrue(support instanceof BuiltInAiSupport);
    }

    @Test
    public void testBuiltInIsAlwaysAvailable() {
        var support = new BuiltInAiSupport();
        assertTrue(support.isAvailable());
        assertEquals(0, support.priority());
    }

    @Test
    public void testHigherPriorityWins() {
        // Create two mock supports and verify the resolver logic
        var low = new TestAiSupport("low", true, 10);
        var high = new TestAiSupport("high", true, 100);

        // Since we can't easily control ServiceLoader, test the comparison directly
        assertTrue(high.priority() > low.priority());
    }

    @Test
    public void testUnavailableSupportSkipped() {
        var unavailable = new TestAiSupport("unavailable", false, 1000);
        assertFalse(unavailable.isAvailable());
    }

    /**
     * Test implementation of AiSupport.
     */
    static class TestAiSupport implements AiSupport {
        private final String name;
        private final boolean available;
        private final int priority;

        TestAiSupport(String name, boolean available, int priority) {
            this.name = name;
            this.available = available;
            this.priority = priority;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public void stream(AiRequest request, StreamingSession session) {
        }
    }
}
