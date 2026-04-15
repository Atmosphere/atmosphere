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

import org.atmosphere.cpr.BroadcastFilter.BroadcastAction.ACTION;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringFilterAggregatorTest {

    @Test
    void buffersBelowLimit() {
        var agg = new StringFilterAggregator(10);
        var result = agg.filter("b", "hi", "hi");
        assertEquals(ACTION.ABORT, result.action());
    }

    @Test
    void flushesAtLimit() {
        var agg = new StringFilterAggregator(5);
        agg.filter("b", "abc", "abc"); // 3 chars buffered
        var result = agg.filter("b", "de", "de"); // 5 chars total
        assertEquals(ACTION.CONTINUE, result.action());
        assertEquals("abcde", result.message());
    }

    @Test
    void flushesAboveLimit() {
        var agg = new StringFilterAggregator(3);
        var result = agg.filter("b", "abcdef", "abcdef");
        assertEquals(ACTION.CONTINUE, result.action());
        assertEquals("abcdef", result.message());
    }

    @Test
    void nonStringPassesThrough() {
        var agg = new StringFilterAggregator();
        var obj = Integer.valueOf(42);
        var result = agg.filter("b", obj, obj);
        assertEquals(ACTION.CONTINUE, result.action());
        assertEquals(obj, result.message());
    }

    @Test
    void defaultConstructorUses256() {
        var agg = new StringFilterAggregator();
        // 10 chars should buffer
        var result = agg.filter("b", "short", "short");
        assertEquals(ACTION.ABORT, result.action());
    }

    @Test
    void resetsBufferAfterFlush() {
        var agg = new StringFilterAggregator(3);
        agg.filter("b", "abcd", "abcd"); // flush
        var result = agg.filter("b", "x", "x"); // new buffer
        assertEquals(ACTION.ABORT, result.action());
    }
}
