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
package org.atmosphere.ai.workspace;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.governance.GovernancePolicies;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the OpenClaw extension files (RUNTIME.md, PERMISSIONS.md, SKILLS.md,
 * CHANNELS.md, MCP.md) actually reach their subsystems — not just that they
 * parse. Each test asserts the effect: {@link AiConfig} reflects RUNTIME.md, the
 * framework's policy bag (read by {@link GovernancePolicies#installed}) reflects
 * PERMISSIONS.md, and the augmented prompt carries the SKILLS / SOUL content.
 */
class WorkspaceExtensionsTest {

    @BeforeEach
    void neutralizeAmbientEnv() {
        // applyRuntime consults LLM_MODE/LLM_MODEL/... as operator overrides;
        // stub the env so a developer shell that exports them (e.g. via .envrc)
        // cannot leak into these RUNTIME.md-precedence assertions. Tests that
        // exercise an override set it via system property (checked first).
        WorkspaceExtensions.envReader = key -> null;
    }

    @AfterEach
    void restoreEnvReader() {
        WorkspaceExtensions.envReader = System::getenv;
    }

    @AfterEach
    void clearGenerationSysprops() {
        // applyRuntime feeds generation knobs through these sysprops; clear them
        // so they cannot leak into later tests' AiConfig.configure() resolution.
        System.clearProperty(AiConfig.TEMPERATURE_PROPERTY);
        System.clearProperty(AiConfig.MAX_TOKENS_PROPERTY);
        System.clearProperty(AiConfig.TOP_P_PROPERTY);
        System.clearProperty(AiConfig.STOP_PROPERTY);
        // applyRuntime now defers to these operator-override knobs; clear them
        // so an override set by one test cannot leak into another.
        System.clearProperty(AiConfig.LLM_MODE);
        System.clearProperty(AiConfig.LLM_MODEL);
        System.clearProperty(AiConfig.LLM_API_KEY);
        System.clearProperty(AiConfig.LLM_BASE_URL);
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    /** Minimal OpenClaw workspace: AGENTS.md is the recognition marker. */
    private static AgentDefinition loadFixture(Path root) throws IOException {
        write(root, "AGENTS.md", "Operate carefully.");
        write(root, "SOUL.md", "You are Atlas, a meticulous assistant.");
        var def = WorkspaceExtensions.load(root.toString()).orElse(null);
        assertNotNull(def, "OpenClaw workspace should load");
        return def;
    }

    @Test
    void loadReadsOpenClawExtensionFiles(@TempDir Path root) throws IOException {
        write(root, "RUNTIME.md", "model: gpt-4o");
        var def = loadFixture(root);
        assertEquals("openclaw", def.adapterName());
        assertTrue(def.atmosphereExtensions().containsKey("RUNTIME.md"),
                "RUNTIME.md should be read into atmosphereExtensions");
    }

    @Test
    void loadReturnsEmptyForMissingOrNull() {
        assertTrue(WorkspaceExtensions.load(null).isEmpty());
        assertTrue(WorkspaceExtensions.load("   ").isEmpty());
        assertTrue(WorkspaceExtensions.load("/no/such/atmosphere/workspace/dir").isEmpty());
    }

    @Test
    void applyRuntimeConfiguresAiConfig(@TempDir Path root) throws IOException {
        write(root, "RUNTIME.md", String.join("\n",
                "# Runtime",
                "mode: fake",
                "model: gpt-4o-mini",
                "temperature: 0.3",
                "max-tokens: 1234"));
        var def = loadFixture(root);

        assertTrue(WorkspaceExtensions.applyRuntime(def), "RUNTIME.md should apply");

        var settings = AiConfig.get();
        assertNotNull(settings, "AiConfig must be configured by RUNTIME.md");
        assertEquals("gpt-4o-mini", settings.model());
        assertEquals("fake", settings.mode());
        assertEquals(0.3, settings.generation().temperature());
        assertEquals(1234, settings.generation().maxTokens());
    }

    @Test
    void operatorModelOverrideBeatsRuntimeMdPin(@TempDir Path root) throws IOException {
        // An explicit LLM_MODEL override (system property here, the env var in
        // production) must win over a workspace RUNTIME.md model pin — otherwise
        // a shipped RUNTIME.md silently overrides an operator pointing the app
        // at a different model/endpoint (e.g. Ollama), producing a confusing
        // upstream 404 for the pinned (absent) model name.
        System.setProperty(AiConfig.LLM_MODEL, "qwen2.5:3b");
        write(root, "RUNTIME.md", "model: gemini-2.5-flash");
        var def = loadFixture(root);

        assertTrue(WorkspaceExtensions.applyRuntime(def), "RUNTIME.md should apply");

        assertEquals("qwen2.5:3b", AiConfig.get().model(),
                "operator LLM_MODEL override must win over the RUNTIME.md model pin");
    }

    @Test
    void permissionPoliciesParsesAllThreeDirectiveFamilies(@TempDir Path root) throws IOException {
        write(root, "PERMISSIONS.md", String.join("\n",
                "# Permissions",
                "deny: DROP TABLE",
                "- deny: rm -rf",
                "deny-regex: (?i)password",
                "allow: weather",
                "require-role: admin"));
        var def = loadFixture(root);

        var policies = WorkspaceExtensions.permissionPolicies(def);
        var names = policies.stream().map(GovernancePolicy::name).toList();
        assertEquals(3, policies.size(), "deny-list + allow-list + authorization");
        assertTrue(names.contains("workspace-deny-" + def.name()));
        assertTrue(names.contains("workspace-allow-" + def.name()));
        assertTrue(names.contains("workspace-authz-" + def.name()));
    }

    @Test
    void permissionPoliciesEmptyWhenProseOnly(@TempDir Path root) throws IOException {
        write(root, "PERMISSIONS.md", "This agent should be careful and kind.\n\nNo directives here.");
        var def = loadFixture(root);
        assertTrue(WorkspaceExtensions.permissionPolicies(def).isEmpty(),
                "prose-only PERMISSIONS.md yields no policies (logged at WARN)");
    }

    @Test
    void installPoliciesReachesGovernancePoliciesInstalled(@TempDir Path root) throws IOException {
        write(root, "PERMISSIONS.md", "deny: launch the missiles");
        var def = loadFixture(root);

        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        Map<String, Object> properties = new HashMap<>();
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);

        WorkspaceExtensions.installPolicies(framework, WorkspaceExtensions.permissionPolicies(def));

        // The same single-source-of-truth the @AiEndpoint / A2A / channel /
        // coordinator pipelines admit against (AgentProcessor wires this list).
        var installed = GovernancePolicies.installed(framework);
        assertTrue(installed.stream().anyMatch(p -> p.name().equals("workspace-deny-" + def.name())),
                "PERMISSIONS.md policy must be visible to GovernancePolicies.installed()");
    }

    @Test
    void installPoliciesDeduplicatesByName(@TempDir Path root) throws IOException {
        write(root, "PERMISSIONS.md", "deny: x");
        var def = loadFixture(root);

        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        Map<String, Object> properties = new HashMap<>();
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(properties);

        var policies = WorkspaceExtensions.permissionPolicies(def);
        WorkspaceExtensions.installPolicies(framework, policies);
        WorkspaceExtensions.installPolicies(framework, policies);

        assertEquals(1, GovernancePolicies.installed(framework).size(),
                "re-install must not double-add the same-named policy");
    }

    @Test
    void augmentSystemPromptAppendsWorkspaceAndSkills(@TempDir Path root) throws IOException {
        write(root, "SKILLS.md", "Use the calculator skill for arithmetic.");
        var skillDir = Files.createDirectories(root.resolve("skills").resolve("calculator"));
        write(skillDir, "SKILL.md", "Calculator: adds two numbers.");
        var def = loadFixture(root);

        var prompt = WorkspaceExtensions.augmentSystemPrompt("You are a host agent.", def);
        assertTrue(prompt.startsWith("You are a host agent."), "base prompt leads");
        assertTrue(prompt.contains("Atlas, a meticulous assistant"), "SOUL.md reaches the prompt");
        assertTrue(prompt.contains("Use the calculator skill"), "SKILLS.md reaches the prompt");
        assertTrue(prompt.contains("Calculator: adds two numbers"), "discovered SKILL.md reaches the prompt");
    }

    @Test
    void channelNamesParsedFromChannelsMd(@TempDir Path root) throws IOException {
        write(root, "CHANNELS.md", String.join("\n",
                "# Channels",
                "web",
                "- slack",
                "telegram: enable with TELEGRAM_BOT_TOKEN"));
        var def = loadFixture(root);
        assertEquals(List.of("web", "slack", "telegram"), WorkspaceExtensions.channelNames(def));
    }

    @Test
    void mcpServerUrisSkipsEntriesWithoutEndpoint(@TempDir Path root) throws IOException {
        write(root, "MCP.md", String.join("\n",
                "weather: https://mcp.example.com/weather",
                "github: credential-store-backed"));
        var def = loadFixture(root);

        var refs = WorkspaceExtensions.mcpServerUris(def);
        assertEquals(1, refs.size(), "only the entry with a real http(s) endpoint is kept");
        assertEquals("weather", refs.get(0).name());
        assertEquals("https://mcp.example.com/weather", refs.get(0).uri().toString());
        assertFalse(refs.stream().anyMatch(r -> r.name().equals("github")));
    }
}
