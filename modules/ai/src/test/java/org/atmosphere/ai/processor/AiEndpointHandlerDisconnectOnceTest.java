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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A close and a broadcast in flight both hand the handler the resource's
 * cancelled event: the close thread runs the disconnect, and a broadcast that
 * passed the broadcaster's validity check just before the cancel delivers the
 * same event again, possibly after {@code destroy()} stripped its resource.
 * The sweep saw {@code @Disconnect} 19 times for one client of an AI sample.
 * The disconnect (the {@code @Disconnect} method and the interceptors'
 * {@code onDisconnect}) must run once per resource (Invariant #2).
 */
class AiEndpointHandlerDisconnectOnceTest {

    static final AtomicInteger DISCONNECTS = new AtomicInteger();

    static class Endpoint {
        @Prompt
        public void onPrompt(String message) {
            // Not exercised by the disconnect path.
        }

        @Disconnect
        public void onDisconnect(AtmosphereResourceEvent event) {
            DISCONNECTS.incrementAndGet();
        }
    }

    static final class CountingInterceptor implements AiInterceptor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void onDisconnect(String userId, String conversationId, List<ChatMessage> history) {
            calls.incrementAndGet();
        }
    }

    private AiEndpointHandler handler;
    private CountingInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        DISCONNECTS.set(0);
        Method promptMethod = Endpoint.class.getMethod("onPrompt", String.class);
        interceptor = new CountingInterceptor();
        handler = new AiEndpointHandler(new Endpoint(), promptMethod, 30_000L, "",
                mock(AgentRuntime.class), List.<AiInterceptor>of(interceptor));
    }

    private static AtmosphereResourceEventImpl cancelledEventFor(String uuid) {
        var resource = mock(AtmosphereResourceImpl.class);
        when(resource.uuid()).thenReturn(uuid);
        when(resource.getRequest()).thenReturn(mock(AtmosphereRequest.class));
        when(resource.getBroadcaster()).thenReturn(mock(Broadcaster.class));
        // isCancelled=true: what completeLifecycle sets before the handler runs
        return new AtmosphereResourceEventImpl(resource, true, false);
    }

    @Test
    void cancelledEventDeliveredByTheCloseAndAnInFlightBroadcastDisconnectsOnce() throws Exception {
        var event = cancelledEventFor("uuid-disconnect-once");

        handler.onStateChange(event); // the close thread
        handler.onStateChange(event); // the broadcast that passed the validity check

        assertEquals(1, DISCONNECTS.get(), "@Disconnect must fire once per resource");
        assertEquals(1, interceptor.calls.get(), "interceptors must see one disconnect");
    }

    @Test
    void broadcastLandingAfterDestroyDoesNotDisconnectAgain() throws Exception {
        var event = cancelledEventFor("uuid-disconnect-after-destroy");

        handler.onStateChange(event); // the close thread, resource still attached
        event.destroy();              // cancel() strips the resource
        assertNull(event.getResource(), "precondition: the late delivery takes the recycled path");
        handler.onStateChange(event); // the broadcast still in flight

        assertEquals(1, DISCONNECTS.get(), "@Disconnect must fire once per resource");
        assertEquals(1, interceptor.calls.get(), "interceptors must see one disconnect");
    }
}
