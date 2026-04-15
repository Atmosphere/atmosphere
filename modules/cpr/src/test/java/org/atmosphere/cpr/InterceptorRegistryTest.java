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

import org.atmosphere.interceptor.InvokationOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterceptorRegistryTest {

    private InterceptorRegistry registry;

    @BeforeEach
    void setUp() {
        AtmosphereConfig config = Mockito.mock(AtmosphereConfig.class);
        registry = new InterceptorRegistry(config);
    }

    @Test
    void addInterceptorAddsToList() {
        AtmosphereInterceptor interceptor = new TestInterceptor();
        boolean added = registry.addInterceptor(interceptor, false);
        assertTrue(added);
        assertEquals(1, registry.interceptors().size());
    }

    @Test
    void addInterceptorRejectsDuplicate() {
        AtmosphereInterceptor interceptor1 = new TestInterceptor();
        AtmosphereInterceptor interceptor2 = new TestInterceptor();
        registry.addInterceptor(interceptor1, false);
        boolean added = registry.addInterceptor(interceptor2, false);
        assertFalse(added);
        assertEquals(1, registry.interceptors().size());
    }

    @Test
    void findInterceptorReturnsPresent() {
        TestInterceptor interceptor = new TestInterceptor();
        registry.addInterceptor(interceptor, false);
        Optional<TestInterceptor> found = registry.findInterceptor(TestInterceptor.class);
        assertTrue(found.isPresent());
    }

    @Test
    void findInterceptorReturnsEmptyWhenNotFound() {
        Optional<TestInterceptor> found = registry.findInterceptor(TestInterceptor.class);
        assertFalse(found.isPresent());
    }

    @SuppressWarnings("deprecation")
    @Test
    void interceptorReturnsNullWhenNotFound() {
        TestInterceptor result = registry.interceptor(TestInterceptor.class);
        assertNull(result);
    }

    @SuppressWarnings("deprecation")
    @Test
    void interceptorReturnsInstanceWhenFound() {
        TestInterceptor interceptor = new TestInterceptor();
        registry.addInterceptor(interceptor, false);
        TestInterceptor result = registry.interceptor(TestInterceptor.class);
        assertNotNull(result);
    }

    @Test
    void excludeInterceptorAddsToExcludedList() {
        registry.excludeInterceptor("org.example.SomeInterceptor");
        assertEquals(1, registry.excludedInterceptors().size());
        assertEquals("org.example.SomeInterceptor", registry.excludedInterceptors().get(0));
    }

    @Test
    void clearRemovesAllInterceptorsAndExclusions() {
        registry.addInterceptor(new TestInterceptor(), false);
        registry.excludeInterceptor("org.example.SomeInterceptor");
        registry.clear();
        assertEquals(0, registry.interceptors().size());
        assertEquals(0, registry.excludedInterceptors().size());
    }

    @Test
    void defaultInterceptorsReturnsNonEmptyArray() {
        Class<? extends AtmosphereInterceptor>[] defaults = registry.defaultInterceptors();
        assertNotNull(defaults);
        assertTrue(defaults.length > 0);
    }

    static class TestInterceptor implements AtmosphereInterceptor, InvokationOrder {
        @Override
        public void configure(AtmosphereConfig config) {
        }

        @Override
        public Action inspect(AtmosphereResource r) {
            return Action.CONTINUE;
        }

        @Override
        public void postInspect(AtmosphereResource r) {
        }

        @Override
        public void destroy() {
        }

        @Override
        public PRIORITY priority() {
            return AFTER_DEFAULT;
        }
    }
}
