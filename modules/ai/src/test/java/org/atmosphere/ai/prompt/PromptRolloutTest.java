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
package org.atmosphere.ai.prompt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PromptRolloutTest {

    private static final String ROLLOUT_KEY = PromptRollout.ROLLOUT_PROPERTY_PREFIX + "greeter";

    @AfterEach
    public void tearDown() {
        System.clearProperty(ROLLOUT_KEY);
    }

    @Test
    public void noConfigurationMeansNoSelection() {
        assertTrue(PromptRollout.selectConfigured("greeter", "user-1").isEmpty());
    }

    @Test
    public void sameUnitAlwaysGetsTheSameVersion() {
        System.setProperty(ROLLOUT_KEY, "v1:90,v2:10");
        var first = PromptRollout.selectConfigured("greeter", "user-42").orElseThrow();
        for (var i = 0; i < 100; i++) {
            assertEquals(first, PromptRollout.selectConfigured("greeter", "user-42").orElseThrow());
        }
    }

    @Test
    public void weightsAreRoughlyHonoredOverManyUnits() {
        System.setProperty(ROLLOUT_KEY, "v1:90,v2:10");
        var v2Count = 0;
        var total = 10_000;
        for (var i = 0; i < total; i++) {
            if ("v2".equals(PromptRollout.selectConfigured("greeter", "user-" + i).orElseThrow())) {
                v2Count++;
            }
        }
        // 10% expected. SHA-256 over fixed inputs is fully deterministic, so
        // this is a stable assertion, not a flaky statistical one; the wide
        // band just avoids over-pinning the exact hash distribution.
        assertTrue(v2Count > 500 && v2Count < 2000,
                "expected roughly 10% v2, got " + v2Count + "/" + total);
    }

    @Test
    public void selectionIsIndependentPerPromptName() {
        System.setProperty(ROLLOUT_KEY, "v1:50,v2:50");
        System.setProperty(PromptRollout.ROLLOUT_PROPERTY_PREFIX + "other", "v1:50,v2:50");
        try {
            var differs = false;
            for (var i = 0; i < 64 && !differs; i++) {
                var unit = "user-" + i;
                differs = !PromptRollout.selectConfigured("greeter", unit).orElseThrow()
                        .equals(PromptRollout.selectConfigured("other", unit).orElseThrow());
            }
            assertTrue(differs, "the prompt name must be part of the hash: two prompts with "
                    + "identical weights should not split every unit identically");
        } finally {
            System.clearProperty(PromptRollout.ROLLOUT_PROPERTY_PREFIX + "other");
        }
    }

    @Test
    public void malformedSpecsFailClosed() {
        for (var spec : new String[]{"v1:90,v2", "v1:0", "v1:-5", "v1:x", "x1:50", "", " ,",
                "v1:50,v1:50"}) {
            System.setProperty(ROLLOUT_KEY, spec);
            if (spec.isBlank()) {
                // Blank means "not configured", not "malformed".
                assertTrue(PromptRollout.selectConfigured("greeter", "u").isEmpty(), spec);
                continue;
            }
            assertThrows(IllegalStateException.class,
                    () -> PromptRollout.selectConfigured("greeter", "u"), "spec: " + spec);
        }
    }

    @Test
    public void singleEntryAlwaysWins() {
        System.setProperty(ROLLOUT_KEY, "v3:1");
        assertEquals("v3", PromptRollout.selectConfigured("greeter", "anyone").orElseThrow());
    }
}
