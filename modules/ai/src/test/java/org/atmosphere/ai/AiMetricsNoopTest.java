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

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiMetricsNoopTest {

    @Test
    void noopInstanceExists() {
        assertNotNull(AiMetrics.NOOP);
    }

    @Test
    void recordStreamingTextUsageNoOp() {
        assertDoesNotThrow(() -> AiMetrics.NOOP.recordStreamingTextUsage("gpt-4", 100, 50));
    }

    @Test
    void recordLatencyNoOp() {
        assertDoesNotThrow(() -> AiMetrics.NOOP.recordLatency("gpt-4",
                Duration.ofMillis(200), Duration.ofSeconds(1)));
    }

    @Test
    void recordCostNoOp() {
        assertDoesNotThrow(() -> AiMetrics.NOOP.recordCost("gpt-4", new BigDecimal("0.003")));
    }

    @Test
    void recordToolCallNoOp() {
        assertDoesNotThrow(() -> AiMetrics.NOOP.recordToolCall("gpt-4", "search",
                Duration.ofMillis(500), true));
    }

    @Test
    void recordErrorNoOp() {
        assertDoesNotThrow(() -> AiMetrics.NOOP.recordError("gpt-4", "rate_limit"));
    }

    @Test
    void sessionStartedDefaultNoOp() {
        assertDoesNotThrow(() -> AiMetrics.NOOP.sessionStarted("gpt-4"));
    }

    @Test
    void sessionEndedDefaultNoOp() {
        assertDoesNotThrow(() -> AiMetrics.NOOP.sessionEnded("gpt-4"));
    }
}
