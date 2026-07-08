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
package org.atmosphere.ai.embabel

import org.atmosphere.ai.AiConfig
import org.atmosphere.ai.StreamingSession
import org.atmosphere.ai.fs.AgentFileSystem
import org.atmosphere.ai.fs.AgentFileSystemProvider
import org.atmosphere.ai.fs.WorkspaceAgentFileSystem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.nio.file.Path

/**
 * Pins the native virtual-filesystem wiring on the Embabel runtime
 * (`AiCapability.VIRTUAL_FILESYSTEM`, Correctness Invariant #5 — Runtime
 * Truth): [AtmosphereFileTools] exposes Embabel's `FileTools` tool surface
 * rooted at the SAME conversation-scoped directory the harness
 * [WorkspaceAgentFileSystem] manages, with every mutation routed back
 * through the store so [AgentFileSystem.Limits] and the traversal guards
 * hold on the native surface too (Invariants #3/#4). Also pins the negative
 * space: BUILTIN mode, fs-free sessions, and disk-root-free custom stores
 * leave the native surface off.
 */
internal class EmbabelAgentRuntimeVfsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fileSystem: WorkspaceAgentFileSystem

    @BeforeEach
    fun setUp() {
        fileSystem = WorkspaceAgentFileSystem.forConversation(
            tempDir, "conv-1", AgentFileSystem.Limits.defaults())
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(AiConfig.FILESYSTEM_PROPERTY)
    }

    // ---------- Round trip: same directory, both surfaces ----------

    @Test
    fun fileToolsWriteIsVisibleThroughAgentFileSystem() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!

        assertEquals("file written", tools.writeFile("notes.md", "bridged"))

        assertEquals("bridged", fileSystem.read("notes.md"),
            "an Embabel FileTools write must land in the store AgentFileSystem manages")
    }

    @Test
    fun agentFileSystemWriteIsVisibleThroughFileTools() {
        fileSystem.write("seed.md", "from-store")

        val tools = AtmosphereFileTools.forStore(fileSystem)!!

        assertEquals("from-store", tools.readFile("seed.md"),
            "a store write must be readable through Embabel's native surface")
    }

    // ---------- Bounds: Limits hold on the native surface ----------

    @Test
    fun perFileByteLimitEnforcedThroughNativeSurface() {
        val bounded = WorkspaceAgentFileSystem(
            tempDir.resolve("bounded"), AgentFileSystem.Limits(16, 4, 64))
        val tools = AtmosphereFileTools.forStore(bounded)!!

        val e = assertThrows(IllegalArgumentException::class.java) {
            tools.writeFile("big.md", "x".repeat(17))
        }

        assertTrue(e.message!!.contains("per-file limit"),
            "the store's per-file rejection must surface through FileTools: ${e.message}")
    }

    @Test
    fun fileCountLimitEnforcedThroughNativeSurface() {
        val bounded = WorkspaceAgentFileSystem(
            tempDir.resolve("counted"), AgentFileSystem.Limits(16, 2, 64))
        val tools = AtmosphereFileTools.forStore(bounded)!!
        tools.writeFile("a.md", "1")
        tools.writeFile("b.md", "2")

        val e = assertThrows(IllegalArgumentException::class.java) {
            tools.createFile("c.md", "3")
        }

        assertTrue(e.message!!.contains("-file limit"),
            "the store's file-count rejection must surface through FileTools: ${e.message}")
    }

    @Test
    fun appendIsBoundsCheckedOnTheCombinedSize() {
        val bounded = WorkspaceAgentFileSystem(
            tempDir.resolve("appended"), AgentFileSystem.Limits(16, 4, 64))
        val tools = AtmosphereFileTools.forStore(bounded)!!
        tools.writeFile("log.md", "0123456789")

        assertEquals("content appended to file", tools.appendFile("log.md", "abc"))
        assertEquals("0123456789abc", bounded.read("log.md"))
        assertThrows(IllegalArgumentException::class.java) {
            tools.appendFile("log.md", "0123456789")
        }
    }

    // ---------- Boundary safety ----------

    @Test
    fun traversalRejectedThroughNativeSurface() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!

        assertThrows(IllegalArgumentException::class.java) {
            tools.writeFile("../escape.md", "outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.readFile("../../etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.delete("..")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.createDirectory("../side")
        }
    }

    // ---------- Tool semantics (mode parity with the built-in floor) ----------

    @Test
    fun editFileRequiresExactlyOneMatch() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!
        tools.writeFile("draft.md", "alpha beta alpha")

        assertThrows(IllegalArgumentException::class.java) {
            tools.editFile("draft.md", "alpha", "gamma")
        }
        assertThrows(IllegalArgumentException::class.java) {
            tools.editFile("draft.md", "missing", "gamma")
        }

        assertEquals("file edited", tools.editFile("draft.md", "beta", "gamma"))
        assertEquals("alpha gamma alpha", fileSystem.read("draft.md"))
    }

    @Test
    fun createFileFailsWhenPresentAndDeleteRemoves() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!

        assertEquals("file created", tools.createFile("once.md", "v1"))
        assertThrows(IllegalArgumentException::class.java) {
            tools.createFile("once.md", "v2")
        }
        assertEquals("v1", fileSystem.read("once.md"))

        assertEquals("file deleted", tools.delete("once.md"))
        assertThrows(IllegalArgumentException::class.java) { fileSystem.read("once.md") }
    }

    @Test
    fun appendFileRequiresAnExistingFile() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!

        assertThrows(IllegalArgumentException::class.java) {
            tools.appendFile("absent.md", "tail")
        }
    }

    @Test
    fun createDirectoryIsIdempotentAndConflictChecked() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!

        assertEquals("directory created", tools.createDirectory("docs/site"))
        assertEquals("directory already exists", tools.createDirectory("docs/site"))

        tools.writeFile("plain.md", "x")
        assertThrows(IllegalArgumentException::class.java) {
            tools.createDirectory("plain.md")
        }
    }

    // ---------- Self-publication (SelfToolPublisher → @LlmTool scan) ----------

    @Test
    fun selfPublishedToolsCoverTheFileSurface() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!

        val names = tools.tools.map { it.definition.name }

        // Store-routed overrides — re-annotated @LlmTool methods must publish.
        for (expected in listOf("createFile", "writeFile", "editFile", "appendFile",
                "delete", "createDirectory", "readFile")) {
            assertTrue(expected in names,
                "bounded override '$expected' must self-publish: $names")
        }
        // Inherited Embabel read helpers keep their default publication.
        for (expected in listOf("listFiles", "findFiles")) {
            assertTrue(expected in names,
                "inherited Embabel read tool '$expected' must self-publish: $names")
        }
    }

    // ---------- FileChangeLog audit ----------

    @Test
    fun changeLogRecordsNetChangePerPath() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!
        tools.writeFile("a.md", "1")
        tools.writeFile("b.md", "2")
        tools.appendFile("a.md", "3")
        tools.delete("b.md")

        val entries = tools.getChanges().map { "${it.type}:${it.path}" }

        // Embabel's DefaultFileChangeLog audit contract: one NET entry per
        // path — a later different-typed change replaces the earlier one and
        // moves the path to the end of the log.
        assertEquals(listOf("APPEND:a.md", "DELETE:b.md"), entries)
    }

    @Test
    fun changeAuditEmitsOneMetadataFrame() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!
        tools.writeFile("a.md", "1")
        tools.writeFile("b.md", "2")
        tools.delete("b.md")
        val recorded = mutableListOf<Pair<String, Any>>()

        EmbabelAgentRuntime.emitFileChangeAudit(tools, sessionWith(mapOf(), recorded))

        assertEquals(1, recorded.size)
        assertEquals("ai.embabel.file_changes", recorded[0].first)
        assertEquals("CREATE:a.md, DELETE:b.md", recorded[0].second.toString())
    }

    @Test
    fun changeAuditIsSilentWhenNothingChanged() {
        val tools = AtmosphereFileTools.forStore(fileSystem)!!
        val recorded = mutableListOf<Pair<String, Any>>()

        EmbabelAgentRuntime.emitFileChangeAudit(tools, sessionWith(mapOf(), recorded))
        EmbabelAgentRuntime.emitFileChangeAudit(null, sessionWith(mapOf(), recorded))

        assertTrue(recorded.isEmpty(), "no mutations → no metadata frame: $recorded")
    }

    // ---------- Resolution: mode + scope negative space ----------

    @Test
    fun builtinModeSuppressesTheNativeSurface() {
        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "builtin")

        assertNull(EmbabelAgentRuntime.resolveAgentFileSystem(
                sessionWith(mapOf(AgentFileSystem::class.java to fileSystem))),
            "BUILTIN mode: the portable tool floor owns the surface — wiring the "
                + "native bridge too would duplicate the file tools")
    }

    @Test
    fun autoDefaultSuppressesTheNativeSurface() {
        // The runtime does not declare VIRTUAL_FILESYSTEM (the deployed path
        // has no per-process tool surface), so the native bridge is an
        // explicit atmosphere.ai.filesystem=native opt-in — under the AUTO
        // default the eight-tool floor owns every dispatch path.
        assertNull(EmbabelAgentRuntime.resolveAgentFileSystem(
                sessionWith(mapOf(AgentFileSystem::class.java to fileSystem))),
            "AUTO default: the portable tool floor owns the surface")
    }

    @Test
    fun fsFreeSessionResolvesNoStore() {
        assertNull(EmbabelAgentRuntime.resolveAgentFileSystem(sessionWith(mapOf())),
            "harness FILESYSTEM off: nothing in the tool scope, no native surface")
        assertNull(EmbabelAgentRuntime.resolveAgentFileSystem(null))
    }

    @Test
    fun directStoreWinsOverProvider() {
        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "native")
        val provider = AgentFileSystemProvider(tempDir, AgentFileSystem.Limits.defaults())

        val resolved = EmbabelAgentRuntime.resolveAgentFileSystem(sessionWith(mapOf(
            AgentFileSystem::class.java to fileSystem,
            AgentFileSystemProvider::class.java to provider)))

        assertSame(fileSystem, resolved,
            "the dispatch-seam-published store must win over the registration-time provider")
    }

    @Test
    fun providerFallbackDerivesConversationScopedStore() {
        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "native")
        val provider = AgentFileSystemProvider(tempDir, AgentFileSystem.Limits.defaults())

        val resolved = EmbabelAgentRuntime.resolveAgentFileSystem(sessionWith(mapOf(
            AgentFileSystemProvider::class.java to provider)))

        assertSame(provider.forConversation("default"), resolved,
            "no resource/session identity in scope collapses to the provider's "
                + "default conversation view")
    }

    @Test
    fun diskRootFreeStoresCannotBeBridged() {
        assertNull(AtmosphereFileTools.forStore(null))
        assertNull(AtmosphereFileTools.forStore(mock(AgentFileSystem::class.java)),
            "Embabel FileTools is real-disk only — a custom AgentFileSystem without "
                + "a disk root must not be faked into a native surface")
    }

    // ---------- Helpers ----------

    private fun sessionWith(
        injectables: Map<Class<*>, Any>,
        metadata: MutableList<Pair<String, Any>> = mutableListOf()
    ): StreamingSession = object : StreamingSession {
        override fun sessionId(): String = "session-1"

        override fun injectables(): Map<Class<*>, Any> = injectables

        override fun send(text: String) {
        }

        override fun sendMetadata(key: String, value: Any) {
            metadata.add(key to value)
        }

        override fun progress(message: String) {
        }

        override fun complete() {
        }

        override fun complete(summary: String) {
        }

        override fun error(t: Throwable) {
        }

        override fun isClosed(): Boolean = false
    }
}
