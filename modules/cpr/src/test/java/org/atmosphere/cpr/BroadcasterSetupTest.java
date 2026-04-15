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

import jakarta.servlet.ServletConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class BroadcasterSetupTest {

    private BroadcasterSetup setup;
    private AtmosphereConfig config;

    @BeforeEach
    void setUp() {
        config = new AtmosphereConfig(new AtmosphereFramework());
        setup = new BroadcasterSetup(config);
    }

    // --- Default state ---

    @Test
    void defaultBroadcasterClassName() {
        assertEquals(DefaultBroadcaster.class.getName(), setup.broadcasterClassName());
    }

    @Test
    void defaultBroadcasterSpecifiedIsFalse() {
        assertFalse(setup.isBroadcasterSpecified());
    }

    @Test
    void defaultBroadcasterLifeCyclePolicy() {
        assertEquals("NEVER", setup.broadcasterLifeCyclePolicy());
    }

    @Test
    void defaultCollectionsAreEmpty() {
        assertTrue(setup.broadcasterFilters().isEmpty());
        assertTrue(setup.broadcasterTypes().isEmpty());
        assertTrue(setup.inspectors().isEmpty());
        assertTrue(setup.broadcasterListeners().isEmpty());
        assertTrue(setup.broadcasterCacheListeners().isEmpty());
        assertTrue(setup.filterManipulators().isEmpty());
    }

    @Test
    void defaultFactoriesAreNull() {
        assertNull(setup.broadcasterFactory());
        assertNull(setup.arFactory());
        assertNull(setup.metaBroadcaster());
        assertNull(setup.getSessionFactory());
    }

    // --- Setters ---

    @Test
    void setBroadcasterClassName() {
        setup.setBroadcasterClassName("com.example.MyBroadcaster");
        assertEquals("com.example.MyBroadcaster", setup.broadcasterClassName());
    }

    @Test
    void setBroadcasterSpecified() {
        setup.setBroadcasterSpecified(true);
        assertTrue(setup.isBroadcasterSpecified());
    }

    @Test
    void setBroadcasterLifeCyclePolicy() {
        setup.setBroadcasterLifeCyclePolicy("IDLE_DESTROY");
        assertEquals("IDLE_DESTROY", setup.broadcasterLifeCyclePolicy());
    }

    @Test
    void setBroadcasterCacheClassName() {
        setup.setBroadcasterCacheClassName("com.example.MyCache");
        assertEquals("com.example.MyCache", setup.broadcasterCacheClassName());
    }

    @Test
    void setDefaultSerializerClassName() {
        setup.setDefaultSerializerClassName("com.example.MySerializer");
        assertEquals("com.example.MySerializer", setup.defaultSerializerClassName());
    }

    @Test
    void setBroadcasterFactory() {
        BroadcasterFactory factory = Mockito.mock(BroadcasterFactory.class);
        setup.setBroadcasterFactory(factory);
        assertSame(factory, setup.broadcasterFactory());
    }

    @Test
    void setArFactory() {
        AtmosphereResourceFactory factory = Mockito.mock(AtmosphereResourceFactory.class);
        setup.setArFactory(factory);
        assertSame(factory, setup.arFactory());
    }

    // --- parseInitParams ---

    @Test
    void parseInitParamsBroadcasterClass() {
        ServletConfig sc = Mockito.mock(ServletConfig.class);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CLASS)).thenReturn("com.example.CustomBroadcaster");
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CACHE)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCAST_FILTER_CLASSES)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_FACTORY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.DEFAULT_SERIALIZER)).thenReturn(null);

        setup.parseInitParams(sc);

        assertEquals("com.example.CustomBroadcaster", setup.broadcasterClassName());
        assertTrue(setup.isBroadcasterSpecified());
    }

    @Test
    void parseInitParamsBroadcasterCache() {
        ServletConfig sc = Mockito.mock(ServletConfig.class);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CLASS)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CACHE)).thenReturn("com.example.MyCache");
        when(sc.getInitParameter(ApplicationConfig.BROADCAST_FILTER_CLASSES)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_FACTORY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.DEFAULT_SERIALIZER)).thenReturn(null);

        setup.parseInitParams(sc);

        assertEquals("com.example.MyCache", setup.broadcasterCacheClassName());
    }

    @Test
    void parseInitParamsBroadcastFilters() {
        ServletConfig sc = Mockito.mock(ServletConfig.class);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CLASS)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CACHE)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCAST_FILTER_CLASSES)).thenReturn("filter1,filter2,filter3");
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_FACTORY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.DEFAULT_SERIALIZER)).thenReturn(null);

        setup.parseInitParams(sc);

        assertEquals(3, setup.broadcasterFilters().size());
        assertEquals("filter1", setup.broadcasterFilters().get(0));
        assertEquals("filter2", setup.broadcasterFilters().get(1));
        assertEquals("filter3", setup.broadcasterFilters().get(2));
    }

    @Test
    void parseInitParamsLifeCyclePolicy() {
        ServletConfig sc = Mockito.mock(ServletConfig.class);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CLASS)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CACHE)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCAST_FILTER_CLASSES)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY)).thenReturn("IDLE_DESTROY");
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_FACTORY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.DEFAULT_SERIALIZER)).thenReturn(null);

        setup.parseInitParams(sc);

        assertEquals("IDLE_DESTROY", setup.broadcasterLifeCyclePolicy());
    }

    @Test
    void parseInitParamsDefaultSerializer() {
        ServletConfig sc = Mockito.mock(ServletConfig.class);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CLASS)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CACHE)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCAST_FILTER_CLASSES)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_FACTORY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.DEFAULT_SERIALIZER)).thenReturn("com.example.MySerializer");

        setup.parseInitParams(sc);

        assertEquals("com.example.MySerializer", setup.defaultSerializerClassName());
    }

    @Test
    void parseInitParamsWithNullValues() {
        ServletConfig sc = Mockito.mock(ServletConfig.class);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CLASS)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_CACHE)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCAST_FILTER_CLASSES)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.BROADCASTER_FACTORY)).thenReturn(null);
        when(sc.getInitParameter(ApplicationConfig.DEFAULT_SERIALIZER)).thenReturn(null);

        setup.parseInitParams(sc);

        // Defaults should remain
        assertEquals(DefaultBroadcaster.class.getName(), setup.broadcasterClassName());
        assertFalse(setup.isBroadcasterSpecified());
        assertEquals("NEVER", setup.broadcasterLifeCyclePolicy());
    }

    // --- populateBroadcasterType ---

    @Test
    void populateBroadcasterTypeAddsTypes() {
        setup.populateBroadcasterType();

        assertEquals(8, setup.broadcasterTypes().size());
        assertTrue(setup.broadcasterTypes().contains(FrameworkConfig.KAFKA_BROADCASTER));
        assertTrue(setup.broadcasterTypes().contains(FrameworkConfig.REDIS_BROADCASTER));
        assertTrue(setup.broadcasterTypes().contains(FrameworkConfig.HAZELCAST_BROADCASTER));
    }

    // --- lookupDefaultBroadcasterType ---

    @Test
    void lookupDefaultBroadcasterTypeReturnsDefaultWhenNoTypesFound() {
        setup.populateBroadcasterType();
        String result = setup.lookupDefaultBroadcasterType("com.example.Fallback");
        // None of the broadcaster types (kafka, hazelcast, etc.) are on classpath
        assertEquals("com.example.Fallback", result);
    }

    // --- clear ---

    @Test
    void clearResetsAllState() {
        setup.setBroadcasterFactory(Mockito.mock(BroadcasterFactory.class));
        setup.setArFactory(Mockito.mock(AtmosphereResourceFactory.class));
        setup.broadcasterFilters().add("filter1");
        setup.broadcasterListeners().add(Mockito.mock(BroadcasterListener.class));
        setup.populateBroadcasterType();

        setup.clear();

        assertTrue(setup.broadcasterFilters().isEmpty());
        assertTrue(setup.broadcasterTypes().isEmpty());
        assertTrue(setup.inspectors().isEmpty());
        assertTrue(setup.broadcasterListeners().isEmpty());
        assertTrue(setup.broadcasterCacheListeners().isEmpty());
        assertTrue(setup.filterManipulators().isEmpty());
        assertNull(setup.broadcasterFactory());
        assertNull(setup.arFactory());
        assertNull(setup.metaBroadcaster());
        assertNull(setup.getSessionFactory());
    }

    // --- destroyFactories ---

    @Test
    void destroyFactoriesCallsDestroyOnAll() {
        BroadcasterFactory bf = Mockito.mock(BroadcasterFactory.class);
        AtmosphereResourceFactory rf = Mockito.mock(AtmosphereResourceFactory.class);
        setup.setBroadcasterFactory(bf);
        setup.setArFactory(rf);

        setup.destroyFactories();

        Mockito.verify(bf).destroy();
        Mockito.verify(rf).destroy();
    }

    @Test
    void destroyFactoriesHandlesNullFactories() {
        // Should not throw with null factories
        setup.destroyFactories();
    }

    // --- initDefaultSerializer ---

    @Test
    void initDefaultSerializerWithNullClassName() {
        setup.setDefaultSerializerClassName(null);
        setup.initDefaultSerializer();

        assertNull(setup.defaultSerializerClassName());
        assertNull(setup.defaultSerializerClass());
    }

    @Test
    void initDefaultSerializerWithEmptyClassName() {
        setup.setDefaultSerializerClassName("");
        setup.initDefaultSerializer();

        assertNull(setup.defaultSerializerClassName());
        assertNull(setup.defaultSerializerClass());
    }

    @Test
    void initDefaultSerializerWithInvalidClass() {
        setup.setDefaultSerializerClassName("com.nonexistent.Serializer");
        setup.initDefaultSerializer();

        // On error, both are reset to null
        assertNull(setup.defaultSerializerClassName());
        assertNull(setup.defaultSerializerClass());
    }

    // --- autodetectBroadcaster ---

    @Test
    void autodetectBroadcasterDefaultsToTrue() {
        assertTrue(setup.autodetectBroadcaster());
    }
}
