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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AtmosphereResourceListenerAdapterTest {

    @Test
    void implementsAtmosphereResourceListener() {
        var adapter = new AtmosphereResourceListenerAdapter();
        assertInstanceOf(AtmosphereResourceListener.class, adapter);
    }

    @Test
    void onSuspendedDoesNotThrow() {
        var adapter = new AtmosphereResourceListenerAdapter();
        assertDoesNotThrow(() -> adapter.onSuspended("test-uuid-123"));
    }

    @Test
    void onSuspendedAcceptsNull() {
        var adapter = new AtmosphereResourceListenerAdapter();
        assertDoesNotThrow(() -> adapter.onSuspended(null));
    }

    @Test
    void onDisconnectDoesNotThrow() {
        var adapter = new AtmosphereResourceListenerAdapter();
        assertDoesNotThrow(() -> adapter.onDisconnect("test-uuid-456"));
    }

    @Test
    void onDisconnectAcceptsNull() {
        var adapter = new AtmosphereResourceListenerAdapter();
        assertDoesNotThrow(() -> adapter.onDisconnect(null));
    }

    @Test
    void canSubclassAndOverrideMethods() {
        var custom = new AtmosphereResourceListenerAdapter() {
            String lastSuspended;
            String lastDisconnected;

            @Override
            public void onSuspended(String uuid) {
                lastSuspended = uuid;
            }

            @Override
            public void onDisconnect(String uuid) {
                lastDisconnected = uuid;
            }
        };

        custom.onSuspended("s1");
        custom.onDisconnect("d1");

        org.junit.jupiter.api.Assertions.assertEquals("s1", custom.lastSuspended);
        org.junit.jupiter.api.Assertions.assertEquals("d1", custom.lastDisconnected);
    }
}
