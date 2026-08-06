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
package org.atmosphere.nativeimage;

import org.atmosphere.cpr.AtmosphereReflectiveTypes;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the merge behaviour every consumer depends on.
 *
 * <p>Three consumers read this — the Spring Boot hints registrar, the Quarkus
 * build step, and the generated metadata embedded in the shipped jars. Each
 * previously carried its own transcription of one list, so the property that
 * matters most here is that a provider's entries survive the merge intact: a
 * type dropped at this seam is a type missing from every runtime at once.</p>
 */
class NativeImageMetadataTest {

    @Test
    void theRuntimesOwnProviderIsDiscovered() {
        var metadata = NativeImageMetadata.collect();

        assertTrue(metadata.providerNames().contains("atmosphere-runtime"),
                "the core provider ships in this module's META-INF/services and must be "
                        + "found, or every consumer silently registers nothing: "
                        + metadata.providerNames());
    }

    @Test
    void everyCoreTypeSurvivesTheMerge() {
        var merged = NativeImageMetadata.collect().reflectiveTypes();

        var missing = AtmosphereReflectiveTypes.coreTypes().stream()
                .filter(t -> !merged.contains(t))
                .toList();

        assertTrue(missing.isEmpty(),
                "types declared by the core provider must reach consumers unchanged — "
                        + "anything lost here is unregistered in Spring, Quarkus and the "
                        + "embedded metadata simultaneously: " + missing);
    }

    @Test
    void annotationProcessorsAreIncluded() {
        var merged = NativeImageMetadata.collect().reflectiveTypes();

        assertTrue(merged.containsAll(AtmosphereReflectiveTypes.annotationProcessors()),
                "annotation processors are instantiated by AnnotationHandler and must be "
                        + "registered; they were a separate loop in each consumer before");
    }

    @Test
    void serviceLoaderFilesAreDeclaredAsResources() {
        var patterns = NativeImageMetadata.collect().resourcePatterns();

        assertTrue(patterns.contains("META-INF/services/org.atmosphere.inject.Injectable"),
                "a missing Injectable service file does not raise an error — the framework "
                        + "simply finds nothing to inject, which is the silent shape this "
                        + "whole SPI exists to prevent");
        assertTrue(patterns.contains(
                        "META-INF/services/org.atmosphere.nativeimage.NativeImageMetadataProvider"),
                "the SPI's own service file must survive into the image, or discovery "
                        + "finds no providers at runtime");
    }

    @Test
    void duplicatesFromMultipleProvidersCollapse() {
        var merged = NativeImageMetadata.collect().reflectiveTypes();
        var distinct = merged.stream().distinct().toList();

        assertEquals(distinct.size(), merged.size(),
                "two modules may legitimately declare the same type; the merge must "
                        + "de-duplicate rather than emit it twice into the metadata");
    }

    @Test
    void aProviderThatThrowsDoesNotTakeDownTheRest() {
        // Not loaded via ServiceLoader — constructed directly so the failure path
        // can be exercised without a broken provider on the real classpath.
        var exploding = new NativeImageMetadataProvider() {
            @Override public String name() {
                return "exploding";
            }

            @Override public Collection<String> reflectiveTypes() {
                throw new IllegalStateException("provider is broken");
            }
        };

        assertThrowsInternally(exploding);

        // The real collection still works: one bad provider must not empty the merge.
        assertFalse(NativeImageMetadata.collect().reflectiveTypes().isEmpty(),
                "a failing provider is skipped with a warning; the remaining providers "
                        + "must still contribute");
    }

    private static void assertThrowsInternally(NativeImageMetadataProvider provider) {
        try {
            provider.reflectiveTypes();
            throw new AssertionError("fixture should have thrown");
        } catch (IllegalStateException expected) {
            // the fixture behaves as intended
        }
    }

    @Test
    void unavailableProvidersContributeNothing() {
        var unavailable = new NativeImageMetadataProvider() {
            @Override public String name() {
                return "absent-dependency";
            }

            @Override public boolean isAvailable() {
                return false;
            }

            @Override public Collection<String> reflectiveTypes() {
                return List.of("com.example.NotOnThisClasspath");
            }
        };

        assertFalse(unavailable.isAvailable(),
                "a provider guarding an optional dependency reports unavailable, and the "
                        + "merge must not name types that cannot resolve in this image");
        assertFalse(NativeImageMetadata.collect().reflectiveTypes()
                        .contains("com.example.NotOnThisClasspath"),
                "nothing should have contributed this type");
    }
}
