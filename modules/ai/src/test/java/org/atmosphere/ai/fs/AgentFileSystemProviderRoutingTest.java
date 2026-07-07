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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the composite-routing consumer on {@link AgentFileSystemProvider}: when
 * durable routes are configured, {@code forConversation} composes a
 * {@link CompositeAgentFileSystem} over the per-conversation workspace so a
 * routed prefix reaches a shared durable backend while everything else stays
 * per-conversation; with no routes the provider is unchanged.
 */
class AgentFileSystemProviderRoutingTest {

    @Test
    void withNoRoutesTheProviderReturnsAPlainPerConversationWorkspace(@TempDir Path root) {
        var provider = new AgentFileSystemProvider(root, AgentFileSystem.Limits.defaults(), Map.of());

        var fs = provider.forConversation("conv-1");

        assertInstanceOf(WorkspaceAgentFileSystem.class, fs,
                "unconfigured provider must keep the plain workspace (no behavior change)");
    }

    @Test
    void aRoutedPrefixReachesTheDurableSharedBackendAcrossConversations(
            @TempDir Path agentRoot, @TempDir Path memoryDir) {
        var provider = new AgentFileSystemProvider(agentRoot, AgentFileSystem.Limits.defaults(),
                Map.of("memory/", memoryDir));

        var convA = provider.forConversation("conv-A");
        assertInstanceOf(CompositeAgentFileSystem.class, convA,
                "a configured route must compose the workspace with the durable backend");
        convA.write("memory/pref.md", "user likes tea");
        convA.write("scratch.md", "conversation-A scratch");

        // A different conversation sees the SAME durable memory route...
        var convB = provider.forConversation("conv-B");
        assertEquals("user likes tea", convB.read("memory/pref.md"),
                "the memory/ route is durable + shared across conversations");
        // ...but NOT conversation-A's per-conversation scratch file.
        assertTrue(convB.glob("**").stream().noneMatch(p -> p.contains("scratch.md")),
                "non-routed paths stay isolated to their conversation");
    }
}
