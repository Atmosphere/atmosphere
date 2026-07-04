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
package org.atmosphere.spring.boot;

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.AgentFileSystemProvider;
import org.atmosphere.ai.plan.AgentPlanStore;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.cpr.AtmosphereFramework;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Read-only admin REST surface for the harness PLANNING / FILESYSTEM
 * primitives: the current {@code AgentPlan} and the conversation-scoped agent
 * workspace files, resolved through the exact {@link AgentPlanStore} /
 * {@link AgentFileSystemProvider} instances the core attach engines
 * ({@code PlanningPreset} / {@code FilesystemPreset}) registered on the
 * installed {@link HarnessPreset} — never a reconstructed twin (Runtime
 * Truth, Invariant #5: an owner without a genuinely attached surface answers
 * 404 here even if its workspace directory happens to exist on disk).
 *
 * <p>All endpoints are GETs under {@code /api/admin/} — they inherit the
 * opt-in {@code atmosphere.admin.http-read-auth-required} token gate the
 * admin API filter applies to the whole {@code /api/admin/*} space, matching
 * the posture of the other admin read endpoints. Malformed session ids or
 * paths (traversal, separators) are rejected by the core stores' boundary
 * validation and surface as {@code 400}, never {@code 500}
 * (Invariant #4).</p>
 *
 * <p>Owner names are the registration-time keys the processors used: the
 * agent name for {@code @Agent} / {@code @Coordinator}, the sanitized
 * endpoint path (e.g. {@code atmosphere-ai-chat}) for {@code @AiEndpoint}.
 * {@code GET /api/admin/workspace/owners} lists them with their attached
 * surfaces so the console discovers what is browsable.</p>
 *
 * @since 4.1
 */
@AutoConfiguration
@RestController
@RequestMapping("/api/admin")
@ConditionalOnBean(AtmosphereAdmin.class)
@ConditionalOnClass(HarnessPreset.class)
public class AtmosphereAgentWorkspaceEndpoint {

    private final AtmosphereFramework framework;

    public AtmosphereAgentWorkspaceEndpoint(AtmosphereFramework framework) {
        this.framework = framework;
    }

    // ── Discovery ──

    /**
     * The owners with a registered plan store and/or filesystem provider —
     * i.e. the workspaces this surface can actually serve. Empty when the
     * harness preset never ran or no PLANNING / FILESYSTEM primitive attached.
     */
    @GetMapping("/workspace/owners")
    public java.util.List<Map<String, Object>> listWorkspaceOwners() {
        var owners = new ArrayList<Map<String, Object>>();
        var preset = installedPreset();
        if (preset.isEmpty()) {
            return owners;
        }
        var names = new TreeSet<String>();
        names.addAll(preset.get().planOwners());
        names.addAll(preset.get().fileSystemOwners());
        for (var name : names) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("owner", name);
            entry.put("plan", preset.get().planStore(name).isPresent());
            entry.put("filesystem", preset.get().fileSystemProvider(name).isPresent());
            owners.add(entry);
        }
        return owners;
    }

    // ── Plan ──

    /**
     * The current plan for one agent × conversation, in the same wire shape
     * as the {@code plan-update} streaming event ({@code steps} entries carry
     * {@code content} / lower-cased {@code status} / optional
     * {@code activeForm}) so the console renders both identically. {@code 404}
     * when no plan surface is attached for the owner or no plan was written
     * for the session yet.
     */
    @GetMapping("/agents/{name}/plan")
    public ResponseEntity<Map<String, Object>> getAgentPlan(
            @PathVariable("name") String name,
            @RequestParam("sessionId") String sessionId) {
        var store = installedPreset().flatMap(p -> p.planStore(name));
        if (store.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            return store.get().get(name, sessionId)
                    .map(plan -> {
                        // Explicitly Map-typed: var would infer LinkedHashMap and
                        // make ok(body) incompatible with the notFound() branch.
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("agent", name);
                        body.put("sessionId", sessionId);
                        if (plan.goal() != null && !plan.goal().isBlank()) {
                            body.put("goal", plan.goal());
                        }
                        body.put("steps", plan.toWireSteps());
                        return ResponseEntity.ok(body);
                    })
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            // Boundary rejection (blank / traversal session id) — 400, not 500.
            return badRequest(e);
        }
    }

    // ── Files ──

    /**
     * List the entries of one conversation's workspace subtree
     * ({@code files/{sessionId}/} under the owner's workspace root).
     * {@code path} is optional — absent lists the root. {@code 404} when no
     * file surface is attached for the owner or the conversation has no
     * workspace yet; {@code 400} on a traversal-shaped session id or path.
     */
    @GetMapping("/agents/{name}/files")
    public ResponseEntity<Map<String, Object>> listAgentFiles(
            @PathVariable("name") String name,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "path", required = false) String path) {
        var provider = installedPreset().flatMap(p -> p.fileSystemProvider(name));
        if (provider.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            var fs = conversationFs(provider.get(), sessionId);
            if (fs.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            var entries = new ArrayList<Map<String, Object>>();
            for (var info : fs.get().ls(path)) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("path", info.path());
                entry.put("size", info.size());
                entry.put("directory", info.directory());
                entries.add(entry);
            }
            var body = new LinkedHashMap<String, Object>();
            body.put("agent", name);
            body.put("sessionId", sessionId);
            body.put("path", path == null || path.isBlank() ? "." : path);
            body.put("entries", entries);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return badRequest(e);
        }
    }

    /**
     * Read one workspace file's UTF-8 content. {@code 404} when no file
     * surface is attached, the conversation has no workspace, or the file
     * does not exist; {@code 400} on traversal or an over-limit file.
     */
    @GetMapping("/agents/{name}/files/content")
    public ResponseEntity<Map<String, Object>> readAgentFile(
            @PathVariable("name") String name,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("path") String path) {
        var provider = installedPreset().flatMap(p -> p.fileSystemProvider(name));
        if (provider.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            var fs = conversationFs(provider.get(), sessionId);
            if (fs.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            var content = fs.get().read(path);
            var body = new LinkedHashMap<String, Object>();
            body.put("agent", name);
            body.put("sessionId", sessionId);
            body.put("path", path);
            body.put("content", content);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            // The store throws the same exception type for "missing file" and
            // for boundary rejections; a missing file is an addressable-but-
            // absent resource → 404, everything else is caller error → 400.
            if (e.getMessage() != null && e.getMessage().startsWith("File not found")) {
                return ResponseEntity.notFound().build();
            }
            return badRequest(e);
        }
    }

    /**
     * The conversation-scoped store, or empty when the conversation never
     * wrote a file. Existence is checked <em>before</em> asking the provider
     * so an admin read can never create {@code files/{sessionId}/} directories
     * for arbitrary probed ids (an unbounded, attacker-fillable directory tree
     * — Invariant #3; {@code WorkspaceAgentFileSystem}'s constructor creates
     * its root eagerly). The session id is validated as a single path segment
     * first (Invariant #4).
     */
    private Optional<AgentFileSystem> conversationFs(AgentFileSystemProvider provider,
                                                     String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sessionId.contains("/") || sessionId.contains("\\") || sessionId.contains("..")) {
            throw new IllegalArgumentException(
                    "sessionId contains illegal path characters: " + sessionId);
        }
        var conversationRoot = provider.agentRoot().resolve("files").resolve(sessionId)
                .toAbsolutePath().normalize();
        if (!conversationRoot.startsWith(provider.agentRoot().toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("sessionId escapes the workspace: " + sessionId);
        }
        if (!Files.isDirectory(conversationRoot)) {
            return Optional.empty();
        }
        return Optional.of(provider.forConversation(sessionId));
    }

    /**
     * The harness preset the core installer stashed in the framework property
     * bag, or empty when the preset never ran (no AI endpoints registered).
     */
    private Optional<HarnessPreset> installedPreset() {
        var cfg = framework != null ? framework.getAtmosphereConfig() : null;
        if (cfg == null) {
            return Optional.empty();
        }
        if (cfg.properties().get(HarnessPreset.PRESET_PROPERTY) instanceof HarnessPreset preset) {
            return Optional.of(preset);
        }
        return Optional.empty();
    }

    private ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage() != null ? e.getMessage() : "invalid request"));
    }
}
