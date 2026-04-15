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
package org.atmosphere.cpr;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link RawMessage} wrapper class.
 */
class RawMessageTest {

    @Test
    void messageShouldReturnWrappedStringMessage() {
        RawMessage raw = new RawMessage("hello");
        assertEquals("hello", raw.message());
    }

    @Test
    void messageShouldReturnWrappedObjectMessage() {
        List<String> payload = List.of("a", "b", "c");
        RawMessage raw = new RawMessage(payload);
        assertSame(payload, raw.message());
    }

    @Test
    void messageShouldReturnNullWhenConstructedWithNull() {
        RawMessage raw = new RawMessage(null);
        assertNull(raw.message());
    }

    @Test
    void toStringShouldDelegateToMessageToString() {
        RawMessage raw = new RawMessage("test-payload");
        assertEquals("test-payload", raw.toString());
    }

    @Test
    void toStringWithComplexObjectShouldUseObjectToString() {
        Object payload = new Object() {
            @Override
            public String toString() {
                return "custom-object";
            }
        };
        RawMessage raw = new RawMessage(payload);
        assertEquals("custom-object", raw.toString());
    }

    @Test
    void shouldBeSerializable() {
        RawMessage raw = new RawMessage("serializable");
        assertInstanceOf(Serializable.class, raw);
    }

    @Test
    void messageShouldReturnByteArrayPayload() {
        byte[] data = {1, 2, 3};
        RawMessage raw = new RawMessage(data);
        assertSame(data, raw.message());
    }

    @Test
    void messageShouldReturnIntegerPayload() {
        RawMessage raw = new RawMessage(42);
        assertEquals(42, raw.message());
    }
}
