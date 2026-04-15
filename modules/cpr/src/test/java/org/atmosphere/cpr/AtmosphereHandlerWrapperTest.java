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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AtmosphereHandlerWrapperTest {

    private AtmosphereHandler handler;
    private BroadcasterFactory broadcasterFactory;
    private Broadcaster broadcaster;
    private AtmosphereConfig config;

    @BeforeEach
    void setUp() {
        handler = mock(AtmosphereHandler.class);
        broadcasterFactory = mock(BroadcasterFactory.class);
        broadcaster = mock(Broadcaster.class);
        config = mock(AtmosphereConfig.class);

        var framework = mock(AtmosphereFramework.class);
        when(config.framework()).thenReturn(framework);
        doReturn(mock(AtmosphereObjectFactory.class)).when(framework).objectFactory();

        // startupHook invokes the hook immediately for test purposes
        doAnswer(invocation -> {
            AtmosphereConfig.StartupHook hook = invocation.getArgument(0);
            hook.started(framework);
            return config;
        }).when(config).startupHook(any(AtmosphereConfig.StartupHook.class));
    }

    @Test
    void constructorWithFactoryLooksUpBroadcaster() {
        when(broadcasterFactory.lookup(anyString(), anyBoolean())).thenReturn(broadcaster);

        var wrapper = new AtmosphereHandlerWrapper(broadcasterFactory, handler, "/test", config);

        assertSame(broadcaster, wrapper.broadcaster());
        assertSame(handler, wrapper.atmosphereHandler());
    }

    @Test
    void constructorWithFactoryWildcardMapping() {
        when(broadcasterFactory.lookup(anyString(), anyBoolean())).thenReturn(broadcaster);

        var wrapper = new AtmosphereHandlerWrapper(broadcasterFactory, handler, "/chat/{id}", config);

        assertSame(broadcaster, wrapper.broadcaster());
        assertEquals(true, wrapper.wildcardMapping());
    }

    @Test
    void constructorWithFactoryPlainMappingNotWildcard() {
        when(broadcasterFactory.lookup(anyString(), anyBoolean())).thenReturn(broadcaster);

        var wrapper = new AtmosphereHandlerWrapper(broadcasterFactory, handler, "/plain", config);

        assertFalse(wrapper.wildcardMapping());
    }

    @Test
    void constructorWithNullFactoryStoresMapping() {
        var wrapper = new AtmosphereHandlerWrapper(null, handler, "/fallback", config);

        assertNull(wrapper.broadcaster());
        assertEquals("/fallback", wrapper.mapping());
    }

    @Test
    void constructorWithDirectBroadcaster() {
        var wrapper = new AtmosphereHandlerWrapper(handler, broadcaster, config);

        assertSame(handler, wrapper.atmosphereHandler());
        assertSame(broadcaster, wrapper.broadcaster());
        assertFalse(wrapper.wildcardMapping());
    }

    @Test
    void interceptorsInitiallyEmptyAndModifiable() {
        var wrapper = new AtmosphereHandlerWrapper(handler, broadcaster, config);

        assertNotNull(wrapper.interceptors());
        assertEquals(0, wrapper.interceptors().size());

        var interceptor = mock(AtmosphereInterceptor.class);
        wrapper.interceptors().add(interceptor);
        assertEquals(1, wrapper.interceptors().size());
        assertSame(interceptor, wrapper.interceptors().getFirst());
    }

    @Test
    void setBroadcasterUpdatesBroadcaster() {
        var wrapper = new AtmosphereHandlerWrapper(handler, broadcaster, config);
        var newBroadcaster = mock(Broadcaster.class);

        wrapper.setBroadcaster(newBroadcaster);

        assertSame(newBroadcaster, wrapper.broadcaster());
    }

    @Test
    void toStringContainsHandlerInfo() {
        when(handler.toString()).thenReturn("TestHandler");

        var wrapper = new AtmosphereHandlerWrapper(handler, broadcaster, config);
        String result = wrapper.toString();

        assertNotNull(result);
        assertEquals(true, result.contains("TestHandler"));
        assertEquals(true, result.contains("atmosphereHandler"));
    }

    @Test
    void constructorWithFactoryThrowingExceptionWrapsInRuntime() {
        when(broadcasterFactory.lookup(anyString(), anyBoolean()))
                .thenThrow(new RuntimeException("lookup failed"));

        assertThrows(RuntimeException.class, () ->
                new AtmosphereHandlerWrapper(broadcasterFactory, handler, "/bad", config));
    }
}
