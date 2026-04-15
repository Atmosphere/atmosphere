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
package org.atmosphere.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheMessageTest {

    @Test
    void canonicalConstructorStoresAll() {
        var msg = new CacheMessage("id1", 100L, "hello", "uuid1");
        assertEquals("id1", msg.id());
        assertEquals(100L, msg.createTime());
        assertEquals("hello", msg.message());
        assertEquals("uuid1", msg.uuid());
    }

    @Test
    void shortConstructorUsesNanoTime() {
        long before = System.nanoTime();
        var msg = new CacheMessage("id", "data", "u");
        long after = System.nanoTime();
        assertTrue(msg.createTime() >= before);
        assertTrue(msg.createTime() <= after);
    }

    @Test
    void getMessageReturnsMessage() {
        var msg = new CacheMessage("id", 0, "hello", "u");
        assertEquals("hello", msg.getMessage());
    }

    @Test
    void getIdReturnsId() {
        var msg = new CacheMessage("myId", 0, "x", "u");
        assertEquals("myId", msg.getId());
    }

    @Test
    void getCreateTimeReturnsTime() {
        var msg = new CacheMessage("id", 42L, "x", "u");
        assertEquals(42L, msg.getCreateTime());
    }

    @Test
    void toStringReturnsMessageString() {
        var msg = new CacheMessage("id", 0, "payload", "u");
        assertEquals("payload", msg.toString());
    }
}
