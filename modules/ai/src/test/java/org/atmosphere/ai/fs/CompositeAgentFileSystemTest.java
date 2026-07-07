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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link CompositeAgentFileSystem}: prefix routing to distinct
 * {@link WorkspaceAgentFileSystem} backends (longest-prefix match, full path
 * passed through), the whole-namespace merge/fan-out operations
 * ({@code ls} of root, {@code glob}, root {@code grep}), the cross-backend
 * {@code rename} guard, the {@code null}/blank boundary guard, and the fact
 * that each delegate keeps its own {@link AgentFileSystem.Limits}.
 */
public class CompositeAgentFileSystemTest {

    @TempDir
    Path tmp;

    private WorkspaceAgentFileSystem fs(String name) {
        return new WorkspaceAgentFileSystem(tmp.resolve(name),
                AgentFileSystem.Limits.defaults());
    }

    /** memory/ -> fsA, everything else -> fsB. */
    private CompositeAgentFileSystem composite(WorkspaceAgentFileSystem memory,
                                               WorkspaceAgentFileSystem def) {
        return CompositeAgentFileSystem.builder()
                .defaultFs(def)
                .route("memory/", memory)
                .build();
    }

    // ---- routing: writes land in the right backend ----

    @Test
    public void writeToPrefixLandsInThatBackendOnly() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);

        composite.write("memory/x.md", "A");

        // (1) lands in fsA, and NOT in fsB.
        assertEquals("A", fsA.read("memory/x.md"));
        assertThrows(IllegalArgumentException.class, () -> fsB.read("memory/x.md"),
                "the memory route must not write to the default backend");
    }

    @Test
    public void writeToUnmatchedPathLandsInDefaultBackend() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);

        composite.write("notes.md", "N");

        // (2) lands in fsB, and NOT in fsA.
        assertEquals("N", fsB.read("notes.md"));
        assertThrows(IllegalArgumentException.class, () -> fsA.read("notes.md"),
                "an unmatched path must not write to the memory backend");
    }

    @Test
    public void readRoutesToTheOwningBackend() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "A");
        composite.write("notes.md", "N");

        // (3) reads route by the same prefix rule as writes.
        assertEquals("A", composite.read("memory/x.md"));
        assertEquals("N", composite.read("notes.md"));
    }

    @Test
    public void editAndDeleteRouteToTheOwningBackend() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "one two");
        composite.write("notes.md", "keep me");

        composite.edit("memory/x.md", "two", "2");
        assertEquals("one 2", fsA.read("memory/x.md"));

        composite.delete("memory/x.md");
        assertThrows(IllegalArgumentException.class, () -> composite.read("memory/x.md"));
        assertEquals("keep me", composite.read("notes.md"),
                "deleting on one backend must not touch the other");
    }

    // ---- (4) longest-prefix match wins ----

    @Test
    public void longestPrefixMatchWins() {
        var fsA = fs("a");        // memory/
        var fsC = fs("c");        // memory/private/
        var fsB = fs("b");        // default
        var composite = CompositeAgentFileSystem.builder()
                .defaultFs(fsB)
                .route("memory/", fsA)
                .route("memory/private/", fsC)
                .build();

        composite.write("memory/notes.md", "general");
        composite.write("memory/private/secret.md", "hush");

        assertEquals("general", fsA.read("memory/notes.md"));
        assertThrows(IllegalArgumentException.class, () -> fsA.read("memory/private/secret.md"),
                "the deeper prefix must win, not the shorter memory/ route");
        assertEquals("hush", fsC.read("memory/private/secret.md"));
        // The routing key is segment-aware: memoryfoo does not match memory.
        composite.write("memoryfoo.md", "not memory");
        assertEquals("not memory", fsB.read("memoryfoo.md"));
    }

    // ---- (5) glob fans out across all backends ----

    @Test
    public void globReturnsEntriesFromEveryBackend() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "A");
        composite.write("notes.md", "N");

        var all = composite.glob("**");

        assertEquals(List.of("memory/x.md", "notes.md"), all,
                "glob must span the whole namespace across both backends");
    }

    // ---- grep fans out at root, routes when scoped ----

    @Test
    public void grepFansOutAtRootAndRoutesWhenScoped() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "needle here");
        composite.write("notes.md", "needle there");

        var rootHits = composite.grep("needle", null);
        assertEquals(2, rootHits.size(), "root grep must fan out to both backends");

        var scoped = composite.grep("needle", "memory");
        assertEquals(1, scoped.size(), "a scoped grep routes to the owning backend");
        assertEquals("memory/x.md", scoped.get(0).path());
    }

    // ---- (6) cross-backend rename fails loud; same-backend works ----

    @Test
    public void crossBackendRenameThrows() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "A");

        var e = assertThrows(IllegalArgumentException.class,
                () -> composite.rename("memory/x.md", "notes.md"));
        assertTrue(e.getMessage().contains("cross-backend rename is not supported"),
                e.getMessage());
        // Nothing moved.
        assertEquals("A", fsA.read("memory/x.md"));
        assertThrows(IllegalArgumentException.class, () -> fsB.read("notes.md"));
    }

    @Test
    public void sameBackendRenameSucceeds() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "A");

        composite.rename("memory/x.md", "memory/y.md");

        assertEquals("A", composite.read("memory/y.md"));
        assertThrows(IllegalArgumentException.class, () -> composite.read("memory/x.md"));
    }

    // ---- (7) ls("") merges both namespaces ----

    @Test
    public void lsOfRootMergesEveryBackend() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "A");
        composite.write("notes.md", "N");

        var root = composite.ls("");

        var paths = root.stream().map(AgentFileSystem.FileInfo::path).toList();
        assertEquals(List.of("memory", "notes.md"), paths,
                "root ls must merge both namespaces, directories first");
        assertTrue(root.get(0).directory(), "memory is the fsA directory");
        assertFalse(root.get(1).directory(), "notes.md is the fsB file");
    }

    @Test
    public void lsOfRoutedDirRoutesToOwningBackend() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);
        composite.write("memory/x.md", "A");

        var listing = composite.ls("memory");
        assertEquals(1, listing.size());
        assertEquals("memory/x.md", listing.get(0).path());
    }

    // ---- (8) a delegate's bounds still bite ----

    @Test
    public void delegateBoundsAreStillEnforced() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = composite(fsA, fsB);

        // Default per-file cap is 512 KiB; one byte over must be rejected by
        // the routed delegate, unchanged by the composite.
        var oversized = "x".repeat(AgentFileSystem.Limits.DEFAULT_MAX_FILE_BYTES + 1);
        var e = assertThrows(IllegalArgumentException.class,
                () -> composite.write("memory/big.md", oversized));
        assertTrue(e.getMessage().contains("per-file limit"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> fsA.read("memory/big.md"),
                "a rejected write must not create the file");
    }

    // ---- boundary + construction guards ----

    @Test
    public void nullOrBlankPathIsRejectedBeforeRouting() {
        var composite = composite(fs("a"), fs("b"));
        assertThrows(IllegalArgumentException.class, () -> composite.read(null));
        assertThrows(IllegalArgumentException.class, () -> composite.read("  "));
        assertThrows(IllegalArgumentException.class, () -> composite.write(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> composite.delete(" "));
        assertThrows(IllegalArgumentException.class, () -> composite.rename(null, "a.txt"));
        assertThrows(IllegalArgumentException.class, () -> composite.rename("a.txt", " "));
    }

    @Test
    public void builderRejectsBadConfiguration() {
        var fsA = fs("a");
        assertThrows(IllegalArgumentException.class,
                () -> CompositeAgentFileSystem.builder().build(),
                "a default backend is required");
        assertThrows(IllegalArgumentException.class,
                () -> CompositeAgentFileSystem.builder().defaultFs(fsA).route("  ", fsA).build(),
                "a blank prefix must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> CompositeAgentFileSystem.builder().defaultFs(fsA).route("../esc/", fsA),
                "a traversal prefix must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> CompositeAgentFileSystem.builder().defaultFs(fsA)
                        .route("memory/", fsA).route("memory", fsA),
                "a duplicate prefix must be rejected");
    }

    @Test
    public void mapFactoryRoutesLikeTheBuilder() {
        var fsA = fs("a");
        var fsB = fs("b");
        var composite = CompositeAgentFileSystem.of(
                java.util.Map.of("memory/", fsA), fsB);

        composite.write("memory/x.md", "A");
        composite.write("notes.md", "N");

        assertEquals("A", fsA.read("memory/x.md"));
        assertEquals("N", fsB.read("notes.md"));
    }
}
