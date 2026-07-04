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
package org.atmosphere.ai.adk;

import com.google.genai.types.Part;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.WorkspaceAgentFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link AdkArtifactService} bridge contract: every ADK artifact
 * operation lands in (or reads from) Atmosphere's bounded
 * {@link AgentFileSystem} store, versions collapse to overwrite semantics
 * (always {@code 0}), and boundary rejections — traversal, bounds, non-text
 * payloads — surface on the reactive error channel instead of being
 * swallowed.
 */
class AdkArtifactServiceTest {

    private static final AgentFileSystem.Limits SMALL_LIMITS =
            new AgentFileSystem.Limits(64, 4, 256);

    @TempDir
    Path tempDir;

    private WorkspaceAgentFileSystem fileSystem;
    private AdkArtifactService service;

    @BeforeEach
    void setUp() {
        fileSystem = WorkspaceAgentFileSystem.forConversation(
                tempDir, "conv-1", AgentFileSystem.Limits.defaults());
        service = new AdkArtifactService(fileSystem);
    }

    @Test
    void saveHitsAgentFileSystemAndReportsVersionZero() {
        var version = service
                .saveArtifact("app", "user", "sess", "notes.md", Part.fromText("hello"))
                .blockingGet();

        assertEquals(AdkArtifactService.CURRENT_VERSION, version);
        assertEquals("hello", fileSystem.read("notes.md"),
                "the save must land in Atmosphere's store, not a shadow copy");
    }

    @Test
    void loadReadsBackFromAgentFileSystem() {
        fileSystem.write("report/summary.txt", "from the store");

        var part = service
                .loadArtifact("app", "user", "sess", "report/summary.txt", null)
                .blockingGet();

        assertEquals("from the store", part.text().orElseThrow());
    }

    @Test
    void loadExplicitVersionZeroReturnsCurrentContent() {
        fileSystem.write("a.txt", "v0");

        var part = service.loadArtifact("app", "user", "sess", "a.txt", 0).blockingGet();

        assertEquals("v0", part.text().orElseThrow());
    }

    @Test
    void loadMissingArtifactCompletesEmpty() {
        service.loadArtifact("app", "user", "sess", "absent.txt", null)
                .test()
                .assertNoValues()
                .assertComplete();
    }

    @Test
    void loadNonCurrentVersionCompletesEmpty() {
        fileSystem.write("a.txt", "only version");

        // Overwrite semantics: no historical snapshots exist, so any version
        // other than 0 has nothing to serve.
        service.loadArtifact("app", "user", "sess", "a.txt", 3)
                .test()
                .assertNoValues()
                .assertComplete();
    }

    @Test
    void overwriteKeepsSingleVersion() {
        service.saveArtifact("app", "user", "sess", "a.txt", Part.fromText("first"))
                .blockingGet();
        var second = service
                .saveArtifact("app", "user", "sess", "a.txt", Part.fromText("second"))
                .blockingGet();

        assertEquals(AdkArtifactService.CURRENT_VERSION, second,
                "a second save must overwrite, not mint a new version");
        assertEquals("second", fileSystem.read("a.txt"));
        assertEquals(java.util.List.of(AdkArtifactService.CURRENT_VERSION),
                service.listVersions("app", "user", "sess", "a.txt").blockingGet());
    }

    @Test
    void listVersionsOfMissingArtifactIsEmpty() {
        assertTrue(service.listVersions("app", "user", "sess", "nope.txt")
                .blockingGet().isEmpty());
    }

    @Test
    void listArtifactKeysReflectsTheStore() {
        fileSystem.write("a.txt", "1");
        fileSystem.write("notes/b.md", "2");

        var filenames = service.listArtifactKeys("app", "user", "sess")
                .blockingGet().filenames();

        assertEquals(java.util.List.of("a.txt", "notes/b.md"), filenames);
    }

    @Test
    void traversalFilenameSignalsErrorOnSave() {
        service.saveArtifact("app", "user", "sess", "../escape.txt", Part.fromText("x"))
                .test()
                .assertError(IllegalArgumentException.class);
    }

    @Test
    void traversalFilenameSignalsErrorOnLoad() {
        service.loadArtifact("app", "user", "sess", "../../etc/passwd", null)
                .test()
                .assertError(IllegalArgumentException.class);
    }

    @Test
    void overLimitWriteSignalsErrorWithClearMessage() {
        var bounded = new AdkArtifactService(
                new WorkspaceAgentFileSystem(tempDir.resolve("bounded"), SMALL_LIMITS));

        bounded.saveArtifact("app", "user", "sess", "big.txt",
                        Part.fromText("x".repeat(100)))
                .test()
                .assertError(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("Write rejected"));
    }

    @Test
    void deleteArtifactSignalsUnsupported() {
        fileSystem.write("a.txt", "content");

        service.deleteArtifact("app", "user", "sess", "a.txt")
                .test()
                .assertError(UnsupportedOperationException.class);
        assertEquals("content", fileSystem.read("a.txt"),
                "a rejected delete must leave the store untouched");
    }

    @Test
    void utf8InlineDataIsStoredAsText() {
        var part = Part.fromBytes("café ☕".getBytes(StandardCharsets.UTF_8), "text/plain");

        service.saveArtifact("app", "user", "sess", "utf8.txt", part).blockingGet();

        assertEquals("café ☕", fileSystem.read("utf8.txt"));
    }

    @Test
    void binaryInlineDataIsRejected() {
        // 0xFF 0xFE is never valid UTF-8 — the text-only store must refuse it
        // rather than corrupt it into replacement characters.
        var part = Part.fromBytes(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00}, "image/png");

        service.saveArtifact("app", "user", "sess", "binary.bin", part)
                .test()
                .assertError(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("not valid UTF-8"));
    }

    @Test
    void payloadFreePartIsRejected() {
        service.saveArtifact("app", "user", "sess", "empty.txt", Part.builder().build())
                .test()
                .assertError(e -> e instanceof IllegalArgumentException
                        && e.getMessage().contains("no text or inline-data payload"));
    }

    @Test
    void nullFileSystemIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new AdkArtifactService(null));
    }
}
