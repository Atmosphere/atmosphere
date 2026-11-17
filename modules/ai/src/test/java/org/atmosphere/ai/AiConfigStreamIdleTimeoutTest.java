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

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stream idle timeout is read from the application's own environment, in
 * the duration spellings an operator writes; {@code 0} turns it off, and a
 * malformed or negative value falls back to the default rather than failing a
 * turn (Correctness Invariant #4).
 */
class AiConfigStreamIdleTimeoutTest {

    @Test
    void unsetIsTheDefault() {
        try (var environment = AiEnvironment.create(key -> null)) {
            assertEquals(AiConfig.DEFAULT_STREAM_IDLE_TIMEOUT, environment.resolveStreamIdleTimeout());
        }
    }

    @Test
    void acceptsUnitsIsoBareSecondsAndZero() {
        assertTimeout("500ms", Duration.ofMillis(500));
        assertTimeout("90s", Duration.ofSeconds(90));
        assertTimeout("5m", Duration.ofMinutes(5));
        assertTimeout("PT2M", Duration.ofMinutes(2));
        assertTimeout("45", Duration.ofSeconds(45));
        assertTimeout("0", Duration.ZERO);
    }

    @Test
    void malformedOrNegativeFallsBackToTheDefault() {
        assertTimeout("soon", AiConfig.DEFAULT_STREAM_IDLE_TIMEOUT);
        assertTimeout("-5s", AiConfig.DEFAULT_STREAM_IDLE_TIMEOUT);
    }

    @Test
    void eachApplicationReadsItsOwnValue() {
        try (var a = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "10s")::get);
             var b = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, "20s")::get)) {
            assertEquals(Duration.ofSeconds(10), a.resolveStreamIdleTimeout());
            assertEquals(Duration.ofSeconds(20), b.resolveStreamIdleTimeout());
        }
    }

    private static void assertTimeout(String raw, Duration expected) {
        try (var environment = AiEnvironment.create(Map.of(AiConfig.STREAM_IDLE_TIMEOUT_PROPERTY, raw)::get)) {
            assertEquals(expected, environment.resolveStreamIdleTimeout(), raw);
        }
    }
}
