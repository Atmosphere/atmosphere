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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FanOutStrategyTest {

    @Test
    void allResponsesImplementsFanOutStrategy() {
        var strategy = new FanOutStrategy.AllResponses();
        assertInstanceOf(FanOutStrategy.class, strategy);
    }

    @Test
    void firstCompleteImplementsFanOutStrategy() {
        var strategy = new FanOutStrategy.FirstComplete();
        assertInstanceOf(FanOutStrategy.class, strategy);
    }

    @Test
    void fastestStreamingTextsImplementsFanOutStrategy() {
        var strategy = new FanOutStrategy.FastestStreamingTexts(5);
        assertInstanceOf(FanOutStrategy.class, strategy);
    }

    @Test
    void fastestStreamingTextsRetainsThreshold() {
        var strategy = new FanOutStrategy.FastestStreamingTexts(10);
        assertEquals(10, strategy.streamingTextThreshold());
    }

    @Test
    void allResponsesRecordEquality() {
        var a = new FanOutStrategy.AllResponses();
        var b = new FanOutStrategy.AllResponses();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void firstCompleteRecordEquality() {
        var a = new FanOutStrategy.FirstComplete();
        var b = new FanOutStrategy.FirstComplete();
        assertEquals(a, b);
    }

    @Test
    void fastestStreamingTextsRecordEquality() {
        var a = new FanOutStrategy.FastestStreamingTexts(3);
        var b = new FanOutStrategy.FastestStreamingTexts(3);
        var c = new FanOutStrategy.FastestStreamingTexts(7);
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    void differentVariantsAreNotEqual() {
        FanOutStrategy all = new FanOutStrategy.AllResponses();
        FanOutStrategy first = new FanOutStrategy.FirstComplete();
        FanOutStrategy fastest = new FanOutStrategy.FastestStreamingTexts(5);
        assertNotEquals(all, first);
        assertNotEquals(first, fastest);
        assertNotEquals(all, fastest);
    }
}
