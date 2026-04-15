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
package org.atmosphere.ai.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CoalescedCacheEventTest {

    @Test
    void creationWithAllFields() {
        var event = new CoalescedCacheEvent("sess-1", "bc-1", 42, "complete", 1500L);

        assertEquals("sess-1", event.sessionId());
        assertEquals("bc-1", event.broadcasterId());
        assertEquals(42, event.totalStreamingTexts());
        assertEquals("complete", event.status());
        assertEquals(1500L, event.elapsedMs());
    }

    @Test
    void nullStringFieldsAreAllowed() {
        var event = new CoalescedCacheEvent(null, null, 0, null, 0L);

        assertNull(event.sessionId());
        assertNull(event.broadcasterId());
        assertNull(event.status());
    }

    @Test
    void zeroNumericValues() {
        var event = new CoalescedCacheEvent("s", "b", 0, "complete", 0L);

        assertEquals(0, event.totalStreamingTexts());
        assertEquals(0L, event.elapsedMs());
    }

    @Test
    void errorStatus() {
        var event = new CoalescedCacheEvent("s", "b", 5, "error", 200L);
        assertEquals("error", event.status());
    }

    @Test
    void recordEquality() {
        var a = new CoalescedCacheEvent("s1", "b1", 10, "complete", 500L);
        var b = new CoalescedCacheEvent("s1", "b1", 10, "complete", 500L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
