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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeartbeatAtmosphereResourceEventTest {

    private AtmosphereResourceImpl createMockResource(String uuid) {
        var resource = mock(AtmosphereResourceImpl.class);
        when(resource.uuid()).thenReturn(uuid);
        return resource;
    }

    @Test
    void constructorSetsResource() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertNotNull(event.getResource());
        assertEquals(resource, event.getResource());
    }

    @Test
    void extendsAtmosphereResourceEventImpl() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertInstanceOf(AtmosphereResourceEventImpl.class, event);
    }

    @Test
    void implementsAtmosphereResourceEvent() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertInstanceOf(AtmosphereResourceEvent.class, event);
    }

    @Test
    void isCancelledDefaultsFalse() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertFalse(event.isCancelled());
    }

    @Test
    void isResumedOnTimeoutDefaultsFalse() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertFalse(event.isResumedOnTimeout());
    }

    @Test
    void messageDefaultsToNull() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertNull(event.getMessage());
    }

    @Test
    void setMessageWorks() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        event.setMessage("heartbeat-data");
        assertEquals("heartbeat-data", event.getMessage());
    }

    @Test
    void isClosedByClientDefaultsFalse() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertFalse(event.isClosedByClient());
    }

    @Test
    void isClosedByApplicationDefaultsFalse() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        assertFalse(event.isClosedByApplication());
    }

    @Test
    void toStringContainsUuid() {
        var resource = createMockResource("test-uuid");
        var event = new HeartbeatAtmosphereResourceEvent(resource);

        var str = event.toString();
        assertNotNull(str);
        assertTrue(str.contains("test-uuid"));
    }
}
