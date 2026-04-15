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
package org.atmosphere.annotation;

import org.atmosphere.client.TrackMessageSizeInterceptor;
import org.atmosphere.config.managed.ManagedServiceInterceptor;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor;
import org.atmosphere.interceptor.SuspendTrackerInterceptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnotationUtilTest {

    @Test
    void checkDefaultReturnsFalseForManagedInterceptors() {
        assertFalse(AnnotationUtil.checkDefault(AtmosphereResourceLifecycleInterceptor.class));
        assertFalse(AnnotationUtil.checkDefault(TrackMessageSizeInterceptor.class));
        assertFalse(AnnotationUtil.checkDefault(SuspendTrackerInterceptor.class));
        assertFalse(AnnotationUtil.checkDefault(ManagedServiceInterceptor.class));
    }

    @Test
    void checkDefaultReturnsFalseForFrameworkDefaults() {
        for (Class<? extends AtmosphereInterceptor> defaultInterceptor
                : AtmosphereFramework.DEFAULT_ATMOSPHERE_INTERCEPTORS) {
            assertFalse(AnnotationUtil.checkDefault(defaultInterceptor),
                    "Expected false for framework default: " + defaultInterceptor.getName());
        }
    }

    @Test
    void checkDefaultReturnsTrueForCustomInterceptor() {
        assertTrue(AnnotationUtil.checkDefault(CustomTestInterceptor.class));
    }

    @Test
    void listenersReturnsNullForEmptyArray() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends AtmosphereResourceEventListener>[] empty = new Class[0];
        var framework = mock(AtmosphereFramework.class);
        assertNull(AnnotationUtil.listeners(empty, framework));
    }

    @Test
    void listenersReturnsInterceptorForNonEmptyArray() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends AtmosphereResourceEventListener>[] arr = new Class[]{TestEventListener.class};
        var framework = mock(AtmosphereFramework.class);

        AtmosphereInterceptor interceptor = AnnotationUtil.listeners(arr, framework);
        assertNotNull(interceptor);
        assertEquals("@Service Event Listeners", interceptor.toString());
    }

    @Test
    void listenersInterceptorReturnsContinue() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends AtmosphereResourceEventListener>[] arr = new Class[]{TestEventListener.class};
        var framework = mock(AtmosphereFramework.class);

        AtmosphereInterceptor interceptor = AnnotationUtil.listeners(arr, framework);
        assertNotNull(interceptor);

        var resource = mock(AtmosphereResource.class);
        when(resource.isSuspended()).thenReturn(false);
        when(framework.newClassInstance(AtmosphereResourceEventListener.class, TestEventListener.class))
                .thenReturn(new TestEventListener());

        Action action = interceptor.inspect(resource);
        assertEquals(Action.CONTINUE, action);
        verify(resource).addEventListener(org.mockito.ArgumentMatchers.any(TestEventListener.class));
    }

    @Test
    void listenersInterceptorSkipsAddWhenSuspended() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends AtmosphereResourceEventListener>[] arr = new Class[]{TestEventListener.class};
        var framework = mock(AtmosphereFramework.class);

        AtmosphereInterceptor interceptor = AnnotationUtil.listeners(arr, framework);
        assertNotNull(interceptor);

        var resource = mock(AtmosphereResource.class);
        when(resource.isSuspended()).thenReturn(true);

        Action action = interceptor.inspect(resource);
        assertEquals(Action.CONTINUE, action);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void interceptorsRegistersWithFramework() throws Exception {
        var framework = mock(AtmosphereFramework.class);
        var interceptor = mock(AtmosphereInterceptor.class);
        when(framework.newClassInstance(AtmosphereInterceptor.class, CustomTestInterceptor.class))
                .thenReturn(interceptor);

        Class<? extends AtmosphereInterceptor>[] arr = new Class[]{CustomTestInterceptor.class};
        AnnotationUtil.interceptors(arr, framework);

        verify(framework).interceptor(interceptor);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void interceptorsHandlesEmptyArray() {
        var framework = mock(AtmosphereFramework.class);
        Class<? extends AtmosphereInterceptor>[] arr = new Class[0];
        AnnotationUtil.interceptors(arr, framework);
        // Should not throw - no interactions expected
    }

    @Test
    void atmosphereConfigSetsInitParams() {
        var framework = mock(AtmosphereFramework.class);
        String[] configs = {"key1=value1", "key2=value2"};
        AnnotationUtil.atmosphereConfig(configs, framework);

        verify(framework).addInitParameter("key1", "value1");
        verify(framework).addInitParameter("key2", "value2");
        verify(framework, org.mockito.Mockito.times(2)).reconfigureInitParams(true);
    }

    @Test
    void atmosphereConfigHandlesEmptyArray() {
        var framework = mock(AtmosphereFramework.class);
        AnnotationUtil.atmosphereConfig(new String[0], framework);
        // Should not throw
    }

    @Test
    void interceptorsForManagedServiceSkipsExcluded() throws Exception {
        var framework = mock(AtmosphereFramework.class);
        when(framework.excludedInterceptors()).thenReturn(List.of(CustomTestInterceptor.class.getName()));

        List<AtmosphereInterceptor> result = new ArrayList<>();
        AnnotationUtil.interceptorsForManagedService(
                framework, List.of(CustomTestInterceptor.class), result);

        assertEquals(0, result.size());
    }

    @Test
    void interceptorsForManagedServiceAddsNonExcluded() throws Exception {
        var framework = mock(AtmosphereFramework.class);
        when(framework.excludedInterceptors()).thenReturn(List.of());
        var interceptor = new CustomTestInterceptor();
        when(framework.newClassInstance(AtmosphereInterceptor.class, CustomTestInterceptor.class))
                .thenReturn(interceptor);

        List<AtmosphereInterceptor> result = new ArrayList<>();
        AnnotationUtil.interceptorsForManagedService(
                framework, List.of(CustomTestInterceptor.class), result);

        assertEquals(1, result.size());
    }

    // Test helpers

    public static class CustomTestInterceptor extends AtmosphereInterceptorAdapter {
        @Override
        public Action inspect(AtmosphereResource r) {
            return Action.CONTINUE;
        }
    }

    public static class TestEventListener implements AtmosphereResourceEventListener {
        @Override
        public void onSuspend(org.atmosphere.cpr.AtmosphereResourceEvent event) { }

        @Override
        public void onResume(org.atmosphere.cpr.AtmosphereResourceEvent event) { }

        @Override
        public void onDisconnect(org.atmosphere.cpr.AtmosphereResourceEvent event) { }

        @Override
        public void onBroadcast(org.atmosphere.cpr.AtmosphereResourceEvent event) { }

        @Override
        public void onThrowable(org.atmosphere.cpr.AtmosphereResourceEvent event) { }

        @Override
        public void onHeartbeat(org.atmosphere.cpr.AtmosphereResourceEvent event) { }

        @Override
        public void onClose(org.atmosphere.cpr.AtmosphereResourceEvent event) { }

        @Override
        public void onPreSuspend(org.atmosphere.cpr.AtmosphereResourceEvent event) { }
    }
}
