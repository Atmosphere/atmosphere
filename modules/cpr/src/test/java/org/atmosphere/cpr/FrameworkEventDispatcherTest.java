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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class FrameworkEventDispatcherTest {

    private FrameworkEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new FrameworkEventDispatcher();
    }

    @Test
    void addAndRetrieveAsyncSupportListener() {
        var listener = Mockito.mock(AsyncSupportListener.class);
        dispatcher.addAsyncSupportListener(listener);
        assertTrue(dispatcher.asyncSupportListeners().contains(listener));
    }

    @Test
    void addAndRetrieveResourceListener() {
        var listener = Mockito.mock(AtmosphereResourceListener.class);
        dispatcher.addAtmosphereResourceListener(listener);
        assertTrue(dispatcher.atmosphereResourceListeners().contains(listener));
    }

    @Test
    void addAndRetrieveFrameworkListener() {
        var listener = Mockito.mock(AtmosphereFrameworkListener.class);
        dispatcher.addFrameworkListener(listener);
        assertTrue(dispatcher.frameworkListeners().contains(listener));
    }

    @Test
    void notifySuspendCallsListener() {
        var listener = Mockito.mock(AsyncSupportListener.class);
        dispatcher.addAsyncSupportListener(listener);
        var req = Mockito.mock(AtmosphereRequest.class);
        var res = Mockito.mock(AtmosphereResponse.class);
        dispatcher.notify(Action.TYPE.SUSPEND, req, res);
        verify(listener).onSuspend(req, res);
    }

    @Test
    void notifyTimeoutCallsListener() {
        var listener = Mockito.mock(AsyncSupportListener.class);
        dispatcher.addAsyncSupportListener(listener);
        var req = Mockito.mock(AtmosphereRequest.class);
        var res = Mockito.mock(AtmosphereResponse.class);
        dispatcher.notify(Action.TYPE.TIMEOUT, req, res);
        verify(listener).onTimeout(req, res);
    }

    @Test
    void notifyResumeCallsListener() {
        var listener = Mockito.mock(AsyncSupportListener.class);
        dispatcher.addAsyncSupportListener(listener);
        var req = Mockito.mock(AtmosphereRequest.class);
        var res = Mockito.mock(AtmosphereResponse.class);
        dispatcher.notify(Action.TYPE.RESUME, req, res);
        verify(listener).onResume(req, res);
    }

    @Test
    void notifyCancelledCallsOnClose() {
        var listener = Mockito.mock(AsyncSupportListener.class);
        dispatcher.addAsyncSupportListener(listener);
        var req = Mockito.mock(AtmosphereRequest.class);
        var res = Mockito.mock(AtmosphereResponse.class);
        dispatcher.notify(Action.TYPE.CANCELLED, req, res);
        verify(listener).onClose(req, res);
    }

    @Test
    void notifyDestroyedCallsListener() {
        var listener = Mockito.mock(AsyncSupportListener.class);
        dispatcher.addAsyncSupportListener(listener);
        var req = Mockito.mock(AtmosphereRequest.class);
        var res = Mockito.mock(AtmosphereResponse.class);
        dispatcher.notify(Action.TYPE.DESTROYED, req, res);
        verify(listener).onDestroyed(req, res);
    }

    @Test
    void notifyHandlesListenerException() {
        var listener = Mockito.mock(AsyncSupportListener.class);
        var req = Mockito.mock(AtmosphereRequest.class);
        var res = Mockito.mock(AtmosphereResponse.class);
        doThrow(new RuntimeException("test")).when(listener).onSuspend(req, res);
        dispatcher.addAsyncSupportListener(listener);
        // Should not throw
        dispatcher.notify(Action.TYPE.SUSPEND, req, res);
    }

    @Test
    void notifyDestroyedResourceListener() {
        List<String> destroyed = new ArrayList<>();
        dispatcher.addAtmosphereResourceListener(new AtmosphereResourceListener() {
            @Override
            public void onSuspended(String uuid) {
            }

            @Override
            public void onDisconnect(String uuid) {
                destroyed.add(uuid);
            }
        });
        dispatcher.notifyDestroyed("uuid-123");
        assertEquals(1, destroyed.size());
        assertEquals("uuid-123", destroyed.getFirst());
    }

    @Test
    void notifySuspendedResourceListener() {
        List<String> suspended = new ArrayList<>();
        dispatcher.addAtmosphereResourceListener(new AtmosphereResourceListener() {
            @Override
            public void onSuspended(String uuid) {
                suspended.add(uuid);
            }

            @Override
            public void onDisconnect(String uuid) {
            }
        });
        dispatcher.notifySuspended("uuid-456");
        assertEquals(1, suspended.size());
    }

    @Test
    void clearRemovesAsyncAndResourceListenersButKeepsFramework() {
        dispatcher.addAsyncSupportListener(Mockito.mock(AsyncSupportListener.class));
        dispatcher.addAtmosphereResourceListener(Mockito.mock(AtmosphereResourceListener.class));
        dispatcher.addFrameworkListener(Mockito.mock(AtmosphereFrameworkListener.class));

        dispatcher.clear();

        assertTrue(dispatcher.asyncSupportListeners().isEmpty());
        assertTrue(dispatcher.atmosphereResourceListeners().isEmpty());
        assertFalse(dispatcher.frameworkListeners().isEmpty(),
                "Framework listeners should survive clear()");
    }
}
