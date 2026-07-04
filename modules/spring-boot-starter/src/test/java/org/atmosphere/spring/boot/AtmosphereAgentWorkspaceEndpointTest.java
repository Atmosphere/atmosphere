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

import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.AgentFileSystemProvider;
import org.atmosphere.ai.plan.AgentPlan;
import org.atmosphere.ai.plan.FileSystemAgentPlanStore;
import org.atmosphere.ai.plan.PlanStatus;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the read-only workspace admin surface
 * ({@code /api/admin/workspace/owners}, {@code /api/admin/agents/{name}/plan},
 * {@code /api/admin/agents/{name}/files[/content]}): resolution goes through
 * the exact {@link HarnessPreset} registries the core attach engines populate
 * (Runtime Truth — an owner without a registered surface answers 404 even if
 * its workspace directory exists on disk), plan JSON matches the
 * {@code plan-update} wire shape, and traversal-shaped session ids / paths are
 * rejected as 400, never 500 (Correctness Invariant #4).
 */
class AtmosphereAgentWorkspaceEndpointTest {

    private static final String OWNER = "assistant";
    private static final String SESSION = "conv-1";

    @TempDir
    Path workspace;

    private HarnessPreset preset;
    private FileSystemAgentPlanStore planStore;
    private AgentFileSystemProvider fsProvider;
    private MockMvc mockMvc;
    private MockMvc mockMvcNoPreset;

    @BeforeEach
    void setUp() {
        // Same fixture shape as HarnessPresetTest (modules/ai): a mocked
        // framework whose config carries a real property bag, so
        // HarnessPreset.install publishes into it exactly as in production.
        var framework = mock(AtmosphereFramework.class);
        var cfg = mock(AtmosphereConfig.class);
        when(cfg.properties()).thenReturn(new ConcurrentHashMap<>());
        when(framework.getAtmosphereConfig()).thenReturn(cfg);
        preset = HarnessPreset.install(framework);

        planStore = new FileSystemAgentPlanStore(workspace);
        fsProvider = new AgentFileSystemProvider(workspace, AgentFileSystem.Limits.defaults());
        preset.registerPlanStore(OWNER, planStore);
        preset.registerFileSystemProvider(OWNER, fsProvider);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AtmosphereAgentWorkspaceEndpoint(framework))
                .build();

        // A framework whose preset never installed — every endpoint must 404
        // (owners: empty list) instead of reconstructing a surface from disk.
        var bareFramework = mock(AtmosphereFramework.class);
        var bareCfg = mock(AtmosphereConfig.class);
        when(bareCfg.properties()).thenReturn(new ConcurrentHashMap<>());
        when(bareFramework.getAtmosphereConfig()).thenReturn(bareCfg);
        mockMvcNoPreset = MockMvcBuilders
                .standaloneSetup(new AtmosphereAgentWorkspaceEndpoint(bareFramework))
                .build();
    }

    // ── Discovery ──

    @Test
    void ownersListsRegisteredSurfacesWithTheirCapabilities() throws Exception {
        mockMvc.perform(get("/api/admin/workspace/owners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].owner").value(OWNER))
                .andExpect(jsonPath("$[0].plan").value(true))
                .andExpect(jsonPath("$[0].filesystem").value(true));
    }

    @Test
    void ownersIsEmptyWhenThePresetNeverInstalled() throws Exception {
        mockMvcNoPreset.perform(get("/api/admin/workspace/owners"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Plan ──

    @Test
    void planReturnsTheStoredPlanInWireShape() throws Exception {
        planStore.put(OWNER, SESSION, new AgentPlan("Ship the feature", List.of(
                new AgentPlan.Step("Write code", PlanStatus.COMPLETED, null),
                new AgentPlan.Step("Run tests", PlanStatus.IN_PROGRESS, "Running tests"))));

        mockMvc.perform(get("/api/admin/agents/{name}/plan", OWNER)
                        .param("sessionId", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent").value(OWNER))
                .andExpect(jsonPath("$.sessionId").value(SESSION))
                .andExpect(jsonPath("$.goal").value("Ship the feature"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].content").value("Write code"))
                .andExpect(jsonPath("$.steps[0].status").value("completed"))
                .andExpect(jsonPath("$.steps[1].status").value("in_progress"))
                .andExpect(jsonPath("$.steps[1].activeForm").value("Running tests"));
    }

    @Test
    void planIs404WhenNoPlanWasWrittenYet() throws Exception {
        mockMvc.perform(get("/api/admin/agents/{name}/plan", OWNER)
                        .param("sessionId", "never-planned"))
                .andExpect(status().isNotFound());
    }

    @Test
    void planIs404ForAnOwnerWithoutAPlanSurface() throws Exception {
        mockMvc.perform(get("/api/admin/agents/{name}/plan", "unknown-agent")
                        .param("sessionId", SESSION))
                .andExpect(status().isNotFound());
    }

    @Test
    void planRejectsTraversalSessionIdsAs400() throws Exception {
        mockMvc.perform(get("/api/admin/agents/{name}/plan", OWNER)
                        .param("sessionId", "../escape"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Files ──

    @Test
    void filesListsTheConversationWorkspace() throws Exception {
        var fs = fsProvider.forConversation(SESSION);
        fs.write("notes/todo.md", "- [ ] review");
        fs.write("report.txt", "done");

        mockMvc.perform(get("/api/admin/agents/{name}/files", OWNER)
                        .param("sessionId", SESSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent").value(OWNER))
                .andExpect(jsonPath("$.path").value("."))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].path").value("notes"))
                .andExpect(jsonPath("$.entries[0].directory").value(true))
                .andExpect(jsonPath("$.entries[1].path").value("report.txt"))
                .andExpect(jsonPath("$.entries[1].directory").value(false));

        mockMvc.perform(get("/api/admin/agents/{name}/files", OWNER)
                        .param("sessionId", SESSION)
                        .param("path", "notes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].path").value("notes/todo.md"));
    }

    @Test
    void filesIs404WhenTheConversationNeverWroteAFile() throws Exception {
        mockMvc.perform(get("/api/admin/agents/{name}/files", OWNER)
                        .param("sessionId", "no-such-conversation"))
                .andExpect(status().isNotFound());

        // The probe must not have provisioned a workspace for the probed id —
        // admin reads never create attacker-fillable directories (Invariant #3).
        org.junit.jupiter.api.Assertions.assertFalse(
                java.nio.file.Files.exists(
                        workspace.resolve("files").resolve("no-such-conversation")),
                "an admin read must never create a conversation workspace");
    }

    @Test
    void filesIs404ForAnOwnerWithoutAFileSurface() throws Exception {
        mockMvc.perform(get("/api/admin/agents/{name}/files", "unknown-agent")
                        .param("sessionId", SESSION))
                .andExpect(status().isNotFound());
    }

    @Test
    void filesRejectsTraversalSessionIdsAs400() throws Exception {
        mockMvc.perform(get("/api/admin/agents/{name}/files", OWNER)
                        .param("sessionId", "../other-agent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void fileContentReturnsTheStoredText() throws Exception {
        fsProvider.forConversation(SESSION).write("notes/todo.md", "- [ ] review");

        mockMvc.perform(get("/api/admin/agents/{name}/files/content", OWNER)
                        .param("sessionId", SESSION)
                        .param("path", "notes/todo.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("notes/todo.md"))
                .andExpect(jsonPath("$.content").value("- [ ] review"));
    }

    @Test
    void fileContentIs404ForAMissingFile() throws Exception {
        fsProvider.forConversation(SESSION).write("exists.txt", "x");

        mockMvc.perform(get("/api/admin/agents/{name}/files/content", OWNER)
                        .param("sessionId", SESSION)
                        .param("path", "missing.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fileContentRejectsTraversalPathsAs400() throws Exception {
        fsProvider.forConversation(SESSION).write("exists.txt", "x");

        mockMvc.perform(get("/api/admin/agents/{name}/files/content", OWNER)
                        .param("sessionId", SESSION)
                        .param("path", "../../plans/secret.json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
