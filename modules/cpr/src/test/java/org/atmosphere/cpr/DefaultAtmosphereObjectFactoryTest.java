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
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultAtmosphereObjectFactoryTest {

    private final DefaultAtmosphereObjectFactory factory = new DefaultAtmosphereObjectFactory();

    @Test
    void newClassInstanceCreatesInstance() throws Exception {
        StringBuilder result = factory.newClassInstance(CharSequence.class, StringBuilder.class);
        assertNotNull(result);
        assertInstanceOf(StringBuilder.class, result);
    }

    @Test
    void newClassInstanceThrowsForNoDefaultConstructor() {
        assertThrows(InstantiationException.class,
                () -> factory.newClassInstance(Object.class, NoDefaultConstructor.class));
    }

    @Test
    void configureDoesNotThrow() {
        AtmosphereConfig config = Mockito.mock(AtmosphereConfig.class);
        factory.configure(config);
        // no-op — just verifies it doesn't throw
    }

    @Test
    void allowInjectionReturnsSelf() {
        AtmosphereObjectFactory<Object> result = factory.allowInjectionOf("anything");
        assertSame(factory, result);
    }

    @Test
    void toStringReturnsClassName() {
        assertEquals("DefaultAtmosphereObjectFactory", factory.toString());
    }

    // Helper class with no default constructor
    static class NoDefaultConstructor {
        NoDefaultConstructor(String required) {
        }
    }
}
