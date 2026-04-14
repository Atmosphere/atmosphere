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
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AndroidAtmosphereInterceptorTest {

    private final AndroidAtmosphereInterceptor interceptor = new AndroidAtmosphereInterceptor();

    @Test
    void skipsNonStreamingTransport() {
        var r = mock(AtmosphereResource.class);
        when(r.transport()).thenReturn(AtmosphereResource.TRANSPORT.LONG_POLLING);
        assertEquals(Action.CONTINUE, interceptor.inspect(r));
    }

    @Test
    void skipsNonAndroidUserAgent() {
        var r = mock(AtmosphereResourceImpl.class);
        var response = mock(AtmosphereResponse.class);
        var request = mock(AtmosphereRequest.class);
        var writer = mock(AtmosphereInterceptorWriter.class);

        when(r.transport()).thenReturn(AtmosphereResource.TRANSPORT.STREAMING);
        when(r.getResponse(false)).thenReturn(response);
        when(r.getRequest(false)).thenReturn(request);
        when(request.getHeader("User-Agent")).thenReturn("Chrome/100");
        when(response.getAsyncIOWriter()).thenReturn(writer);

        assertEquals(Action.CONTINUE, interceptor.inspect(r));
    }

    @Test
    void addsInterceptorForAndroid2() {
        var r = mock(AtmosphereResourceImpl.class);
        var response = mock(AtmosphereResponse.class);
        var request = mock(AtmosphereRequest.class);
        var writer = mock(AtmosphereInterceptorWriter.class);

        when(r.transport()).thenReturn(AtmosphereResource.TRANSPORT.STREAMING);
        when(r.getResponse(false)).thenReturn(response);
        when(r.getResponse()).thenReturn(response);
        when(r.getRequest(false)).thenReturn(request);
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0 Android 2.3");
        when(response.getAsyncIOWriter()).thenReturn(writer);

        assertEquals(Action.CONTINUE, interceptor.inspect(r));
    }

    @Test
    void toStringDescriptive() {
        assertEquals("Android Interceptor Support", interceptor.toString());
    }
}
