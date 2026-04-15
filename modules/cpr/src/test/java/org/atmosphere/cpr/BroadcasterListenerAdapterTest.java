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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BroadcasterListenerAdapterTest {

    private final BroadcasterListenerAdapter adapter = new BroadcasterListenerAdapter();

    private Broadcaster createMockBroadcaster(String id) {
        var b = mock(Broadcaster.class);
        when(b.getID()).thenReturn(id);
        return b;
    }

    @Test
    void implementsBroadcasterListener() {
        assertInstanceOf(BroadcasterListener.class, adapter);
    }

    @Test
    void allCallbacksDoNotThrow() {
        var broadcaster = createMockBroadcaster("test-bc");
        var resource = mock(AtmosphereResource.class);
        var deliver = mock(Deliver.class);

        assertDoesNotThrow(() -> {
            adapter.onPostCreate(broadcaster);
            adapter.onComplete(broadcaster);
            adapter.onPreDestroy(broadcaster);
            adapter.onAddAtmosphereResource(broadcaster, resource);
            adapter.onRemoveAtmosphereResource(broadcaster, resource);
            adapter.onMessage(broadcaster, deliver);
        });
    }

    @Test
    void onPostCreateWithBroadcaster() {
        assertDoesNotThrow(() -> adapter.onPostCreate(createMockBroadcaster("bc-1")));
    }

    @Test
    void onCompleteWithBroadcaster() {
        assertDoesNotThrow(() -> adapter.onComplete(createMockBroadcaster("bc-2")));
    }

    @Test
    void onPreDestroyWithBroadcaster() {
        assertDoesNotThrow(() -> adapter.onPreDestroy(createMockBroadcaster("bc-3")));
    }

    @Test
    void subclassCanOverrideOnPostCreate() {
        var captured = new AtomicReference<Broadcaster>();
        var custom = new BroadcasterListenerAdapter() {
            @Override
            public void onPostCreate(Broadcaster b) {
                captured.set(b);
            }
        };
        var bc = createMockBroadcaster("custom-bc");
        custom.onPostCreate(bc);
        assertEquals(bc, captured.get());
    }

    @Test
    void subclassCanOverrideOnComplete() {
        var called = new AtomicBoolean(false);
        var custom = new BroadcasterListenerAdapter() {
            @Override
            public void onComplete(Broadcaster b) {
                called.set(true);
            }
        };
        custom.onComplete(createMockBroadcaster("bc"));
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnPreDestroy() {
        var called = new AtomicBoolean(false);
        var custom = new BroadcasterListenerAdapter() {
            @Override
            public void onPreDestroy(Broadcaster b) {
                called.set(true);
            }
        };
        custom.onPreDestroy(createMockBroadcaster("bc"));
        assertTrue(called.get());
    }

    @Test
    void subclassCanOverrideOnAddAtmosphereResource() {
        var capturedResource = new AtomicReference<AtmosphereResource>();
        var custom = new BroadcasterListenerAdapter() {
            @Override
            public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
                capturedResource.set(r);
            }
        };
        var resource = mock(AtmosphereResource.class);
        custom.onAddAtmosphereResource(createMockBroadcaster("bc"), resource);
        assertEquals(resource, capturedResource.get());
    }

    @Test
    void subclassCanOverrideOnRemoveAtmosphereResource() {
        var capturedResource = new AtomicReference<AtmosphereResource>();
        var custom = new BroadcasterListenerAdapter() {
            @Override
            public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
                capturedResource.set(r);
            }
        };
        var resource = mock(AtmosphereResource.class);
        custom.onRemoveAtmosphereResource(createMockBroadcaster("bc"), resource);
        assertEquals(resource, capturedResource.get());
    }

    @Test
    void subclassCanOverrideOnMessage() {
        var capturedDeliver = new AtomicReference<Deliver>();
        var custom = new BroadcasterListenerAdapter() {
            @Override
            public void onMessage(Broadcaster b, Deliver deliver) {
                capturedDeliver.set(deliver);
            }
        };
        var deliver = mock(Deliver.class);
        custom.onMessage(createMockBroadcaster("bc"), deliver);
        assertEquals(deliver, capturedDeliver.get());
    }
}
