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
package org.atmosphere.ai.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Document ingestion / ETL (P2.18): filesystem + classpath sources, boundary safety, consumer. */
class DocumentSourceTest {

    @Test
    void fileSystemLoadsMatchingFilesRecursivelyAndExcludesOthers(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.md"), "alpha markdown");
        Files.writeString(dir.resolve("b.txt"), "bravo text");
        Files.writeString(dir.resolve("c.png"), "not text");
        var sub = Files.createDirectory(dir.resolve("nested"));
        Files.writeString(sub.resolve("d.md"), "delta nested");

        var docs = new FileSystemDocumentSource(dir).load();

        assertEquals(3, docs.size(), "md+txt loaded, png excluded: " + docs);
        assertTrue(docs.stream().anyMatch(d -> d.content().equals("delta nested")),
                "recursion must load nested files");
        assertTrue(docs.stream().anyMatch(d -> d.source().endsWith("a.md")),
                "source is the path relative to the root");
        assertFalse(docs.stream().anyMatch(d -> d.source().endsWith(".png")));
    }

    @Test
    void nonRecursiveSkipsSubdirectories(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("top.md"), "top");
        var sub = Files.createDirectory(dir.resolve("nested"));
        Files.writeString(sub.resolve("deep.md"), "deep");

        var docs = new FileSystemDocumentSource(dir, Set.of("md"), false).load();
        assertEquals(1, docs.size());
        assertEquals("top", docs.get(0).content());
    }

    @Test
    void missingDirectoryFailsLoudly() {
        var src = new FileSystemDocumentSource(Path.of("/no/such/dir/atmosphere-test"));
        assertThrows(IllegalArgumentException.class, src::load,
                "a missing directory must throw, not silently return empty");
    }

    @Test
    void symlinkEscapingRootIsRejected(@TempDir Path dir) throws IOException {
        // A secret outside the ingestion root that a symlink inside tries to expose.
        var outside = Files.createTempFile("secret", ".md");
        Files.writeString(outside, "SECRET");
        try {
            Files.createSymbolicLink(dir.resolve("leak.md"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            return; // platform without symlink support — nothing to assert
        }
        Files.writeString(dir.resolve("ok.md"), "public");

        var docs = new FileSystemDocumentSource(dir).load();
        assertTrue(docs.stream().noneMatch(d -> d.content().contains("SECRET")),
                "a symlink pointing outside the root must not be read (boundary safety)");
        assertTrue(docs.stream().anyMatch(d -> d.content().equals("public")));
        Files.deleteIfExists(outside);
    }

    @Test
    void classpathSourceLoadsBundledResources() {
        var docs = new ClasspathDocumentSource("docs/sample-a.md", "docs/sample-b.md").load();
        assertEquals(2, docs.size());
        assertTrue(docs.get(0).content().contains("WebTransport"));
    }

    @Test
    void classpathMissingResourceThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClasspathDocumentSource("docs/does-not-exist.md").load());
    }

    @Test
    void fromSourceBuildsRetrievableProvider(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("net.md"), "WebTransport runs over HTTP/3 and QUIC", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("db.md"), "Postgres is a relational database", StandardCharsets.UTF_8);

        var provider = InMemoryContextProvider.fromSource(new FileSystemDocumentSource(dir));
        var hits = provider.retrieve("QUIC transport", 1);

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).content().contains("WebTransport"),
                "retrieval must surface the matching ingested document");
    }

    @Test
    void fromSourceChunkedPreservesChunkMetadata(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("long.md"), "word ".repeat(800));
        var provider = InMemoryContextProvider.fromSourceChunked(
                new FileSystemDocumentSource(dir), 200, 20);
        var hits = provider.retrieve("word", 5);
        assertTrue(hits.size() > 1, "a long document must chunk into multiple retrievable pieces");
        assertTrue(hits.get(0).metadata().containsKey("chunk_index"),
                "chunk attribution metadata must survive ingestion");
    }
}
