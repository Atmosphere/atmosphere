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
package org.atmosphere.ai.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link WorkspaceAgentFileSystem}: the six operations, the traversal
 * guards (absolute paths, {@code ..}, backslashes, empty segments — all
 * rejected before any I/O), the {@link AgentFileSystem.Limits} bounds
 * (over-size write, file-count cap, total-bytes cap), and the unique-match
 * {@code edit} semantics.
 */
public class WorkspaceAgentFileSystemTest {

    @TempDir
    Path tmp;

    private WorkspaceAgentFileSystem fs() {
        return new WorkspaceAgentFileSystem(tmp.resolve("store"),
                AgentFileSystem.Limits.defaults());
    }

    private WorkspaceAgentFileSystem fs(int maxFileBytes, int maxFiles, long maxTotalBytes) {
        return new WorkspaceAgentFileSystem(tmp.resolve("store"),
                new AgentFileSystem.Limits(maxFileBytes, maxFiles, maxTotalBytes));
    }

    // ---- basic operations ----

    @Test
    public void writeReadRoundTrip() {
        var fs = fs();
        fs.write("notes/todo.md", "hello");

        assertEquals("hello", fs.read("notes/todo.md"));
    }

    @Test
    public void lsListsDirectoriesFirst() {
        var fs = fs();
        fs.write("b.txt", "b");
        fs.write("sub/a.txt", "a");

        var entries = fs.ls(null);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0).directory());
        assertEquals("sub", entries.get(0).path());
        assertFalse(entries.get(1).directory());
        assertEquals("b.txt", entries.get(1).path());
        assertEquals(1, entries.get(1).size());

        var sub = fs.ls("sub");
        assertEquals(1, sub.size());
        assertEquals("sub/a.txt", sub.get(0).path());
    }

    @Test
    public void lsOfMissingDirIsEmptyAndOfFileThrows() {
        var fs = fs();
        assertTrue(fs.ls("nope").isEmpty());
        fs.write("f.txt", "x");
        assertThrows(IllegalArgumentException.class, () -> fs.ls("f.txt"));
    }

    @Test
    public void readOfMissingFileThrowsClearly() {
        var e = assertThrows(IllegalArgumentException.class, () -> fs().read("nope.txt"));
        assertTrue(e.getMessage().contains("nope.txt"));
    }

    @Test
    public void globMatchesRelativePaths() {
        var fs = fs();
        fs.write("a.md", "x");
        fs.write("docs/b.md", "x");
        fs.write("docs/c.txt", "x");

        assertEquals(java.util.List.of("a.md", "docs/b.md"), fs.glob("**.md"));
        assertEquals(java.util.List.of("docs/b.md"), fs.glob("docs/*.md"));
        assertTrue(fs.glob("*.py").isEmpty());
    }

    @Test
    public void grepFindsLinesWithNumbers() {
        var fs = fs();
        fs.write("log.txt", "alpha\nbeta\ngamma beta\n");
        fs.write("sub/other.txt", "beta\n");

        var hits = fs.grep("beta", null);

        assertEquals(3, hits.size());
        var first = hits.stream().filter(h -> h.path().equals("log.txt")).findFirst()
                .orElseThrow();
        assertEquals(2, first.line());
        assertEquals("beta", first.text());

        var scoped = fs.grep("beta", "sub");
        assertEquals(1, scoped.size());
        assertEquals("sub/other.txt", scoped.get(0).path());
    }

    @Test
    public void grepRejectsInvalidRegexAndGlobRejectsBlankPattern() {
        var fs = fs();
        assertThrows(IllegalArgumentException.class, () -> fs.grep("[unclosed", null));
        assertThrows(IllegalArgumentException.class, () -> fs.glob("  "));
        assertThrows(IllegalArgumentException.class, () -> fs.grep(" ", null));
    }

    // ---- edit: unique-match semantics ----

    @Test
    public void editReplacesTheUniqueMatch() {
        var fs = fs();
        fs.write("f.txt", "one two three");

        fs.edit("f.txt", "two", "2");

        assertEquals("one 2 three", fs.read("f.txt"));
    }

    @Test
    public void editWithZeroMatchesFails() {
        var fs = fs();
        fs.write("f.txt", "content");

        var e = assertThrows(IllegalArgumentException.class,
                () -> fs.edit("f.txt", "absent", "x"));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        assertEquals("content", fs.read("f.txt"), "a failed edit must change nothing");
    }

    @Test
    public void editWithMultipleMatchesFails() {
        var fs = fs();
        fs.write("f.txt", "dup dup");

        var e = assertThrows(IllegalArgumentException.class,
                () -> fs.edit("f.txt", "dup", "x"));
        assertTrue(e.getMessage().contains("2 times"), e.getMessage());
        assertEquals("dup dup", fs.read("f.txt"), "a failed edit must change nothing");
    }

    @Test
    public void editOfMissingFileAndEmptyOldTextFail() {
        var fs = fs();
        assertThrows(IllegalArgumentException.class, () -> fs.edit("nope.txt", "a", "b"));
        fs.write("f.txt", "x");
        assertThrows(IllegalArgumentException.class, () -> fs.edit("f.txt", "", "b"));
    }

    // ---- delete / rename (native-bridge surface) ----

    @Test
    public void deleteRemovesAFile() {
        var fs = fs();
        fs.write("a.txt", "x");
        fs.write("b.txt", "y");

        fs.delete("a.txt");

        assertThrows(IllegalArgumentException.class, () -> fs.read("a.txt"));
        assertEquals("y", fs.read("b.txt"), "siblings must survive a delete");
    }

    @Test
    public void deleteRemovesADirectoryTree() {
        var fs = fs();
        fs.write("sub/a.txt", "a");
        fs.write("sub/deep/b.txt", "b");
        fs.write("keep.txt", "k");

        fs.delete("sub");

        assertTrue(fs.glob("sub/**").isEmpty(), "the whole subtree must be gone");
        assertEquals("k", fs.read("keep.txt"));
    }

    @Test
    public void deleteOfMissingPathFailsClearly() {
        var e = assertThrows(IllegalArgumentException.class, () -> fs().delete("nope.txt"));
        assertTrue(e.getMessage().contains("nope.txt"), e.getMessage());
    }

    @Test
    public void deleteFreesBoundsBudget() {
        var fs = fs(1024, 2, 4096);
        fs.write("a.txt", "a");
        fs.write("b.txt", "b");
        assertThrows(IllegalArgumentException.class, () -> fs.write("c.txt", "c"));

        fs.delete("a.txt");

        fs.write("c.txt", "c");
        assertEquals("c", fs.read("c.txt"), "delete must free the file-count budget");
    }

    @Test
    public void renameMovesAFileAndCreatesParents() {
        var fs = fs();
        fs.write("a.txt", "content");

        fs.rename("a.txt", "archive/2026/a.txt");

        assertEquals("content", fs.read("archive/2026/a.txt"));
        assertThrows(IllegalArgumentException.class, () -> fs.read("a.txt"));
    }

    @Test
    public void renameMovesADirectory() {
        var fs = fs();
        fs.write("old/a.txt", "a");
        fs.write("old/deep/b.txt", "b");

        fs.rename("old", "new");

        assertEquals("a", fs.read("new/a.txt"));
        assertEquals("b", fs.read("new/deep/b.txt"));
        assertTrue(fs.glob("old/**").isEmpty());
    }

    @Test
    public void renameRejectsMissingSourceExistingDestinationAndSelfMove() {
        var fs = fs();
        fs.write("a.txt", "a");
        fs.write("b.txt", "b");
        fs.write("dir/c.txt", "c");

        var missing = assertThrows(IllegalArgumentException.class,
                () -> fs.rename("nope.txt", "x.txt"));
        assertTrue(missing.getMessage().contains("nope.txt"), missing.getMessage());

        var exists = assertThrows(IllegalArgumentException.class,
                () -> fs.rename("a.txt", "b.txt"));
        assertTrue(exists.getMessage().contains("already exists"), exists.getMessage());
        assertEquals("a", fs.read("a.txt"), "a failed rename must change nothing");
        assertEquals("b", fs.read("b.txt"), "a failed rename must change nothing");

        var self = assertThrows(IllegalArgumentException.class,
                () -> fs.rename("dir", "dir/inner"));
        assertTrue(self.getMessage().contains("into itself"), self.getMessage());
    }

    @Test
    public void deleteAndRenameRejectTraversal() {
        var fs = fs();
        fs.write("a.txt", "a");
        for (var attack : new String[]{"../x", "..", "/etc/passwd", "a\\..\\b", ""}) {
            assertThrows(IllegalArgumentException.class, () -> fs.delete(attack),
                    "delete must reject '" + attack + "'");
            assertThrows(IllegalArgumentException.class, () -> fs.rename(attack, "ok.txt"),
                    "rename must reject source '" + attack + "'");
            assertThrows(IllegalArgumentException.class, () -> fs.rename("a.txt", attack),
                    "rename must reject destination '" + attack + "'");
        }
        assertEquals("a", fs.read("a.txt"));
    }

    // ---- traversal guards ----

    @Test
    public void traversalAttacksAreRejectedEverywhere() throws Exception {
        var fs = fs();
        // A file outside the store root that an escape would reach.
        Files.writeString(tmp.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);

        for (var attack : new String[]{
                "../secret.txt", "..", "a/../../secret.txt", "/etc/passwd",
                "a\\..\\b", "a//b", "", "  ", "./a", "a/./b", "a/"}) {
            assertThrows(IllegalArgumentException.class, () -> fs.read(attack),
                    "read must reject '" + attack + "'");
            assertThrows(IllegalArgumentException.class, () -> fs.write(attack, "x"),
                    "write must reject '" + attack + "'");
            assertThrows(IllegalArgumentException.class, () -> fs.edit(attack, "a", "b"),
                    "edit must reject '" + attack + "'");
            if (attack != null && !attack.isBlank()) {
                // Blank dir is the documented "search everything" default for
                // grep — only path-shaped input must be rejected.
                assertThrows(IllegalArgumentException.class, () -> fs.grep("x", attack),
                        "grep must reject '" + attack + "'");
            }
        }
        assertEquals("secret", Files.readString(tmp.resolve("secret.txt")),
                "nothing outside the root may be touched");
    }

    @Test
    public void forConversationValidatesTheConversationId() {
        var limits = AgentFileSystem.Limits.defaults();
        for (var bad : new String[]{null, " ", "..", "a/b", "a\\b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkspaceAgentFileSystem.forConversation(tmp, bad, limits),
                    "conversationId '" + bad + "' must be rejected");
        }
        var fs = WorkspaceAgentFileSystem.forConversation(tmp, "conv-1", limits);
        assertEquals(tmp.resolve("files").resolve("conv-1"), fs.root(),
                "the store must root at files/{conversationId}/");
    }

    // ---- bounds (Correctness Invariant #3) ----

    @Test
    public void oversizedWriteIsRejected() {
        var fs = fs(16, 10, 1024);

        var e = assertThrows(IllegalArgumentException.class,
                () -> fs.write("f.txt", "x".repeat(17)));
        assertTrue(e.getMessage().contains("per-file limit"), e.getMessage());
        assertTrue(fs.ls(null).isEmpty(), "a rejected write must not create the file");

        fs.write("f.txt", "x".repeat(16));
        assertEquals(16, fs.read("f.txt").length());
    }

    @Test
    public void fileCountCapIsEnforced() {
        var fs = fs(1024, 2, 4096);
        fs.write("a.txt", "a");
        fs.write("b.txt", "b");

        var e = assertThrows(IllegalArgumentException.class, () -> fs.write("c.txt", "c"));
        assertTrue(e.getMessage().contains("file limit"), e.getMessage());

        // Overwriting an existing file is still allowed at the cap.
        fs.write("a.txt", "a2");
        assertEquals("a2", fs.read("a.txt"));
    }

    @Test
    public void totalBytesCapIsEnforced() {
        var fs = fs(64, 10, 100);
        fs.write("a.txt", "x".repeat(60));

        var e = assertThrows(IllegalArgumentException.class,
                () -> fs.write("b.txt", "x".repeat(50)));
        assertTrue(e.getMessage().contains("total limit"), e.getMessage());

        // Replacing the existing file's bytes counts the delta, not the sum.
        fs.write("a.txt", "x".repeat(64));
        assertEquals(64, fs.read("a.txt").length());
    }

    @Test
    public void editEnforcesBoundsToo() {
        var fs = fs(16, 10, 1024);
        fs.write("f.txt", "short");

        var e = assertThrows(IllegalArgumentException.class,
                () -> fs.edit("f.txt", "short", "x".repeat(32)));
        assertTrue(e.getMessage().contains("per-file limit"), e.getMessage());
        assertEquals("short", fs.read("f.txt"));
    }

    @Test
    public void limitsRejectNonPositiveValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentFileSystem.Limits(0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentFileSystem.Limits(1, -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentFileSystem.Limits(1, 1, 0));
        var defaults = AgentFileSystem.Limits.defaults();
        assertEquals(512 * 1024, defaults.maxFileBytes());
        assertEquals(256, defaults.maxFiles());
        assertEquals(16L * 1024 * 1024, defaults.maxTotalBytes());
    }
}
