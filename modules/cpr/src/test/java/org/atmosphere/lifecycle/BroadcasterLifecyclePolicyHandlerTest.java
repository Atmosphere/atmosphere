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
package org.atmosphere.lifecycle;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.cpr.BroadcasterLifeCyclePolicy;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BroadcasterLifecyclePolicyHandlerTest {

    private BroadcasterLifecyclePolicyHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BroadcasterLifecyclePolicyHandler();
    }

    @Test
    void onPostCreateSetsLifecycleHandlerForDefaultBroadcaster() {
        DefaultBroadcaster broadcaster = Mockito.mock(DefaultBroadcaster.class);
        BroadcasterConfig bc = Mockito.mock(BroadcasterConfig.class);
        when(broadcaster.getID()).thenReturn("/test");
        when(broadcaster.getBroadcasterLifeCyclePolicy())
                .thenReturn(BroadcasterLifeCyclePolicy.NEVER);
        when(broadcaster.getAtmosphereResources())
                .thenReturn(java.util.List.of());
        when(broadcaster.getBroadcasterConfig()).thenReturn(bc);
        when(broadcaster.recentActivity()).thenReturn(new AtomicBoolean(false));
        when(broadcaster.currentLifecycleTask()).thenReturn(null);
        when(bc.getScheduledExecutorService()).thenReturn(null);

        handler.onPostCreate(broadcaster);

        verify(broadcaster).lifecycleHandler(Mockito.any(LifecycleHandler.class));
    }

    @Test
    void onPostCreateIgnoresNonDefaultBroadcaster() {
        Broadcaster broadcaster = Mockito.mock(Broadcaster.class);

        // Should not throw or fail — simply ignores non-DefaultBroadcaster
        handler.onPostCreate(broadcaster);
    }

    @Test
    void onPreDestroyCallsOffOnLifecycleHandler() {
        DefaultBroadcaster broadcaster = Mockito.mock(DefaultBroadcaster.class);
        LifecycleHandler lifecycleHandler = Mockito.mock(LifecycleHandler.class);
        when(broadcaster.lifecycleHandler()).thenReturn(lifecycleHandler);

        handler.onPreDestroy(broadcaster);

        verify(lifecycleHandler).off(broadcaster);
    }

    @Test
    void onPreDestroySkipsWhenLifecycleHandlerIsNull() {
        DefaultBroadcaster broadcaster = Mockito.mock(DefaultBroadcaster.class);
        when(broadcaster.lifecycleHandler()).thenReturn(null);

        // Should not throw
        handler.onPreDestroy(broadcaster);
    }

    @Test
    void onPreDestroyIgnoresNonDefaultBroadcaster() {
        Broadcaster broadcaster = Mockito.mock(Broadcaster.class);

        // Should not throw
        handler.onPreDestroy(broadcaster);
    }

    @Test
    void onRemoveAtmosphereResourceCallsOffIfEmpty() {
        DefaultBroadcaster broadcaster = Mockito.mock(DefaultBroadcaster.class);
        LifecycleHandler lifecycleHandler = Mockito.mock(LifecycleHandler.class);
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        when(broadcaster.lifecycleHandler()).thenReturn(lifecycleHandler);

        handler.onRemoveAtmosphereResource(broadcaster, resource);

        verify(lifecycleHandler).offIfEmpty(broadcaster);
    }

    @Test
    void onRemoveAtmosphereResourceSkipsWhenLifecycleHandlerIsNull() {
        DefaultBroadcaster broadcaster = Mockito.mock(DefaultBroadcaster.class);
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        when(broadcaster.lifecycleHandler()).thenReturn(null);

        // Should not throw
        handler.onRemoveAtmosphereResource(broadcaster, resource);
    }

    @Test
    void onRemoveAtmosphereResourceIgnoresNonDefaultBroadcaster() {
        Broadcaster broadcaster = Mockito.mock(Broadcaster.class);
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);

        // Should not throw
        handler.onRemoveAtmosphereResource(broadcaster, resource);
    }
}
