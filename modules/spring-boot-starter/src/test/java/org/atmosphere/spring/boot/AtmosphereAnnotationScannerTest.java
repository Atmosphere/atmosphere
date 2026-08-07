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
 * <p>Indexes are per-artifact: each jar records only the annotated classes it
 * contains, and the runtime merges every one it finds. An earlier version read
 * a single resource and let it short-circuit the classpath scan, which meant one
 * jar's partial index silently hid the classes in every other jar — a test
 * fixture's two entries were enough to suppress the framework's own processors.
 * These tests pin the merge, because that failure was silent.</p>
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
    void indexesFromEveryJarAreMerged() throws Exception {
        var dir = Files.createTempDirectory("atmo-scan-merge");
        try {
            writeResource(dir, "org.atmosphere.spring.boot.DefaultAiChatEndpoint\n");

            var result = AtmosphereAnnotationScanner.readPrecomputed(loaderOver(dir));
            assertTrue(result.isPresent());

            // atmosphere-runtime ships its own index of the framework's annotation
            // processors, so the result is this fixture's entry *plus* those — not
            // this fixture's entry alone. Reading a single resource instead would
            // let one jar's index hide every other jar's, which is precisely the
            // regression that broke sixteen tests when the annotation processor
            // first started emitting per-artifact indexes.
            assertTrue(result.get().contains(DefaultAiChatEndpoint.class),
                    "the fixture's own entry must survive the merge");
            assertTrue(result.get().size() > 1,
                    "the framework's index must be merged in alongside it, not replaced by "
                            + "it; got only " + result.get().size() + " class(es)");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void theFrameworksOwnProcessorsAreAlwaysAvailable() {
        var result = AtmosphereAnnotationScanner.readPrecomputed(getClass().getClassLoader());

        assertTrue(result.isPresent(),
                "atmosphere-runtime always ships an index, because it is the one module "
                        + "whose classes a native image can never scan for");
        assertTrue(result.get().stream()
                        .anyMatch(c -> c.getName().startsWith("org.atmosphere.annotation.")),
                "the framework's annotation processors must be discoverable — without them "
                        + "no @ManagedService endpoint works at all in a native image");
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
            assertTrue(result.get().contains(DefaultAiChatEndpoint.class),
                    "an optional module present at build time but absent at run time must "
                            + "not take down every other endpoint");
            assertTrue(result.get().stream()
                            .noneMatch(c -> c.getName().equals("com.example.RemovedOptionalModule")),
                    "the unresolvable entry must simply be absent");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void commentsAndBlankLinesAreNotTreatedAsClassNames() throws Exception {
        var dir = Files.createTempDirectory("atmo-scan-comments");
        try {
            writeResource(dir, """
                    # a comment is ignored

                    org.atmosphere.spring.boot.DefaultAiChatEndpoint
                    """);

            var result = AtmosphereAnnotationScanner.readPrecomputed(loaderOver(dir));
            assertTrue(result.isPresent());
            assertTrue(result.get().contains(DefaultAiChatEndpoint.class));
            assertTrue(result.get().stream().allMatch(c -> !c.getName().isBlank()),
                    "a blank or commented line must never become a phantom entry");
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
