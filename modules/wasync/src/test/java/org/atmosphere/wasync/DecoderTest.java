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
package org.atmosphere.wasync;

import org.atmosphere.wasync.decoder.PaddingAndHeartbeatDecoder;
import org.atmosphere.wasync.decoder.TrackMessageSizeDecoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the decoder pipeline.
 */
class DecoderTest {

    @Test
    void paddingDecoderStripsWhitespace() {
        var decoder = new PaddingAndHeartbeatDecoder();
        assertEquals("hello", decoder.decode(Event.MESSAGE, "   hello   "));
    }

    @Test
    void paddingDecoderFiltersHeartbeat() {
        var decoder = new PaddingAndHeartbeatDecoder();
        assertNull(decoder.decode(Event.MESSAGE, "  X  "));
    }

    @Test
    void paddingDecoderFiltersEmptyMessages() {
        var decoder = new PaddingAndHeartbeatDecoder();
        assertNull(decoder.decode(Event.MESSAGE, "    "));
    }

    @Test
    void paddingDecoderPassesThroughNonMessage() {
        var decoder = new PaddingAndHeartbeatDecoder();
        assertEquals("test", decoder.decode(Event.OPEN, "test"));
    }

    @Test
    void paddingDecoderCustomHeartbeat() {
        var decoder = new PaddingAndHeartbeatDecoder("♥");
        assertNull(decoder.decode(Event.MESSAGE, " ♥ "));
        assertEquals("hello", decoder.decode(Event.MESSAGE, "hello"));
    }

    @Test
    void trackMessageSizeDecodesSimpleMessage() {
        var decoder = new TrackMessageSizeDecoder();
        var result = decoder.decode(Event.MESSAGE, "5|hello");
        assertEquals("hello", result);
    }

    @Test
    void trackMessageSizeHandlesFragmentedMessages() {
        var decoder = new TrackMessageSizeDecoder();
        // First fragment: length prefix + partial data
        var result1 = decoder.decode(Event.MESSAGE, "11|hello");
        assertNull(result1); // waiting for more data

        // Second fragment: rest of the data
        var result2 = decoder.decode(Event.MESSAGE, " world");
        assertEquals("hello world", result2);
    }

    @Test
    void trackMessageSizeCustomDelimiter() {
        var decoder = new TrackMessageSizeDecoder("#", false);
        var result = decoder.decode(Event.MESSAGE, "3#abc");
        assertEquals("abc", result);
    }

    @Test
    void trackMessageSizeSkipsFirst() {
        var decoder = new TrackMessageSizeDecoder("|", true);
        // First message should be skipped (protocol handshake)
        var result1 = decoder.decode(Event.MESSAGE, "4|uuid");
        assertNull(result1);

        // Second message should be returned
        var result2 = decoder.decode(Event.MESSAGE, "5|hello");
        assertEquals("hello", result2);
    }

    @Test
    void trackMessageSizePassesThroughNonMessage() {
        var decoder = new TrackMessageSizeDecoder();
        assertEquals("test", decoder.decode(Event.OPEN, "test"));
    }
}
