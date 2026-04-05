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
package org.atmosphere.checkpoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckpointIdTest {

    @Test
    void randomProducesUniqueValues() {
        var a = CheckpointId.random();
        var b = CheckpointId.random();
        assertNotEquals(a, b);
    }

    @Test
    void ofWrapsValue() {
        var id = CheckpointId.of("abc-123");
        assertEquals("abc-123", id.value());
        assertEquals("abc-123", id.toString());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> CheckpointId.of(null));
    }

    @Test
    void rejectsBlankValue() {
        assertThrows(IllegalArgumentException.class, () -> CheckpointId.of("   "));
    }

    @Test
    void equalByValue() {
        assertEquals(CheckpointId.of("x"), CheckpointId.of("x"));
    }
}
