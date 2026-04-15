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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AllowInterceptorTest {

    @Test
    void classCanBeImplemented() {
        AllowInterceptor interceptor = new AllowInterceptor() {};
        assertNotNull(interceptor);
    }

    @Test
    void interceptorCanAlsoImplementAtmosphereInterceptor() {
        // AllowInterceptor is a marker interface used alongside AtmosphereInterceptor
        var combined = new CombinedInterceptor();
        assertInstanceOf(AllowInterceptor.class, combined);
        assertInstanceOf(AtmosphereInterceptor.class, combined);
    }

    @Test
    void markerInterfaceHasNoMethods() {
        // AllowInterceptor defines zero methods — it's purely a marker
        int methodCount = AllowInterceptor.class.getDeclaredMethods().length;
        assertNotNull(AllowInterceptor.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, methodCount);
    }

    @Test
    void anonymousImplementationIsAssignable() {
        Object obj = new AllowInterceptor() {};
        assertInstanceOf(AllowInterceptor.class, obj);
    }

    /**
     * Helper class that combines both interfaces, mirroring real usage in the framework.
     */
    private static class CombinedInterceptor implements AtmosphereInterceptor, AllowInterceptor {
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
    }
}
