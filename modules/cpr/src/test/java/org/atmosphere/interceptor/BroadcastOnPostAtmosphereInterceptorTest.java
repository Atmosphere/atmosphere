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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcastOnPostAtmosphereInterceptorTest {

    private final BroadcastOnPostAtmosphereInterceptor interceptor = new BroadcastOnPostAtmosphereInterceptor();

    @Test
    void inspectAlwaysContinues() {
        var r = mock(AtmosphereResource.class);
        assertEquals(Action.CONTINUE, interceptor.inspect(r));
    }

    @Test
    void postInspectIgnoresGetRequests() {
        var r = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var broadcaster = mock(Broadcaster.class);

        when(r.getRequest()).thenReturn(request);
        when(r.getBroadcaster()).thenReturn(broadcaster);
        when(request.getMethod()).thenReturn("GET");

        interceptor.postInspect(r);
        verify(broadcaster, never()).broadcast(org.mockito.ArgumentMatchers.any());
    }
}
