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
package org.atmosphere.ai.fanout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FanOutResultTest {

    @Test
    void creationWithAllFields() {
        var result = new FanOutResult("gpt-4", "Hello world", 150L, 2000L, 10);

        assertEquals("gpt-4", result.modelId());
        assertEquals("Hello world", result.fullResponse());
        assertEquals(150L, result.timeToFirstStreamingTextMs());
        assertEquals(2000L, result.totalTimeMs());
        assertEquals(10, result.streamingTextCount());
    }

    @Test
    void nullModelIdIsAllowed() {
        var result = new FanOutResult(null, "response", 0L, 0L, 0);
        assertNull(result.modelId());
    }

    @Test
    void nullResponseIsAllowed() {
        var result = new FanOutResult("model", null, 0L, 0L, 0);
        assertNull(result.fullResponse());
    }

    @Test
    void zeroLatencyValues() {
        var result = new FanOutResult("model", "resp", 0L, 0L, 0);

        assertEquals(0L, result.timeToFirstStreamingTextMs());
        assertEquals(0L, result.totalTimeMs());
        assertEquals(0, result.streamingTextCount());
    }

    @Test
    void recordEquality() {
        var a = new FanOutResult("m1", "resp", 100L, 500L, 5);
        var b = new FanOutResult("m1", "resp", 100L, 500L, 5);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
