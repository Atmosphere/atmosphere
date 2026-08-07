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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the GraalVM metadata shipped inside this jar in step with the SPI.
 *
 * <p>{@code META-INF/native-image/<group>/<artifact>/reachability-metadata.json}
 * is read automatically by GraalVM from any jar on the classpath. Shipping it
 * here is what lets a deployment with no Spring starter and no Quarkus
 * extension — a plain servlet container, embedded Jetty — build a working
 * native image with nothing to configure.</p>
 *
 * <p>Because it is a committed file rather than something generated during the
 * build, it can drift from the providers it was generated from, and a stale
 * copy fails the way everything else in this area fails: silently, at runtime,
 * in an image nobody built locally. This test regenerates it and compares, so
 * the drift surfaces as a red unit test with the fix printed.</p>
 */
class EmbeddedReachabilityMetadataTest {

    private static final Path METADATA = Path.of(
            "src/main/resources/META-INF/native-image/org.atmosphere/"
                    + "atmosphere-runtime/reachability-metadata.json");

    /**
     * Fixed provenance line. It must not contain a version or a timestamp: the
     * file is committed, so anything that changes per build would make every
     * build dirty and train everyone to regenerate without reading the diff.
     */
    private static final String COMMENT =
            "Generated from Atmosphere's NativeImageMetadataProvider SPI. "
                    + "Regenerate with EmbeddedReachabilityMetadataTest.";

    @Test
    void theMetadataFileIsShipped() {
        assertTrue(Files.isRegularFile(METADATA),
                "expected the embedded metadata at " + METADATA.toAbsolutePath()
                        + " — without it, a deployment that uses neither the Spring starter "
                        + "nor the Quarkus extension gets no reflection registration at all");
    }

    @Test
    void theCommittedFileMatchesWhatTheProvidersDeclare() throws Exception {
        var expected = NativeImageMetadataWriter.render(NativeImageMetadata.collect(), COMMENT);
        var actual = Files.readString(METADATA, StandardCharsets.UTF_8);

        if (!expected.equals(actual)) {
            // Write the regenerated file next to the stale one so the fix is a
            // copy rather than a hand-edit.
            var suggestion = METADATA.resolveSibling("reachability-metadata.json.expected");
            Files.writeString(suggestion, expected, StandardCharsets.UTF_8);
        }

        assertEquals(expected, actual,
                "the shipped GraalVM metadata no longer matches the SPI providers. A type "
                        + "added to a provider but missing here is unregistered for every "
                        + "deployment that has no integration module. Regenerated copy "
                        + "written to reachability-metadata.json.expected — replace the "
                        + "committed file with it.");
    }

    @Test
    void theMetadataCoversTheTypesThatBrokeNativeImage() throws Exception {
        var content = Files.readString(METADATA, StandardCharsets.UTF_8);

        // Concrete regressions, named on purpose. UUIDBroadcasterCache is the
        // class whose absence silently unregistered every @ManagedService
        // endpoint; the Injectable service file is the resource whose absence
        // leaves injection finding nothing, equally quietly.
        assertTrue(content.contains("org.atmosphere.cache.UUIDBroadcasterCache"),
                "the broadcaster cache must be registered — its absence is what stopped "
                        + "every annotated endpoint from registering under GraalVM");
        assertTrue(content.contains("META-INF/services/org.atmosphere.inject.Injectable"),
                "the Injectable service file must survive into the image or injection "
                        + "silently finds nothing");
    }

    @Test
    void theFileIsValidJsonWithTheExpectedShape() throws Exception {
        var content = Files.readString(METADATA, StandardCharsets.UTF_8);

        assertTrue(content.startsWith("{"), "must be a JSON object");
        assertTrue(content.endsWith("}\n"), "must end with a newline for a clean diff");
        assertTrue(content.contains("\"reflection\""), "GraalVM reads the reflection array");
        assertTrue(content.contains("\"resources\""), "GraalVM reads the resources array");
        assertFalse(content.contains("\"type\": \"\""), "an empty type name would be silently ignored");

        // Balanced braces is a cheap structural check that catches a broken
        // renderer without adding a JSON parser dependency to this module.
        long open = content.chars().filter(c -> c == '{').count();
        long close = content.chars().filter(c -> c == '}').count();
        assertEquals(open, close, "unbalanced braces — the renderer emitted malformed JSON");
    }
}
