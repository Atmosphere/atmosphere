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
package org.atmosphere.ai;

import org.atmosphere.ai.extensibility.DynamicToolSelector;
import org.atmosphere.ai.extensibility.McpTrustProvider;
import org.atmosphere.ai.extensibility.ToolDescriptor;
import org.atmosphere.ai.extensibility.ToolExtensibilityPoint;
import org.atmosphere.ai.extensibility.ToolIndex;
import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.atmosphere.ai.identity.AgentIdentity;
import org.atmosphere.ai.identity.InMemoryAgentIdentity;
import org.atmosphere.ai.identity.InMemoryCredentialStore;
import org.atmosphere.ai.identity.PermissionMode;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.state.AgentState;
import org.atmosphere.ai.state.FileSystemAgentState;
import org.atmosphere.ai.workspace.AgentWorkspaceLoader;
import org.atmosphere.ai.workspace.AtmosphereNativeWorkspaceAdapter;
import org.atmosphere.ai.workspace.OpenClawWorkspaceAdapter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end composition test that wires all six non-sandbox primitives
 * together (AgentWorkspace → AgentState → AgentIdentity → AiGateway →
 * ToolExtensibilityPoint → ProtocolBridge) and asserts they behave as a
 * coherent stack. Does not cross the runtime boundary (no LLM), so it
 * runs in CI without API keys.
 *
 * <p>This is the closest we have to a cross-primitive smoke test until
 * the full Phase 4 contract matrix lands.</p>
 */
class FoundationPrimitiveCompositionTest {

    @Test
    void workspaceLoadsStateRecordsFactsIdentityAuditsDispatch(@TempDir Path workspaceRoot)
            throws Exception {
        // --- AgentWorkspace: seed an OpenClaw-shaped workspace ------------
        Files.writeString(workspaceRoot.resolve("AGENTS.md"),
                "Be direct. Cite the source of any fact you add to memory.",
                StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("SOUL.md"),
                "Calm, focused, terse.", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("USER.md"),
                "Address the user as ChefFamille.", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("IDENTITY.md"),
                "name: pierre\nvibe: focused", StandardCharsets.UTF_8);

        var loader = new AgentWorkspaceLoader(List.of(
                new OpenClawWorkspaceAdapter(),
                new AtmosphereNativeWorkspaceAdapter()));
        var definition = loader.load(workspaceRoot);
        assertEquals("openclaw", definition.adapterName());
        assertEquals("pierre", definition.name());

        // --- AgentState: file-backed, user × agent isolation ---------------
        AgentState state = new FileSystemAgentState(workspaceRoot);
        var userId = "chef";
        var agentId = definition.name();

        // Conversation turn one
        state.appendConversation(agentId, "sess-1", ChatMessage.user("I prefer bun."));
        state.appendConversation(agentId, "sess-1",
                ChatMessage.assistant("Noted — will remember that."));

        // Agent writes a fact the user just revealed
        var fact = state.addFact(userId, agentId, "ChefFamille prefers bun over npm");
        assertTrue(state.getFacts(userId, agentId).stream()
                .anyMatch(e -> e.id().equals(fact.id())));

        // Different user never sees the fact (isolation fix verified)
        assertTrue(state.getFacts("other-user", agentId).isEmpty());

        // Rules assemble from workspace files
        var rules = state.getRules(userId, agentId);
        assertTrue(rules.systemPrompt().contains("## Identity"));
        assertTrue(rules.systemPrompt().contains("name: pierre"));
        assertTrue(rules.systemPrompt().contains("ChefFamille"));

        // --- AgentIdentity: per-user permission mode + audit --------------
        AgentIdentity identity = new InMemoryAgentIdentity(new InMemoryCredentialStore());
        identity.setPermissionMode(userId, PermissionMode.PLAN);
        assertEquals(PermissionMode.PLAN, identity.permissionMode(userId));

        identity.recordAudit(new AgentIdentity.AuditEvent(
                "audit-1", userId, "memory.write",
                "fact id=" + fact.id(), java.time.Instant.now()));
        identity.recordAudit(new AgentIdentity.AuditEvent(
                "audit-2", userId, "tool.call",
                "scheduler.propose_slots", java.time.Instant.now()));

        var audit = identity.audit(userId, 10);
        assertEquals(2, audit.size());
        assertEquals("tool.call", audit.get(0).action(),
                "newest event first");

        // --- AiGateway: admit one call through the gateway -----------------
        var limiter = new PerUserRateLimiter(5, Duration.ofSeconds(10));
        var gateway = new AiGateway(limiter,
                AiGateway.CredentialResolver.noop(),
                AiGateway.GatewayTraceExporter.noop());
        var admitted = gateway.admit(userId, "openai", "gpt-4o-mini");
        assertTrue(admitted.accepted());

        // Exceed the rate limit and verify the gateway rejects
        for (var i = 0; i < 4; i++) {
            gateway.admit(userId, "openai", "gpt-4o-mini");
        }
        assertFalse(gateway.admit(userId, "openai", "gpt-4o-mini").accepted());

        // --- ToolExtensibilityPoint: bounded discovery -------------------
        var toolIndex = new ToolIndex();
        toolIndex.register(new ToolDescriptor("scheduler.propose_slots",
                "Propose meeting slots", List.of("calendar")));
        toolIndex.register(new ToolDescriptor("drafter.draft_message",
                "Draft a short message", List.of("writing")));
        toolIndex.register(new ToolDescriptor("research.summarize_topic",
                "Summarize a topic", List.of("research")));

        var extensibility = new ToolExtensibilityPoint(
                toolIndex,
                new DynamicToolSelector(toolIndex, 2),
                new McpTrustProvider.CredentialStoreBacked(
                        identity.credentialStore()));

        var schedulerTools = extensibility.toolsFor("schedule a meeting", 2);
        assertEquals(1, schedulerTools.size());
        assertEquals("scheduler.propose_slots", schedulerTools.get(0).id());

        var draftTools = extensibility.toolsFor("draft a reply", 2);
        assertEquals(1, draftTools.size());
        assertEquals("drafter.draft_message", draftTools.get(0).id());

        // Per-user MCP credential resolution flows through AgentIdentity's
        // credential store — here the user has no credentials yet.
        assertTrue(extensibility.mcpCredential(userId, "github").isEmpty());
        identity.credentialStore().put(userId, "mcp:github", "gho_token");
        assertEquals("gho_token",
                extensibility.mcpCredential(userId, "github").orElseThrow());
    }
}
