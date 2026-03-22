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
package org.atmosphere.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClasspathDetector}.
 */
public class ClasspathDetectorTest {

    @AfterEach
    void clearCache() {
        ClasspathDetector.clearCache();
    }

    @Test
    public void testStandardClassPresent() {
        assertTrue(ClasspathDetector.isPresent("java.lang.String"));
    }

    @Test
    public void testNonExistentClass() {
        assertFalse(ClasspathDetector.isPresent("com.fake.NonExistent"));
    }

    @Test
    public void testResultIsCached() {
        // First call
        var result1 = ClasspathDetector.isPresent("java.lang.String");
        // Second call (should use cache)
        var result2 = ClasspathDetector.isPresent("java.lang.String");
        assertEquals(result1, result2);
    }

    @Test
    public void testClearCacheWorks() {
        ClasspathDetector.isPresent("java.lang.String");
        ClasspathDetector.clearCache();
        // Should still work after clear
        assertTrue(ClasspathDetector.isPresent("java.lang.String"));
    }

    @Test
    public void testA2aOnClasspath() {
        // A2A is an optional dependency of this module, so it's on the test classpath
        assertTrue(ClasspathDetector.hasA2a());
    }

    @Test
    public void testMcpOnClasspath() {
        // MCP is an optional dependency of this module
        assertTrue(ClasspathDetector.hasMcp());
    }

    @Test
    public void testAgUiOnClasspath() {
        // AG-UI is an optional dependency of this module
        assertTrue(ClasspathDetector.hasAgUi());
    }

    @Test
    public void testChannelsOnClasspath() {
        // Channels is an optional dependency of this module
        assertTrue(ClasspathDetector.hasChannels());
    }
}
