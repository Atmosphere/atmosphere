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
package org.atmosphere.checkpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the copied {@link SchemaMigrations} against drift.
 *
 * <p>Module families that must not depend on {@code atmosphere-checkpoint}
 * carry a package-local copy of the helper. "Identical semantics" is only true
 * while someone keeps it true, so this test compares the <em>code</em> of every
 * copy (comments, blank lines, the package declaration, and the class
 * visibility modifier stripped) against the canonical file and fails on any
 * difference — a fix applied to one copy and not the others breaks the build
 * rather than silently diverging the durability behaviour of one module.</p>
 */
class SchemaMigrationsCopySyncTest {

    private static final Path CANONICAL = Path.of(
            "src/main/java/org/atmosphere/checkpoint/SchemaMigrations.java");

    /** Every package-local copy, relative to the reactor's {@code modules/} directory. */
    private static final List<String> COPIES = List.of(
            "durable-sessions-sqlite/src/main/java/org/atmosphere/session/sqlite/SchemaMigrations.java",
            "interactions/src/main/java/org/atmosphere/interactions/SchemaMigrations.java",
            "admin/src/main/java/org/atmosphere/admin/evals/SchemaMigrations.java",
            "ai/src/main/java/org/atmosphere/ai/batch/SchemaMigrations.java",
            "ai-audit-postgres/src/main/java/org/atmosphere/ai/audit/postgres/SchemaMigrations.java");

    @Test
    void everyCopyMatchesTheCanonicalHelperCode() throws IOException {
        var canonicalPath = CANONICAL.toAbsolutePath();
        assertTrue(Files.isRegularFile(canonicalPath),
                "canonical helper not found at " + canonicalPath
                        + " — fix this test's path, do not weaken the check");
        var canonical = code(canonicalPath);

        // modules/checkpoint -> modules
        var modulesDir = canonicalPath.getParent().getParent().getParent().getParent()
                .getParent().getParent().getParent().getParent();

        int compared = 0;
        for (var copy : COPIES) {
            var copyPath = modulesDir.resolve(copy);
            assertTrue(Files.isRegularFile(copyPath),
                    "copy not found at " + copyPath + " — if the file moved, update this test; "
                            + "a missing copy must fail, never silently pass");
            assertEquals(canonical, code(copyPath),
                    copy + " has drifted from the canonical "
                            + "org.atmosphere.checkpoint.SchemaMigrations — the copies must stay "
                            + "semantically identical");
            compared++;
        }
        assertEquals(COPIES.size(), compared);
    }

    @Test
    void theGuardBitesOnCodeDriftButToleratesCommentDrift(@TempDir Path tempDir)
            throws IOException {
        // Proves the comparison actually bites, by running it over injected
        // copies rather than asserting on the real files alone: a one-token
        // semantic change must fail, a comment-only change must not.
        var canonicalPath = CANONICAL.toAbsolutePath();
        var canonical = code(canonicalPath);
        var source = Files.readString(canonicalPath, StandardCharsets.UTF_8);

        var divergent = tempDir.resolve("Divergent.java");
        // Flip the fail-closed comparison — exactly the kind of drift that would
        // let one module silently accept a newer-than-code schema.
        Files.writeString(divergent, source.replace("stamped > target", "stamped >= target"),
                StandardCharsets.UTF_8);
        assertNotEquals(canonical, code(divergent),
                "the guard must reject a copy whose code diverged");

        var commentOnly = tempDir.resolve("CommentOnly.java");
        Files.writeString(commentOnly,
                source.replace(" * @since 4.0", " * @since 4.0 (package-local copy)"),
                StandardCharsets.UTF_8);
        assertEquals(canonical, code(commentOnly),
                "the guard must tolerate the per-copy Javadoc differences");
    }

    /**
     * The file's code with comments, blank lines, the package declaration, and
     * the class visibility modifier removed — the copies legitimately differ in
     * exactly those, and in nothing else.
     */
    private static String code(Path file) throws IOException {
        var out = new StringBuilder();
        boolean inBlockComment = false;
        for (var raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            var line = raw.strip();
            if (inBlockComment) {
                inBlockComment = !line.contains("*/");
                continue;
            }
            if (line.startsWith("/*")) {
                inBlockComment = !line.contains("*/");
                continue;
            }
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) {
                continue;
            }
            if (line.startsWith("package ")) {
                continue;
            }
            out.append(line.replace("public final class SchemaMigrations",
                    "final class SchemaMigrations")).append('\n');
        }
        return out.toString();
    }
}
