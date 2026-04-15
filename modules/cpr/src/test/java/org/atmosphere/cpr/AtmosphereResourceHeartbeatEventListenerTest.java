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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AtmosphereResourceHeartbeatEventListenerTest {

    @Test
    void onHeartbeatIsInvokedWithEvent() {
        var called = new AtomicBoolean(false);
        var capturedEvent = new AtomicReference<AtmosphereResourceEvent>();

        AtmosphereResourceHeartbeatEventListener listener = event -> {
            called.set(true);
            capturedEvent.set(event);
        };

        var event = mock(AtmosphereResourceEvent.class);
        listener.onHeartbeat(event);

        assertTrue(called.get());
        assertNotNull(capturedEvent.get());
        assertEquals(event, capturedEvent.get());
    }

    @Test
    void onHeartbeatAcceptsNullEvent() {
        var called = new AtomicBoolean(false);
        var capturedEvent = new AtomicReference<AtmosphereResourceEvent>();

        AtmosphereResourceHeartbeatEventListener listener = event -> {
            called.set(true);
            capturedEvent.set(event);
        };

        listener.onHeartbeat(null);

        assertTrue(called.get());
        assertNull(capturedEvent.get());
    }

    @Test
    void multipleListenersCanBeInvokedIndependently() {
        List<String> invocations = new ArrayList<>();

        AtmosphereResourceHeartbeatEventListener listener1 = event -> invocations.add("listener1");
        AtmosphereResourceHeartbeatEventListener listener2 = event -> invocations.add("listener2");
        AtmosphereResourceHeartbeatEventListener listener3 = event -> invocations.add("listener3");

        var event = mock(AtmosphereResourceEvent.class);
        listener1.onHeartbeat(event);
        listener2.onHeartbeat(event);
        listener3.onHeartbeat(event);

        assertEquals(3, invocations.size());
        assertEquals("listener1", invocations.get(0));
        assertEquals("listener2", invocations.get(1));
        assertEquals("listener3", invocations.get(2));
    }

    @Test
    void listenerCanBeUsedAsLambda() {
        var count = new int[]{0};
        AtmosphereResourceHeartbeatEventListener listener = event -> count[0]++;

        var event = mock(AtmosphereResourceEvent.class);
        listener.onHeartbeat(event);
        listener.onHeartbeat(event);

        assertEquals(2, count[0]);
    }

    @Test
    void listenerIsAssignableFromEventListener() {
        AtmosphereResourceHeartbeatEventListener listener = event -> { };
        assertTrue(listener instanceof AtmosphereResourceHeartbeatEventListener);
    }
}
