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
package org.atmosphere.spring.boot;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers reading the class list recorded during AOT processing.
 *
 * <p>The distinction that matters here is <em>absent</em> versus <em>present
 * but empty</em>. Absent means no build-time scan ran, so the runtime should
 * scan for itself — the ordinary JVM path. Empty means a build-time scan ran
 * and genuinely found nothing, and falling back to a scan there would be
 * pointless in the one environment (a native image) where the file exists and
 * scanning cannot work.</p>
 */
class AtmosphereAnnotationScannerTest {

    /** Class loader whose only extra entry is a directory we control. */
    private static ClassLoader loaderOver(Path dir) throws Exception {
        return new URLClassLoader(new URL[]{dir.toUri().toURL()},
                AtmosphereAnnotationScannerTest.class.getClassLoader());
    }

    private static void writeResource(Path dir, String body) throws Exception {
        var target = dir.resolve(AtmosphereAnnotationScanner.PRECOMPUTED_RESOURCE);
        Files.createDirectories(target.getParent());
        Files.writeString(target, body, StandardCharsets.UTF_8);
    }

    @Test
    void anAbsentFileMeansNoBuildTimeScanRan() throws Exception {
        var dir = Files.createTempDirectory("atmo-scan-absent");
        try {
            var result = AtmosphereAnnotationScanner.readPrecomputed(loaderOver(dir));
            assertTrue(result.isEmpty(),
                    "with no recorded list the caller must fall back to scanning — "
                            + "this is the ordinary JVM path and must not change");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void recordedClassesAreLoaded() throws Exception {
        var dir = Files.createTempDirectory("atmo-scan-ok");
        try {
            writeResource(dir, """
                    # a comment is ignored
                    org.atmosphere.spring.boot.DefaultAiChatEndpoint

                    org.atmosphere.spring.boot.AtmosphereAutoConfiguration
                    """);

            var result = AtmosphereAnnotationScanner.readPrecomputed(loaderOver(dir));
            assertTrue(result.isPresent());
            assertEquals(2, result.get().size(),
                    "comments and blank lines must not become phantom entries");
            assertTrue(result.get().contains(DefaultAiChatEndpoint.class));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void anEmptyFileIsPresentAndEmptyRatherThanAbsent() throws Exception {
        var dir = Files.createTempDirectory("atmo-scan-empty");
        try {
            writeResource(dir, "# scan ran, found nothing\n");

            var result = AtmosphereAnnotationScanner.readPrecomputed(loaderOver(dir));
            assertTrue(result.isPresent(),
                    "a build-time scan that found nothing is an answer, not a missing "
                            + "answer — reporting it as absent would send a native image "
                            + "back to a classpath scan that cannot work there");
            assertTrue(result.get().isEmpty());
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void aClassMissingAtRuntimeIsSkippedRatherThanFatal() throws Exception {
        var dir = Files.createTempDirectory("atmo-scan-missing");
        try {
            writeResource(dir, """
                    org.atmosphere.spring.boot.DefaultAiChatEndpoint
                    com.example.RemovedOptionalModule
                    """);

            var result = AtmosphereAnnotationScanner.readPrecomputed(loaderOver(dir));
            assertTrue(result.isPresent());
            assertEquals(1, result.get().size(),
                    "an optional module present at build time but absent at run time must "
                            + "not take down every other endpoint");
            assertTrue(result.get().contains(DefaultAiChatEndpoint.class));
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void scanningIsOrderedSoTheGeneratedFileIsReproducible() {
        var first = AtmosphereAnnotationScanner.scan(null);
        var second = AtmosphereAnnotationScanner.scan(null);

        assertFalse(first.isEmpty(),
                "the framework's own annotation processors live under org.atmosphere and "
                        + "must always be found, or this assertion is vacuous");
        assertEquals(first.stream().map(Class::getName).toList(),
                second.stream().map(Class::getName).toList(),
                "the scan feeds a file baked into a native image; unstable ordering would "
                        + "make otherwise-identical builds differ");
    }

    @Test
    void theAnnotationMapGroupsEachClassUnderEveryAnnotationItCarries() {
        // Keyed by *class-level* annotations, matching what the framework consumes.
        // ManagedServiceProcessor carries @AtmosphereAnnotation on the type, which
        // is the shape that matters; a class whose annotations sit on methods
        // contributes no keys, and that is the pre-existing behaviour.
        var processor = org.atmosphere.annotation.ManagedServiceProcessor.class;
        var map = AtmosphereAnnotationScanner.toAnnotationMap(java.util.Set.of(processor));

        assertFalse(map.isEmpty(),
                "the framework consumes this map keyed by annotation type; an empty map "
                        + "would register nothing");
        assertTrue(map.containsKey(org.atmosphere.config.AtmosphereAnnotation.class),
                "@AtmosphereAnnotation is how the framework locates its processors");
        for (var entry : map.entrySet()) {
            assertTrue(entry.getValue().contains(processor),
                    "every key must map back to the class that carried it");
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best-effort temp cleanup; a leftover temp dir must not fail the test
                }
            });
        }
    }
}
