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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryArtifactStoreTest {

    private InMemoryArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryArtifactStore();
    }

    @Test
    void saveAndLoadRoundTrip() {
        var artifact = new Artifact("doc1", "session1", "report.pdf",
                "application/pdf", "hello".getBytes(), 0, Map.of(), Instant.now());
        var saved = store.save(artifact);

        assertEquals(1, saved.version());
        var loaded = store.load("session1", "doc1");
        assertTrue(loaded.isPresent());
        assertArrayEquals("hello".getBytes(), loaded.get().data());
        assertEquals("report.pdf", loaded.get().fileName());
    }

    @Test
    void versioningIncrements() {
        var a1 = new Artifact("doc1", "ns", "v1.txt", "text/plain",
                "v1".getBytes(), 0, Map.of(), Instant.now());
        var a2 = new Artifact("doc1", "ns", "v2.txt", "text/plain",
                "v2".getBytes(), 0, Map.of(), Instant.now());

        assertEquals(1, store.save(a1).version());
        assertEquals(2, store.save(a2).version());

        var latest = store.load("ns", "doc1");
        assertTrue(latest.isPresent());
        assertEquals(2, latest.get().version());
        assertArrayEquals("v2".getBytes(), latest.get().data());
    }

    @Test
    void loadSpecificVersion() {
        var a1 = new Artifact("doc1", "ns", "f.txt", "text/plain",
                "v1".getBytes(), 0, Map.of(), Instant.now());
        var a2 = new Artifact("doc1", "ns", "f.txt", "text/plain",
                "v2".getBytes(), 0, Map.of(), Instant.now());
        store.save(a1);
        store.save(a2);

        var v1 = store.load("ns", "doc1", 1);
        assertTrue(v1.isPresent());
        assertArrayEquals("v1".getBytes(), v1.get().data());

        var v2 = store.load("ns", "doc1", 2);
        assertTrue(v2.isPresent());
        assertArrayEquals("v2".getBytes(), v2.get().data());

        assertTrue(store.load("ns", "doc1", 99).isEmpty());
    }

    @Test
    void listReturnsLatestVersions() {
        store.save(new Artifact("a", "ns", "a.txt", "text/plain",
                "a".getBytes(), 0, Map.of(), Instant.now()));
        store.save(new Artifact("b", "ns", "b.txt", "text/plain",
                "b".getBytes(), 0, Map.of(), Instant.now()));
        store.save(new Artifact("a", "ns", "a2.txt", "text/plain",
                "a2".getBytes(), 0, Map.of(), Instant.now()));

        var list = store.list("ns");
        assertEquals(2, list.size());
    }

    @Test
    void deleteRemovesAllVersions() {
        store.save(new Artifact("doc1", "ns", "f.txt", "text/plain",
                "v1".getBytes(), 0, Map.of(), Instant.now()));
        store.save(new Artifact("doc1", "ns", "f.txt", "text/plain",
                "v2".getBytes(), 0, Map.of(), Instant.now()));

        assertTrue(store.delete("ns", "doc1"));
        assertTrue(store.load("ns", "doc1").isEmpty());
        assertFalse(store.delete("ns", "doc1"));
    }

    @Test
    void deleteAllClearsNamespace() {
        store.save(new Artifact("a", "ns", "a.txt", "text/plain",
                "a".getBytes(), 0, Map.of(), Instant.now()));
        store.save(new Artifact("b", "ns", "b.txt", "text/plain",
                "b".getBytes(), 0, Map.of(), Instant.now()));

        store.deleteAll("ns");
        assertTrue(store.list("ns").isEmpty());
    }

    @Test
    void namespaceIsolation() {
        store.save(new Artifact("doc1", "ns1", "f.txt", "text/plain",
                "ns1".getBytes(), 0, Map.of(), Instant.now()));
        store.save(new Artifact("doc1", "ns2", "f.txt", "text/plain",
                "ns2".getBytes(), 0, Map.of(), Instant.now()));

        var ns1 = store.load("ns1", "doc1");
        var ns2 = store.load("ns2", "doc1");
        assertTrue(ns1.isPresent());
        assertTrue(ns2.isPresent());
        assertArrayEquals("ns1".getBytes(), ns1.get().data());
        assertArrayEquals("ns2".getBytes(), ns2.get().data());
    }

    @Test
    void loadNonexistentReturnsEmpty() {
        assertTrue(store.load("ns", "nope").isEmpty());
        assertTrue(store.load("ns", "nope", 1).isEmpty());
    }

    @Test
    void artifactRecordValidation() {
        assertThrows(IllegalArgumentException.class, () ->
                new Artifact(null, "ns", "f", "t/p", new byte[0], 0, Map.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () ->
                new Artifact("id", null, "f", "t/p", new byte[0], 0, Map.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () ->
                new Artifact("id", "ns", null, "t/p", new byte[0], 0, Map.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () ->
                new Artifact("id", "ns", "f", null, new byte[0], 0, Map.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () ->
                new Artifact("id", "ns", "f", "t/p", null, 0, Map.of(), Instant.now()));
    }

    @Test
    void artifactSizeHelper() {
        var a = new Artifact("id", "ns", "f", "t/p", "hello".getBytes(), 1, Map.of(), Instant.now());
        assertEquals(5, a.size());
    }
}
