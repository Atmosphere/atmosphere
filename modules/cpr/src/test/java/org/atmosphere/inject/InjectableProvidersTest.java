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
package org.atmosphere.inject;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceSessionFactory;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.MetaBroadcaster;
import org.atmosphere.websocket.WebSocketFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InjectableProvidersTest {

    private final AtmosphereConfig config = mock(AtmosphereConfig.class);

    // --- AtmosphereConfigInjectable ---

    @Test
    void configInjectableSupportedType() {
        var injectable = new AtmosphereConfigInjectable();
        assertTrue(injectable.supportedType(AtmosphereConfig.class));
    }

    @Test
    void configInjectableRejectsOtherType() {
        var injectable = new AtmosphereConfigInjectable();
        assertFalse(injectable.supportedType(String.class));
    }

    @Test
    void configInjectableReturnsSameConfig() {
        var injectable = new AtmosphereConfigInjectable();
        assertSame(config, injectable.injectable(config));
    }

    // --- AtmosphereFrameworkInjectable ---

    @Test
    void frameworkInjectableSupportedType() {
        var injectable = new AtmosphereFrameworkInjectable();
        assertTrue(injectable.supportedType(AtmosphereFramework.class));
    }

    @Test
    void frameworkInjectableRejectsOtherType() {
        var injectable = new AtmosphereFrameworkInjectable();
        assertFalse(injectable.supportedType(String.class));
    }

    @Test
    void frameworkInjectableReturnsFramework() {
        var framework = mock(AtmosphereFramework.class);
        when(config.framework()).thenReturn(framework);

        var injectable = new AtmosphereFrameworkInjectable();
        assertSame(framework, injectable.injectable(config));
    }

    // --- BroadcasterFactoryInjectable ---

    @Test
    void broadcasterFactoryInjectableSupportedType() {
        var injectable = new BroadcasterFactoryInjectable();
        assertTrue(injectable.supportedType(BroadcasterFactory.class));
    }

    @Test
    void broadcasterFactoryInjectableRejectsOtherType() {
        var injectable = new BroadcasterFactoryInjectable();
        assertFalse(injectable.supportedType(String.class));
    }

    @Test
    void broadcasterFactoryInjectableReturnsFactory() {
        var factory = mock(BroadcasterFactory.class);
        when(config.getBroadcasterFactory()).thenReturn(factory);

        var injectable = new BroadcasterFactoryInjectable();
        assertSame(factory, injectable.injectable(config));
    }

    // --- AtmosphereResourceFactoryInjectable ---

    @Test
    void resourceFactoryInjectableSupportedType() {
        var injectable = new AtmosphereResourceFactoryInjectable();
        assertTrue(injectable.supportedType(AtmosphereResourceFactory.class));
    }

    @Test
    void resourceFactoryInjectableRejectsOtherType() {
        var injectable = new AtmosphereResourceFactoryInjectable();
        assertFalse(injectable.supportedType(Integer.class));
    }

    @Test
    void resourceFactoryInjectableReturnsFactory() {
        var factory = mock(AtmosphereResourceFactory.class);
        when(config.resourcesFactory()).thenReturn(factory);

        var injectable = new AtmosphereResourceFactoryInjectable();
        assertSame(factory, injectable.injectable(config));
    }

    // --- MetaBroadcasterInjectable ---

    @Test
    void metaBroadcasterInjectableSupportedType() {
        var injectable = new MetaBroadcasterInjectable();
        assertTrue(injectable.supportedType(MetaBroadcaster.class));
    }

    @Test
    void metaBroadcasterInjectableRejectsOtherType() {
        var injectable = new MetaBroadcasterInjectable();
        assertFalse(injectable.supportedType(Object.class));
    }

    @Test
    void metaBroadcasterInjectableReturnsInstance() {
        var meta = mock(MetaBroadcaster.class);
        when(config.metaBroadcaster()).thenReturn(meta);

        var injectable = new MetaBroadcasterInjectable();
        assertSame(meta, injectable.injectable(config));
    }

    // --- WebSocketFactoryInjectable ---

    @Test
    void webSocketFactoryInjectableSupportedType() {
        var injectable = new WebSocketFactoryInjectable();
        assertTrue(injectable.supportedType(WebSocketFactory.class));
    }

    @Test
    void webSocketFactoryInjectableRejectsOtherType() {
        var injectable = new WebSocketFactoryInjectable();
        assertFalse(injectable.supportedType(Runnable.class));
    }

    @Test
    void webSocketFactoryInjectableReturnsFactory() {
        var factory = mock(WebSocketFactory.class);
        when(config.websocketFactory()).thenReturn(factory);

        var injectable = new WebSocketFactoryInjectable();
        assertSame(factory, injectable.injectable(config));
    }

    // --- AtmosphereResourceSessionFactoryInjectable ---

    @Test
    void sessionFactoryInjectableSupportedType() {
        var injectable = new AtmosphereResourceSessionFactoryInjectable();
        assertTrue(injectable.supportedType(AtmosphereResourceSessionFactory.class));
    }

    @Test
    void sessionFactoryInjectableRejectsOtherType() {
        var injectable = new AtmosphereResourceSessionFactoryInjectable();
        assertFalse(injectable.supportedType(Comparable.class));
    }

    @Test
    void sessionFactoryInjectableReturnsFactory() {
        var factory = mock(AtmosphereResourceSessionFactory.class);
        when(config.sessionFactory()).thenReturn(factory);

        var injectable = new AtmosphereResourceSessionFactoryInjectable();
        assertSame(factory, injectable.injectable(config));
    }
}
