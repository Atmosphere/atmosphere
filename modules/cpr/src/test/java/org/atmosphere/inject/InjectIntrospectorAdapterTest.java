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
package org.atmosphere.inject;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class InjectIntrospectorAdapterTest {

    @Test
    void supportedTypeReturnsFalse() {
        var adapter = new InjectIntrospectorAdapter<>();
        assertFalse(adapter.supportedType(String.class));
    }

    @Test
    void introspectFieldDoesNotThrow() {
        var adapter = new InjectIntrospectorAdapter<>();
        adapter.introspectField(null, null);
    }

    @Test
    void introspectMethodDoesNotThrow() {
        var adapter = new InjectIntrospectorAdapter<>();
        adapter.introspectMethod(null, null);
    }

    @Test
    void injectableResourceReturnsNull() {
        var adapter = new InjectIntrospectorAdapter<>();
        assertNull(adapter.injectable(mock(AtmosphereResource.class)));
    }

    @Test
    void injectableConfigReturnsNull() {
        var adapter = new InjectIntrospectorAdapter<>();
        assertNull(adapter.injectable(mock(AtmosphereConfig.class)));
    }
}
