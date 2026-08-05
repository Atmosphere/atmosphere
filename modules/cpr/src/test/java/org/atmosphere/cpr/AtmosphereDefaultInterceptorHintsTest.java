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

import org.atmosphere.annotation.AnnotationUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link AtmosphereReflectiveTypes} against the interceptor lists the
 * framework actually instantiates.
 *
 * <p>Every interceptor reaches the runtime through
 * {@code AtmosphereFramework.newClassInstance}, so under GraalVM each one needs
 * a reflection hint. The registry supplying those hints was maintained by hand
 * and had drifted: the four managed-service interceptors were instantiated for
 * every {@code @ManagedService} endpoint but only one of them was registered.</p>
 *
 * <p>What made the drift survive is that the failure is silent.
 * {@code interceptorsForManagedService} catches {@code Throwable}, logs, and
 * continues, so an unregistered interceptor produces a healthy-looking server
 * with a missing endpoint — an HTTP liveness probe passes, and nothing on the
 * JVM ever notices because full reflection makes every hint redundant there.</p>
 *
 * <p>Comparing the registry against the framework's own lists is what keeps
 * this honest: adding an interceptor to either list without registering it
 * fails here rather than in a native image weeks later.</p>
 */
class AtmosphereDefaultInterceptorHintsTest {

    private static List<Class<?>> reflectivelyInstantiatedInterceptors() {
        var all = new ArrayList<Class<?>>(InterceptorRegistry.DEFAULT_ATMOSPHERE_INTERCEPTORS);
        all.addAll(AnnotationUtil.managedServiceInterceptors());
        return all;
    }

    @Test
    void bothInterceptorListsAreNonEmpty() {
        assertFalse(InterceptorRegistry.DEFAULT_ATMOSPHERE_INTERCEPTORS.isEmpty(),
                "an empty default chain would make this test pass vacuously");
        assertFalse(AnnotationUtil.managedServiceInterceptors().isEmpty(),
                "an empty managed-service list would make this test pass vacuously");
    }

    @Test
    void everyReflectivelyInstantiatedInterceptorIsRegisteredForNativeImage() {
        var registered = AtmosphereReflectiveTypes.coreTypes();

        var missing = new ArrayList<String>();
        for (var interceptor : reflectivelyInstantiatedInterceptors()) {
            if (!registered.contains(interceptor.getName())) {
                missing.add(interceptor.getName());
            }
        }

        assertTrue(missing.isEmpty(),
                "these interceptors are constructed via newClassInstance but carry no "
                        + "reflection hint, so a native image cannot instantiate them — and "
                        + "because the instantiation failure is caught and logged, the only "
                        + "symptom is an endpoint that never registers: " + missing);
    }

    @Test
    void theManagedServiceInterceptorsAreTheOnesTheEndpointPathNeeds() {
        // Narrower than the assertion above, and deliberately so: these four are
        // what a @ManagedService endpoint depends on, and three of them were the
        // actual native-image regression. Named explicitly so a future edit to
        // AnnotationUtil that drops one has to be a conscious change here too.
        var managed = AnnotationUtil.managedServiceInterceptors().stream()
                .map(Class::getName)
                .toList();

        assertTrue(managed.contains("org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor"),
                "the lifecycle interceptor drives suspend/resume for managed endpoints");
        assertTrue(managed.contains("org.atmosphere.config.managed.ManagedServiceInterceptor"),
                "the managed-service interceptor is what dispatches to @ManagedService methods");

        for (var name : managed) {
            assertTrue(AtmosphereReflectiveTypes.coreTypes().contains(name),
                    name + " is installed on every managed endpoint and must be reflectively "
                            + "constructible in a native image");
        }
    }
}
