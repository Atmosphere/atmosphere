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
package org.atmosphere.ai.artifact;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactTest {

    private Artifact createSample() {
        return new Artifact("id-1", "ns", "file.txt", "text/plain",
                new byte[]{1, 2, 3}, 1, Map.of("key", "val"), Instant.now());
    }

    @Test
    void creationWithValidFields() {
        var now = Instant.now();
        var artifact = new Artifact("a1", "ns1", "report.pdf", "application/pdf",
                new byte[]{10, 20}, 1, Map.of("author", "agent"), now);

        assertEquals("a1", artifact.id());
        assertEquals("ns1", artifact.namespace());
        assertEquals("report.pdf", artifact.fileName());
        assertEquals("application/pdf", artifact.mimeType());
        assertEquals(1, artifact.version());
        assertEquals("agent", artifact.metadata().get("author"));
        assertEquals(now, artifact.createdAt());
    }

    @Test
    void nullIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact(null, "ns", "f.txt", "text/plain",
                        new byte[]{1}, 1, Map.of(), Instant.now()));
    }

    @Test
    void blankIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact("  ", "ns", "f.txt", "text/plain",
                        new byte[]{1}, 1, Map.of(), Instant.now()));
    }

    @Test
    void nullNamespaceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact("id", null, "f.txt", "text/plain",
                        new byte[]{1}, 1, Map.of(), Instant.now()));
    }

    @Test
    void nullFileNameThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact("id", "ns", null, "text/plain",
                        new byte[]{1}, 1, Map.of(), Instant.now()));
    }

    @Test
    void nullMimeTypeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact("id", "ns", "f.txt", null,
                        new byte[]{1}, 1, Map.of(), Instant.now()));
    }

    @Test
    void nullDataThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new Artifact("id", "ns", "f.txt", "text/plain",
                        null, 1, Map.of(), Instant.now()));
    }

    @Test
    void dataIsDefensivelyCopiedOnConstruction() {
        byte[] original = {1, 2, 3};
        var artifact = new Artifact("id", "ns", "f.txt", "text/plain",
                original, 1, Map.of(), Instant.now());

        // mutate original — record should be unaffected
        original[0] = 99;
        assertEquals(1, artifact.data()[0]);
    }

    @Test
    void dataAccessorReturnsDefensiveCopy() {
        var artifact = createSample();
        byte[] first = artifact.data();
        byte[] second = artifact.data();

        assertArrayEquals(first, second);
        assertNotSame(first, second);

        // mutating the returned array should not affect the record
        first[0] = 99;
        assertEquals(1, artifact.data()[0]);
    }

    @Test
    void sizeReturnsDataLength() {
        var artifact = new Artifact("id", "ns", "f.txt", "text/plain",
                new byte[]{10, 20, 30, 40, 50}, 1, Map.of(), Instant.now());
        assertEquals(5, artifact.size());
    }

    @Test
    void metadataIsDefensivelyCopied() {
        var meta = new HashMap<String, String>();
        meta.put("k", "v");
        var artifact = new Artifact("id", "ns", "f.txt", "text/plain",
                new byte[]{1}, 1, meta, Instant.now());

        // mutate original map — record should be unaffected
        meta.put("k2", "v2");
        assertEquals(1, artifact.metadata().size());
    }

    @Test
    void nullMetadataDefaultsToEmptyMap() {
        var artifact = new Artifact("id", "ns", "f.txt", "text/plain",
                new byte[]{1}, 1, null, Instant.now());

        assertNotNull(artifact.metadata());
        assertTrue(artifact.metadata().isEmpty());
    }

    @Test
    void nullCreatedAtDefaultsToNow() {
        var before = Instant.now();
        var artifact = new Artifact("id", "ns", "f.txt", "text/plain",
                new byte[]{1}, 1, Map.of(), null);

        assertNotNull(artifact.createdAt());
        // createdAt should be approximately now
        assertTrue(artifact.createdAt().isAfter(before.minusSeconds(1)));
    }
}
