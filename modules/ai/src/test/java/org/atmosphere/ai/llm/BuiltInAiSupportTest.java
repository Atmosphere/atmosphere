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
package org.atmosphere.ai.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BuiltInAiSupport}.
 */
public class BuiltInAiSupportTest {

    @Test
    public void testIsAlwaysAvailable() {
        var support = new BuiltInAiSupport();
        assertTrue(support.isAvailable());
    }

    @Test
    public void testPriorityIsZero() {
        var support = new BuiltInAiSupport();
        assertEquals(0, support.priority());
    }

    @Test
    public void testNameIsBuiltIn() {
        var support = new BuiltInAiSupport();
        assertEquals("built-in", support.name());
    }
}
