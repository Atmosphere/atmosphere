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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AtmosphereMappingExceptionTest {

    @Test
    void defaultConstructorHasNoMessage() {
        var ex = new AtmosphereMappingException();
        assertNull(ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void messageConstructorSetsMessage() {
        var ex = new AtmosphereMappingException("mapping failed");
        assertEquals("mapping failed", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void messageAndCauseConstructor() {
        var cause = new IllegalStateException("root cause");
        var ex = new AtmosphereMappingException("failed", cause);
        assertEquals("failed", ex.getMessage());
        assertSame(cause, ex.getCause());
    }

    @Test
    void causeOnlyConstructor() {
        var cause = new RuntimeException("boom");
        var ex = new AtmosphereMappingException(cause);
        assertSame(cause, ex.getCause());
    }

    @Test
    void isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new AtmosphereMappingException());
    }
}
