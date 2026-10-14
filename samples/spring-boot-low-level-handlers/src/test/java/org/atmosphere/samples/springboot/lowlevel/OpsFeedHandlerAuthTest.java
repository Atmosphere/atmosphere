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
package org.atmosphere.samples.springboot.lowlevel;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The README promises that only the on-call rota may publish to the raw ops feed. {@code @RoomAuth}
 * only guards Room Protocol frames, so a plain HTTP {@code POST} to the raw handler used to reach
 * the broadcaster with no identity at all: {@code fetch('/atmosphere/raw/ops', {method: 'POST'})}
 * was delivered to every subscriber. The handler must ask the same authorizer and fail closed.
 */
class OpsFeedHandlerAuthTest {

    private final Broadcaster broadcaster = Mockito.mock(Broadcaster.class);
    private final AtmosphereResponse response = Mockito.mock(AtmosphereResponse.class);

    private AtmosphereResource postFrom(String user, String body) throws Exception {
        AtmosphereRequest request = Mockito.mock(AtmosphereRequest.class);
        Mockito.when(request.getMethod()).thenReturn("POST");
        Mockito.when(request.getHeader(OncallRoomAuthorizer.USER_HEADER)).thenReturn(user);
        Mockito.when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        Mockito.when(broadcaster.getID()).thenReturn("/atmosphere/raw/ops");
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        Mockito.when(resource.getRequest()).thenReturn(request);
        Mockito.when(resource.getResponse()).thenReturn(response);
        Mockito.when(resource.getBroadcaster()).thenReturn(broadcaster);
        return resource;
    }

    @Test
    void anAnonymousPostIsRefusedAndNeverBroadcast() throws Exception {
        new OpsFeedHandler().onRequest(postFrom(null, "raw-hello"));

        verify(broadcaster, never()).broadcast(any());
        verify(response).setStatus(403);
    }

    @Test
    void anOffRotaPostIsRefusedAndNeverBroadcast() throws Exception {
        new OpsFeedHandler().onRequest(postFrom("mallory", "raw-hello"));

        verify(broadcaster, never()).broadcast(any());
        verify(response).setStatus(403);
    }

    @Test
    void theOncallRotaStillBroadcasts() throws Exception {
        new OpsFeedHandler().onRequest(postFrom("alice", "db failover in progress"));

        verify(broadcaster).broadcast("db failover in progress");
        verify(response, never()).setStatus(403);
    }
}
