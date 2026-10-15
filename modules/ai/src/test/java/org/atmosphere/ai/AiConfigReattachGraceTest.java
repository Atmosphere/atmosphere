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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reattach grace knob accepts the duration spellings an operator writes,
 * and a malformed or negative value falls back to the default rather than
 * failing the disconnect path (Correctness Invariant #4).
 */
class AiConfigReattachGraceTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.REATTACH_GRACE_PROPERTY);
    }

    @Test
    void unsetIsTheDefault() {
        assertEquals(AiConfig.DEFAULT_REATTACH_GRACE, AiConfig.resolveReattachGrace());
    }

    @Test
    void acceptsUnitsIsoAndBareSeconds() {
        assertGrace("500ms", Duration.ofMillis(500));
        assertGrace("30s", Duration.ofSeconds(30));
        assertGrace("2m", Duration.ofMinutes(2));
        assertGrace("1h", Duration.ofHours(1));
        assertGrace("PT45S", Duration.ofSeconds(45));
        assertGrace("45", Duration.ofSeconds(45));
        assertGrace("0", Duration.ZERO);
    }

    @Test
    void malformedOrNegativeFallsBackToTheDefault() {
        assertGrace("soon", AiConfig.DEFAULT_REATTACH_GRACE);
        assertGrace("-5s", AiConfig.DEFAULT_REATTACH_GRACE);
        assertGrace("12x", AiConfig.DEFAULT_REATTACH_GRACE);
    }

    private static void assertGrace(String raw, Duration expected) {
        System.setProperty(AiConfig.REATTACH_GRACE_PROPERTY, raw);
        assertEquals(expected, AiConfig.resolveReattachGrace(), raw);
    }
}
