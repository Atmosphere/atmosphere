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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtmosphereResourceEventImplTest {

    private AtmosphereResourceImpl resource;
    private AtmosphereResourceEventImpl event;

    @BeforeEach
    void setUp() {
        resource = mock(AtmosphereResourceImpl.class);
        when(resource.uuid()).thenReturn("test-uuid");
        when(resource.action()).thenReturn(new Action());
        event = new AtmosphereResourceEventImpl(resource);
    }

    @Test
    void defaultStateNotCancelledNotResumed() {
        assertFalse(event.isCancelled());
        assertFalse(event.isResumedOnTimeout());
    }

    @Test
    void messageSetAndGet() {
        assertNull(event.getMessage());
        var result = event.setMessage("hello");
        assertEquals("hello", event.getMessage());
        assertEquals(event, result);
    }

    @Test
    void getResourceReturnsResource() {
        assertEquals(resource, event.getResource());
    }

    @Test
    void throwableSetAndGet() {
        assertNull(event.throwable());
        var ex = new RuntimeException("test");
        event.setThrowable(ex);
        assertEquals(ex, event.throwable());
    }

    @Test
    void isResumingDelegatesToResource() {
        when(resource.isResumed()).thenReturn(true);
        assertTrue(event.isResuming());
    }

    @Test
    void isSuspendedDelegatesToResource() {
        when(resource.isSuspended()).thenReturn(true);
        assertTrue(event.isSuspended());
    }

    @Test
    void broadcasterDelegatesToResource() {
        var broadcaster = mock(Broadcaster.class);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        assertEquals(broadcaster, event.broadcaster());
    }

    @Test
    void closedByClientFlags() {
        assertFalse(event.isClosedByClient());
        event.isClosedByClient(true);
        assertTrue(event.isClosedByClient());
    }

    @Test
    void closedByApplicationFlags() {
        assertFalse(event.isClosedByApplication());
        event.setCloseByApplication(true);
        assertTrue(event.isClosedByApplication());
    }

    @Test
    void constructorWithCancelledAndResumed() {
        var evt = new AtmosphereResourceEventImpl(resource, true, true);
        assertTrue(evt.isCancelled());
        assertTrue(evt.isResumedOnTimeout());
    }

    @Test
    void constructorWithThrowable() {
        var ex = new RuntimeException("error");
        var evt = new AtmosphereResourceEventImpl(resource, false, false, ex);
        assertEquals(ex, evt.throwable());
    }

    @Test
    void constructorWithClosedByClient() {
        var evt = new AtmosphereResourceEventImpl(resource, false, false, true, null);
        assertTrue(evt.isClosedByClient());
    }

    @Test
    void setCancelledUpdatesResourceAction() {
        event.setCancelled(true);
        assertTrue(event.isCancelled());
    }

    @Test
    void destroyClearsState() {
        event.setMessage("msg");
        event.destroy();
        assertTrue(event.isCancelled());
        assertNull(event.getMessage());
        assertNull(event.getResource());
    }

    @Test
    void isResumingTrueWhenResourceNull() {
        event.destroy();
        assertTrue(event.isResuming());
    }

    @Test
    void broadcasterReturnsNullWhenResourceNull() {
        event.destroy();
        assertNull(event.broadcaster());
    }

    @Test
    void isSuspendedFalseWhenResourceNull() {
        event.destroy();
        assertFalse(event.isSuspended());
    }

    @Test
    void equalsReflexive() {
        assertEquals(event, event);
    }

    @Test
    void equalsNullAndDifferentType() {
        assertNotEquals(null, event);
        assertNotEquals("string", event);
    }

    @Test
    void toStringContainsUuid() {
        assertNotNull(event.toString());
        assertTrue(event.toString().contains("test-uuid"));
    }
}
