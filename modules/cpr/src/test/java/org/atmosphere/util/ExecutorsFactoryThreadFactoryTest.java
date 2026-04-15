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
package org.atmosphere.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorsFactoryThreadFactoryTest {

    @Test
    void sharedThreadNameUsesPrefix() {
        var factory = new ExecutorsFactory.AtmosphereThreadFactory(true, "Custom-");
        var thread = factory.newThread(() -> {});
        assertTrue(thread.getName().startsWith("Atmosphere-Shared-"),
                "Expected Atmosphere-Shared- prefix, got: " + thread.getName());
    }

    @Test
    void nonSharedThreadNameUsesCustomName() {
        var factory = new ExecutorsFactory.AtmosphereThreadFactory(false, "MyPool-");
        var thread = factory.newThread(() -> {});
        assertTrue(thread.getName().startsWith("MyPool-"),
                "Expected MyPool- prefix, got: " + thread.getName());
    }

    @Test
    void threadIsDaemon() {
        var factory = new ExecutorsFactory.AtmosphereThreadFactory(true, "Test-");
        var thread = factory.newThread(() -> {});
        assertTrue(thread.isDaemon());
    }

    @Test
    void threadCountIncrements() {
        var factory = new ExecutorsFactory.AtmosphereThreadFactory(false, "Seq-");
        var t0 = factory.newThread(() -> {});
        var t1 = factory.newThread(() -> {});
        var t2 = factory.newThread(() -> {});
        assertEquals("Seq-0", t0.getName());
        assertEquals("Seq-1", t1.getName());
        assertEquals("Seq-2", t2.getName());
    }

    @Test
    void sharedCountIncrements() {
        var factory = new ExecutorsFactory.AtmosphereThreadFactory(true, "ignored");
        var t0 = factory.newThread(() -> {});
        var t1 = factory.newThread(() -> {});
        assertTrue(t0.getName().endsWith("0"));
        assertTrue(t1.getName().endsWith("1"));
    }

    @Test
    void threadExecutesRunnable() throws InterruptedException {
        var factory = new ExecutorsFactory.AtmosphereThreadFactory(false, "Run-");
        var ran = new boolean[]{false};
        var thread = factory.newThread(() -> ran[0] = true);
        thread.start();
        thread.join(1000);
        assertTrue(ran[0]);
    }
}
