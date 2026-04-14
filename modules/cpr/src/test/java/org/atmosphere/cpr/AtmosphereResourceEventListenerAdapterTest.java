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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AtmosphereResourceEventListenerAdapterTest {

    private final AtmosphereResourceEventListenerAdapter adapter = new AtmosphereResourceEventListenerAdapter();

    @Test
    void allCallbacksDoNotThrow() {
        var event = mock(AtmosphereResourceEvent.class);
        assertDoesNotThrow(() -> {
            adapter.onPreSuspend(event);
            adapter.onSuspend(event);
            adapter.onResume(event);
            adapter.onHeartbeat(event);
            adapter.onDisconnect(event);
            adapter.onBroadcast(event);
            adapter.onThrowable(event);
            adapter.onClose(event);
        });
    }

    @Test
    void onHeartbeatSubclass() {
        var called = new AtomicBoolean(false);
        var listener = new AtmosphereResourceEventListenerAdapter.OnHeartbeat() {
            @Override
            public void onHeartbeat(AtmosphereResourceEvent event) {
                called.set(true);
            }
        };
        listener.onHeartbeat(mock(AtmosphereResourceEvent.class));
        assertTrue(called.get());
    }

    @Test
    void onSuspendSubclass() {
        var called = new AtomicBoolean(false);
        var listener = new AtmosphereResourceEventListenerAdapter.OnSuspend() {
            @Override
            public void onSuspend(AtmosphereResourceEvent event) {
                called.set(true);
            }
        };
        listener.onSuspend(mock(AtmosphereResourceEvent.class));
        assertTrue(called.get());
    }

    @Test
    void onDisconnectSubclass() {
        var called = new AtomicBoolean(false);
        var listener = new AtmosphereResourceEventListenerAdapter.OnDisconnect() {
            @Override
            public void onDisconnect(AtmosphereResourceEvent event) {
                called.set(true);
            }
        };
        listener.onDisconnect(mock(AtmosphereResourceEvent.class));
        assertTrue(called.get());
    }

    @Test
    void onBroadcastSubclass() {
        var called = new AtomicBoolean(false);
        var listener = new AtmosphereResourceEventListenerAdapter.OnBroadcast() {
            @Override
            public void onBroadcast(AtmosphereResourceEvent event) {
                called.set(true);
            }
        };
        listener.onBroadcast(mock(AtmosphereResourceEvent.class));
        assertTrue(called.get());
    }

    @Test
    void onCloseSubclass() {
        var called = new AtomicBoolean(false);
        var listener = new AtmosphereResourceEventListenerAdapter.OnClose() {
            @Override
            public void onClose(AtmosphereResourceEvent event) {
                called.set(true);
            }
        };
        listener.onClose(mock(AtmosphereResourceEvent.class));
        assertTrue(called.get());
    }
}
