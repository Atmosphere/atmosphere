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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExceptionTest {

    @Test
    void messageOnlyConstructor() {
        var ex = new AiException("model timeout");
        assertEquals("model timeout", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void messageAndCauseConstructor() {
        var cause = new RuntimeException("connection refused");
        var ex = new AiException("API call failed", cause);
        assertEquals("API call failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void isRuntimeException() {
        assertTrue(RuntimeException.class.isAssignableFrom(
                AiException.class));
    }

    @Test
    void canBeCaughtAsRuntimeException() {
        try {
            throw new AiException("test");
        } catch (RuntimeException e) {
            assertEquals("test", e.getMessage());
        }
    }
}
