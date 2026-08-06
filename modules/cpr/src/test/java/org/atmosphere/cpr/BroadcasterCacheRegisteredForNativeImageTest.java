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

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins every instantiable {@link BroadcasterCache} into the native-image type
 * registry.
 *
 * <p>A cache is selected by name through the {@code broadcasterCacheClass}
 * init-param and constructed with {@code IOUtils.loadClass}, so a GraalVM image
 * that has not registered the class cannot build one. Every {@code
 * @ManagedService} endpoint needs a {@code Broadcaster}, and a broadcaster
 * cannot be created without its cache — so a single missing registration takes
 * out every annotated endpoint in the application.</p>
 *
 * <p>It does so silently, which is why this went unnoticed:
 * {@code createBroadcaster} wraps the failure in a
 * {@code BroadcasterCreationException}, {@code ManagedServiceProcessor} catches
 * {@code Throwable} and calls {@code logger.warn("", e)}, and startup continues
 * with no endpoint registered. The server answers a liveness probe perfectly
 * while serving nothing.</p>
 *
 * <p>The walk is over the source directory rather than a hand-written list: a
 * new cache implementation should fail this test on the day it is added, not
 * the day someone tries it in a native image.</p>
 */
class BroadcasterCacheRegisteredForNativeImageTest {

    private static final String CACHE_PACKAGE = "org.atmosphere.cache";

    /** Concrete, instantiable caches found by walking the source tree. */
    private static List<Class<?>> instantiableCaches() throws Exception {
        var dir = new File("src/main/java/org/atmosphere/cache");
        assertTrue(dir.isDirectory(),
                "expected the cache sources at " + dir.getAbsolutePath()
                        + " — if they moved, fix this walk rather than deleting the test");

        var found = new ArrayList<Class<?>>();
        var files = dir.listFiles((d, name) -> name.endsWith(".java"));
        if (files == null) {
            return found;
        }
        for (var file : files) {
            var simpleName = file.getName().substring(0, file.getName().length() - ".java".length());
            Class<?> clazz;
            try {
                clazz = Class.forName(CACHE_PACKAGE + "." + simpleName);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                // A package-private helper compiled into another file's class.
                continue;
            }
            if (!BroadcasterCache.class.isAssignableFrom(clazz)) {
                continue;
            }
            if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            found.add(clazz);
        }
        return found;
    }

    @Test
    void thereAreCachesToCheck() throws Exception {
        assertFalse(instantiableCaches().isEmpty(),
                "finding no cache implementations would make this test pass vacuously — "
                        + "the walk is broken, not the codebase");
    }

    @Test
    void everyInstantiableBroadcasterCacheIsRegisteredForNativeImage() throws Exception {
        var registered = AtmosphereReflectiveTypes.coreTypes();

        var missing = new ArrayList<String>();
        for (var cache : instantiableCaches()) {
            if (!registered.contains(cache.getName())) {
                missing.add(cache.getName());
            }
        }

        assertTrue(missing.isEmpty(),
                "these caches are loaded by name and so cannot be constructed in a native "
                        + "image without a reflection hint. Because the failure is caught and "
                        + "logged rather than raised, the symptom is not an error — it is every "
                        + "@ManagedService endpoint silently failing to register: " + missing);
    }

    @Test
    void theDefaultCacheUsedByTheQuarkusExtensionIsRegistered() {
        // Narrower and deliberately explicit: UUIDBroadcasterCache is what
        // cache-enabled deployments select, and its absence is the specific
        // failure observed in the Quarkus native image.
        assertTrue(AtmosphereReflectiveTypes.coreTypes()
                        .contains("org.atmosphere.cache.UUIDBroadcasterCache"),
                "UUIDBroadcasterCache is the cache a cache-enabled deployment selects; "
                        + "without it createBroadcaster throws ClassNotFoundException and no "
                        + "annotated endpoint registers");
    }
}
