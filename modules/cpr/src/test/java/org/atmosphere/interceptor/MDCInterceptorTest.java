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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MDCInterceptorTest {

    private final MDCInterceptor interceptor = new MDCInterceptor();

    private AtmosphereResource mockResourceWithResponse(String uuid, AtmosphereResource.TRANSPORT transport, Broadcaster broadcaster) {
        AtmosphereResource resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.transport()).thenReturn(transport);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        AtmosphereResponse response = mock(AtmosphereResponse.class);
        when(resource.getResponse()).thenReturn(response);
        return resource;
    }

    @Test
    void inspectSetsUuidInMDC() {
        Broadcaster broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("broadcaster-1");
        AtmosphereResource resource = mockResourceWithResponse("test-uuid-123",
                AtmosphereResource.TRANSPORT.WEBSOCKET, broadcaster);

        interceptor.inspect(resource);

        assertEquals("test-uuid-123", MDC.get(MDCInterceptor.MDC_UUID));
        MDC.clear();
    }

    @Test
    void inspectSetsTransportInMDC() {
        AtmosphereResource resource = mockResourceWithResponse("uuid",
                AtmosphereResource.TRANSPORT.LONG_POLLING, null);

        interceptor.inspect(resource);

        assertEquals("LONG_POLLING", MDC.get(MDCInterceptor.MDC_TRANSPORT));
        MDC.clear();
    }

    @Test
    void inspectSetsBroadcasterInMDC() {
        Broadcaster broadcaster = mock(Broadcaster.class);
        when(broadcaster.getID()).thenReturn("my-broadcaster");
        AtmosphereResource resource = mockResourceWithResponse("uuid",
                AtmosphereResource.TRANSPORT.SSE, broadcaster);

        interceptor.inspect(resource);

        assertEquals("my-broadcaster", MDC.get(MDCInterceptor.MDC_BROADCASTER));
        MDC.clear();
    }

    @Test
    void inspectSkipsNullTransport() {
        AtmosphereResource resource = mockResourceWithResponse("uuid", null, null);

        interceptor.inspect(resource);

        assertNull(MDC.get(MDCInterceptor.MDC_TRANSPORT));
        MDC.clear();
    }

    @Test
    void inspectSkipsNullBroadcaster() {
        AtmosphereResource resource = mockResourceWithResponse("uuid",
                AtmosphereResource.TRANSPORT.WEBSOCKET, null);

        interceptor.inspect(resource);

        assertNull(MDC.get(MDCInterceptor.MDC_BROADCASTER));
        MDC.clear();
    }

    @Test
    void inspectReturnsContinue() {
        AtmosphereResource resource = mockResourceWithResponse("uuid", null, null);

        Action action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        MDC.clear();
    }

    @Test
    void postInspectClearsMDC() {
        MDC.put(MDCInterceptor.MDC_UUID, "val");
        MDC.put(MDCInterceptor.MDC_TRANSPORT, "val");
        MDC.put(MDCInterceptor.MDC_BROADCASTER, "val");

        AtmosphereResource resource = mock(AtmosphereResource.class);
        interceptor.postInspect(resource);

        assertNull(MDC.get(MDCInterceptor.MDC_UUID));
        assertNull(MDC.get(MDCInterceptor.MDC_TRANSPORT));
        assertNull(MDC.get(MDCInterceptor.MDC_BROADCASTER));
    }

    @Test
    void toStringReturnsExpected() {
        assertEquals("MDCInterceptor{}", interceptor.toString());
    }
}
