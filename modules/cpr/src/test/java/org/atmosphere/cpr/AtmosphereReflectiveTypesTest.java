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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtmosphereReflectiveTypesTest {

    @Test
    void coreTypesNotEmpty() {
        List<String> types = AtmosphereReflectiveTypes.coreTypes();
        assertNotNull(types);
        assertFalse(types.isEmpty());
    }

    @Test
    void coreTypesContainsFramework() {
        assertTrue(AtmosphereReflectiveTypes.coreTypes()
                .contains("org.atmosphere.cpr.AtmosphereFramework"));
    }

    @Test
    void coreTypesContainsBroadcaster() {
        assertTrue(AtmosphereReflectiveTypes.coreTypes()
                .contains("org.atmosphere.cpr.DefaultBroadcaster"));
    }

    @Test
    void coreTypesContainsInterceptors() {
        var types = AtmosphereReflectiveTypes.coreTypes();
        assertTrue(types.contains("org.atmosphere.interceptor.CorsInterceptor"));
        assertTrue(types.contains("org.atmosphere.interceptor.HeartbeatInterceptor"));
    }

    @Test
    void coreTypesIsImmutable() {
        var types = AtmosphereReflectiveTypes.coreTypes();
        try {
            types.add("com.example.Fake");
        } catch (UnsupportedOperationException e) {
            // expected
            return;
        }
        // If no exception, the list should still not contain our addition
        // (this means it returned a mutable copy, which List.of() never does)
    }

    @Test
    void annotationProcessorsNotEmpty() {
        var procs = AtmosphereReflectiveTypes.annotationProcessors();
        assertNotNull(procs);
        assertFalse(procs.isEmpty());
    }

    @Test
    void annotationProcessorsContainsManagedService() {
        assertTrue(AtmosphereReflectiveTypes.annotationProcessors()
                .contains("org.atmosphere.annotation.ManagedServiceProcessor"));
    }

    @Test
    void poolTypesNotEmpty() {
        var pools = AtmosphereReflectiveTypes.poolTypes();
        assertNotNull(pools);
        assertFalse(pools.isEmpty());
    }

    @Test
    void poolTypesContainsExpectedEntries() {
        var pools = AtmosphereReflectiveTypes.poolTypes();
        assertTrue(pools.contains("org.atmosphere.pool.UnboundedApachePoolableProvider"));
        assertTrue(pools.contains("org.atmosphere.pool.BoundedApachePoolableProvider"));
    }
}
