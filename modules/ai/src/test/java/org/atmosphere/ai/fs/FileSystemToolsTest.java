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

import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolFailedException;
import org.atmosphere.ai.tool.ToolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the eight built-in file tools: thin wrappers over the
 * conversation-scoped {@link AgentFileSystem} from the injectables seam, with
 * a rejected call failing with a {@link ToolFailedException} that carries the
 * text the model reads (never a stack trace), the
 * provider fallback on resource-free paths, and the READ / EDIT
 * {@link ToolKind} split the permission modes key on.
 */
public class FileSystemToolsTest {

    @TempDir
    Path tmp;

    private Map<Class<?>, Object> scopeWithFs() {
        var fs = new WorkspaceAgentFileSystem(tmp.resolve("store"),
                AgentFileSystem.Limits.defaults());
        return Map.of(AgentFileSystem.class, fs);
    }

    private static Object exec(ToolDefinition tool, Map<String, Object> args,
                               Map<Class<?>, Object> scope) throws Exception {
        return tool.executor().execute(args, scope);
    }

    /** The text the model reads for a call that failed; fails the test when the call succeeds. */
    private static String failure(ToolDefinition tool, Map<String, Object> args,
                                  Map<Class<?>, Object> scope) {
        return assertThrows(ToolFailedException.class,
                () -> tool.executor().execute(args, scope)).getMessage();
    }

    @Test
    public void toolKindsArePinned() {
        assertEquals(ToolKind.READ, FileSystemTools.ls().kind());
        assertEquals(ToolKind.READ, FileSystemTools.readFile().kind());
        assertEquals(ToolKind.READ, FileSystemTools.glob().kind());
        assertEquals(ToolKind.READ, FileSystemTools.grep().kind());
        assertEquals(ToolKind.EDIT, FileSystemTools.writeFile().kind());
        assertEquals(ToolKind.EDIT, FileSystemTools.editFile().kind());
        assertEquals(ToolKind.EDIT, FileSystemTools.rename().kind());
        assertEquals(ToolKind.DELETE, FileSystemTools.delete().kind());
        assertEquals(8, FileSystemTools.all().size());
    }

    @Test
    public void writeReadLsRoundTripThroughTheTools() throws Exception {
        var scope = scopeWithFs();

        var wrote = exec(FileSystemTools.writeFile(),
                Map.of("path", "notes/a.md", "content", "hello"), scope);
        assertEquals("Wrote notes/a.md", wrote);

        assertEquals("hello", exec(FileSystemTools.readFile(),
                Map.of("path", "notes/a.md"), scope));

        var ls = exec(FileSystemTools.ls(), Map.of(), scope).toString();
        assertTrue(ls.contains("notes/"), ls);

        var lsNotes = exec(FileSystemTools.ls(), Map.of("dir", "notes"), scope).toString();
        assertTrue(lsNotes.contains("notes/a.md (5 bytes)"), lsNotes);
    }

    @Test
    public void deleteAndRenameRoundTripThroughTheTools() throws Exception {
        var scope = scopeWithFs();
        exec(FileSystemTools.writeFile(), Map.of("path", "old.md", "content", "keep"), scope);

        var renamed = exec(FileSystemTools.rename(),
                Map.of("old_path", "old.md", "new_path", "new.md"), scope);
        assertEquals("Renamed old.md to new.md", renamed);
        assertEquals("keep", exec(FileSystemTools.readFile(), Map.of("path", "new.md"), scope));

        var deleted = exec(FileSystemTools.delete(), Map.of("path", "new.md"), scope);
        assertEquals("Deleted new.md", deleted);
        // Reading a deleted file fails the call with the text the model reads.
        assertEquals("Error: File not found: new.md",
                failure(FileSystemTools.readFile(), Map.of("path", "new.md"), scope));
    }

    @Test
    public void editToolEnforcesUniqueMatch() throws Exception {
        var scope = scopeWithFs();
        exec(FileSystemTools.writeFile(), Map.of("path", "f.txt", "content", "dup dup"), scope);

        var multi = failure(FileSystemTools.editFile(),
                Map.of("path", "f.txt", "old_text", "dup", "new_text", "x"), scope);
        assertTrue(multi.startsWith("Error:"), multi);
        assertTrue(multi.contains("2 times"), multi);

        var zero = failure(FileSystemTools.editFile(),
                Map.of("path", "f.txt", "old_text", "absent", "new_text", "x"), scope);
        assertTrue(zero.contains("not found"), zero);

        var ok = exec(FileSystemTools.editFile(),
                Map.of("path", "f.txt", "old_text", "dup dup", "new_text", "once"), scope);
        assertEquals("Edited f.txt", ok);
        assertEquals("once", exec(FileSystemTools.readFile(), Map.of("path", "f.txt"), scope));
    }

    @Test
    public void globAndGrepReportMatchesAndEmptiness() throws Exception {
        var scope = scopeWithFs();
        exec(FileSystemTools.writeFile(),
                Map.of("path", "a.md", "content", "alpha\nbeta"), scope);
        exec(FileSystemTools.writeFile(),
                Map.of("path", "b.txt", "content", "beta"), scope);

        assertEquals("a.md", exec(FileSystemTools.glob(), Map.of("pattern", "*.md"), scope));
        assertEquals("(no matches)",
                exec(FileSystemTools.glob(), Map.of("pattern", "*.py"), scope));

        var grep = exec(FileSystemTools.grep(), Map.of("pattern", "beta"), scope).toString();
        assertTrue(grep.contains("a.md:2: beta"), grep);
        assertTrue(grep.contains("b.txt:1: beta"), grep);
        assertEquals("(no matches)",
                exec(FileSystemTools.grep(), Map.of("pattern", "gamma"), scope));
    }

    @Test
    public void traversalAndBoundsRejectionsFailTheCallWithErrorText() {
        var fs = new WorkspaceAgentFileSystem(tmp.resolve("bounded"),
                new AgentFileSystem.Limits(8, 10, 64));
        var scope = Map.<Class<?>, Object>of(AgentFileSystem.class, fs);

        var traversal = failure(FileSystemTools.readFile(),
                Map.of("path", "../secret"), scope);
        assertTrue(traversal.startsWith("Error:"), traversal);

        var tooBig = failure(FileSystemTools.writeFile(),
                Map.of("path", "f.txt", "content", "123456789"), scope);
        assertTrue(tooBig.startsWith("Error:"), tooBig);
        assertTrue(tooBig.contains("per-file limit"), tooBig);

        var missingArg = failure(FileSystemTools.readFile(), Map.of(), scope);
        assertTrue(missingArg.contains("'path' is required"), missingArg);
    }

    @Test
    public void unavailableFilesystemFailsTheCallWithAClearMessage() {
        var result = failure(FileSystemTools.ls(), Map.of(), Map.of());
        assertTrue(result.contains("no agent filesystem"), result);
    }

    @Test
    public void providerFallbackScopesByConversation() throws Exception {
        var provider = new AgentFileSystemProvider(tmp.resolve("agent"),
                AgentFileSystem.Limits.defaults());
        StreamingSession session = new CollectingSession("conv-9");
        var scope = Map.<Class<?>, Object>of(
                AgentFileSystemProvider.class, provider,
                StreamingSession.class, session);

        exec(FileSystemTools.writeFile(), Map.of("path", "f.txt", "content", "x"), scope);

        // The write landed under files/{conversationId}/ for the session's id.
        var scoped = provider.forConversation("conv-9");
        assertEquals("x", scoped.read("f.txt"));
        assertTrue(java.nio.file.Files.exists(
                tmp.resolve("agent").resolve("files").resolve("conv-9").resolve("f.txt")));

        // A different conversation sees an isolated store.
        var other = provider.forConversation("conv-10");
        assertTrue(other.ls(null).isEmpty());
    }

    @Test
    public void providerCachesPerConversation() {
        var provider = new AgentFileSystemProvider(tmp.resolve("agent"),
                AgentFileSystem.Limits.defaults());

        org.junit.jupiter.api.Assertions.assertSame(
                provider.forConversation("c1"), provider.forConversation("c1"),
                "the same conversation must share one store (locks + bounds accounting)");
        org.junit.jupiter.api.Assertions.assertNotSame(
                provider.forConversation("c1"), provider.forConversation("c2"));
    }
}
