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

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AtmosphereFrameworkListenerAdapterTest {

    private final AtmosphereFrameworkListenerAdapter adapter = new AtmosphereFrameworkListenerAdapter();

    @Test
    void allCallbacksDoNotThrow() {
        var framework = mock(AtmosphereFramework.class);
        assertDoesNotThrow(() -> {
            adapter.onPreInit(framework);
            adapter.onPostInit(framework);
            adapter.onPreDestroy(framework);
            adapter.onPostDestroy(framework);
        });
    }

    @Test
    void implementsFrameworkListener() {
        assertInstanceOf(AtmosphereFrameworkListener.class, adapter);
    }

    @Test
    void onPreInitAcceptsNull() {
        assertDoesNotThrow(() -> adapter.onPreInit(null));
    }

    @Test
    void onPostInitAcceptsNull() {
        assertDoesNotThrow(() -> adapter.onPostInit(null));
    }

    @Test
    void onPreDestroyAcceptsNull() {
        assertDoesNotThrow(() -> adapter.onPreDestroy(null));
    }

    @Test
    void onPostDestroyAcceptsNull() {
        assertDoesNotThrow(() -> adapter.onPostDestroy(null));
    }

    @Test
    void subclassCanOverrideOnPreInit() {
        var called = new AtomicBoolean(false);
        var custom = new AtmosphereFrameworkListenerAdapter() {
            @Override
            public void onPreInit(AtmosphereFramework f) {
                called.set(true);
            }
        };
        custom.onPreInit(mock(AtmosphereFramework.class));
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnPostInit() {
        var called = new AtomicBoolean(false);
        var custom = new AtmosphereFrameworkListenerAdapter() {
            @Override
            public void onPostInit(AtmosphereFramework f) {
                called.set(true);
            }
        };
        custom.onPostInit(mock(AtmosphereFramework.class));
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnPreDestroy() {
        var called = new AtomicBoolean(false);
        var custom = new AtmosphereFrameworkListenerAdapter() {
            @Override
            public void onPreDestroy(AtmosphereFramework f) {
                called.set(true);
            }
        };
        custom.onPreDestroy(mock(AtmosphereFramework.class));
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnPostDestroy() {
        var called = new AtomicBoolean(false);
        var custom = new AtmosphereFrameworkListenerAdapter() {
            @Override
            public void onPostDestroy(AtmosphereFramework f) {
                called.set(true);
            }
        };
        custom.onPostDestroy(mock(AtmosphereFramework.class));
        assertTrue(called.get());
    }
}
