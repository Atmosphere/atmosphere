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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.tools.file.DefaultFileChangeLog
import com.embabel.agent.tools.file.DefaultFileReadLog
import com.embabel.agent.tools.file.FileChangeLog
import com.embabel.agent.tools.file.FileModification
import com.embabel.agent.tools.file.FileModificationType
import com.embabel.agent.tools.file.FileReadLog
import com.embabel.agent.tools.file.FileTools
import com.embabel.common.util.StringTransformer
import org.atmosphere.ai.fs.AgentFileSystem
import org.atmosphere.ai.fs.WorkspaceAgentFileSystem
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Embabel's native file-tool surface ([FileTools]) rooted at the SAME
 * directory Atmosphere's conversation-scoped [WorkspaceAgentFileSystem]
 * manages — the store the built-in `ls`/`read_file`/`write_file` tool floor
 * and the console Workspace tab read. Registered per dispatch on the
 * Atmosphere-native path by [EmbabelAgentRuntime], this is what makes
 * `AiCapability.VIRTUAL_FILESYSTEM` an honest declaration on the Embabel
 * runtime (Correctness Invariant #5 — Runtime Truth).
 *
 * <h4>Bounds honesty (Correctness Invariant #3)</h4>
 * A raw `FileTools.readWrite(root)` would bypass [AgentFileSystem.Limits] —
 * Embabel's default write methods hit the disk directly (containment-guarded,
 * but with no byte/count caps). This class therefore keeps Embabel's tool
 * *surface* (the same self-published `@LlmTool` methods, names, and
 * [FileChangeLog] audit) but overrides every mutating method — and the
 * model-facing `readFile` — to route through the [WorkspaceAgentFileSystem],
 * so the per-file / file-count / total-byte bounds and the traversal guards
 * are enforced identically to the built-in tool floor. Overrides re-declare
 * `@LlmTool` because Embabel's scanner reads annotations with plain
 * reflection (no annotation inheritance on methods).
 *
 * The non-mutating helpers ([listFiles][com.embabel.agent.tools.file.FileReadTools.listFiles],
 * [findFiles][com.embabel.agent.tools.file.FileReadTools.findFiles],
 * [fileSize][com.embabel.agent.tools.file.FileReadTools.fileSize],
 * [fileCount][com.embabel.agent.tools.file.FileReadTools.fileCount]) keep
 * Embabel's default implementations: they are read-only and containment-guarded
 * by Embabel's own `resolvePath` (`SecurityException` on escape), and [root]
 * points at the bounded conversation directory.
 *
 * `editFile` follows the store's exactly-once-match contract (the same
 * semantics as the built-in `edit_file` floor — Correctness Invariant #7,
 * Mode Parity) rather than Embabel's default replace-all; the re-declared
 * tool description says so.
 *
 * One instance is dispatch-scoped and dies with the request's session — the
 * [FileChangeLog] it accumulates is the per-run audit
 * [EmbabelAgentRuntime.emitFileChangeAudit] mirrors onto the wire.
 */
internal class AtmosphereFileTools private constructor(
    private val store: WorkspaceAgentFileSystem
) : FileTools,
    FileReadLog by DefaultFileReadLog(),
    FileChangeLog by DefaultFileChangeLog() {

    companion object {

        /**
         * Wrap the scoped store when it exposes a real disk root. Embabel's
         * [FileTools] contract is real-disk only ([root] is a filesystem
         * path), so a custom [AgentFileSystem] implementation without a disk
         * root cannot be bridged — this returns `null` and the caller logs
         * the skip loudly instead of faking a surface.
         */
        @JvmStatic
        fun forStore(fs: AgentFileSystem?): AtmosphereFileTools? =
            (fs as? WorkspaceAgentFileSystem)?.let { AtmosphereFileTools(it) }
    }

    private val storeRoot: Path = store.root()

    override val root: String = storeRoot.toString()

    override val fileContentTransformers: List<StringTransformer> = emptyList()

    // ---------- Model-facing read surface (store-routed) ----------

    @LlmTool(description = "Read the whole file at the relative path")
    override fun readFile(path: String): String {
        val content = store.read(path)
        recordRead(path)
        return content
    }

    // ---------- Model-facing write surface (store-routed, bounds-enforced) ----------

    @LlmTool(
        description = "Create a new file at the given relative path with the given content. " +
            "Fails if the file already exists. Use writeFile to overwrite existing files. " +
            "Writes are size-bounded; an over-limit write is rejected with the reason."
    )
    override fun createFile(path: String, content: String): String {
        if (isRegularFile(path)) {
            throw IllegalArgumentException(
                "File already exists: $path — use writeFile to overwrite it"
            )
        }
        store.write(path, content)
        recordChange(FileModification(path, FileModificationType.CREATE))
        return "file created"
    }

    override fun createFile(path: String, content: String, overwrite: Boolean): Path {
        val existed = isRegularFile(path)
        if (existed && !overwrite) {
            throw IllegalArgumentException("File already exists: $path")
        }
        store.write(path, content)
        recordChange(FileModification(path,
            if (existed) FileModificationType.EDIT else FileModificationType.CREATE))
        return resolveContained(path)
    }

    @LlmTool(
        description = "Write content to a file at the given relative path, replacing its " +
            "entire contents if it exists or creating it if it doesn't. Writes are " +
            "size-bounded; an over-limit write is rejected with the reason."
    )
    override fun writeFile(path: String, content: String): String {
        val existed = isRegularFile(path)
        store.write(path, content)
        recordChange(FileModification(path,
            if (existed) FileModificationType.EDIT else FileModificationType.CREATE))
        return "file written"
    }

    @LlmTool(
        description = "Edit the file at the given relative path by replacing oldContent " +
            "with newContent. oldContent must match EXACTLY ONCE — include enough " +
            "surrounding context to make it unique; zero or multiple matches are errors."
    )
    override fun editFile(path: String, oldContent: String, newContent: String): String {
        store.edit(path, oldContent, newContent)
        recordChange(FileModification(path, FileModificationType.EDIT))
        return "file edited"
    }

    @LlmTool(
        description = "Append content to the end of the file at the given relative path. " +
            "The file must already exist. The combined size is bounds-checked."
    )
    override fun appendFile(path: String, content: String): String {
        appendToFile(path, content, false)
        return "content appended to file"
    }

    override fun appendToFile(path: String, content: String, createIfNotExists: Boolean) {
        val exists = isRegularFile(path)
        if (!exists && !createIfNotExists) {
            throw IllegalArgumentException("File not found: $path")
        }
        val existing = if (exists) store.read(path) else ""
        store.write(path, existing + content)
        recordChange(FileModification(path,
            if (exists) FileModificationType.APPEND else FileModificationType.CREATE))
    }

    @LlmTool(
        description = "Create a directory at the given relative path. Parent directories " +
            "are created as needed."
    )
    override fun createDirectory(path: String): String {
        val target = resolveContained(path)
        if (Files.isRegularFile(target)) {
            throw IllegalArgumentException("A file already exists at this path: $path")
        }
        if (Files.isDirectory(target)) {
            return "directory already exists"
        }
        try {
            Files.createDirectories(target)
        } catch (e: IOException) {
            throw UncheckedIOException("Failed to create directory $path", e)
        }
        recordChange(FileModification(path, FileModificationType.CREATE_DIRECTORY))
        return "directory created"
    }

    @LlmTool(
        description = "Delete the file or directory at the given relative path " +
            "(directories are deleted together with everything under them)."
    )
    override fun delete(path: String): String {
        store.delete(path)
        recordChange(FileModification(path, FileModificationType.DELETE))
        return "file deleted"
    }

    // ---------- Helpers ----------

    /**
     * Existence probe for the pre-write checks above. Containment is
     * re-validated here so the probe can never leak information about paths
     * outside the store (Correctness Invariant #4); the store's own
     * `validatePath` guards then re-check the path on the actual mutation.
     */
    private fun isRegularFile(path: String): Boolean = Files.isRegularFile(resolveContained(path))

    private fun resolveContained(path: String): Path {
        val resolved = storeRoot.resolve(path.trim()).normalize()
        if (!resolved.startsWith(storeRoot)) {
            throw IllegalArgumentException("Path escapes the store root: $path")
        }
        return resolved
    }
}
