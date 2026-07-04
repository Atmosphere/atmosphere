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
package org.atmosphere.ai.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.AgentFileSystemProvider;
import org.atmosphere.ai.fs.WorkspaceAgentFileSystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the native virtual-filesystem wiring on the ADK runtime
 * ({@code AiCapability.VIRTUAL_FILESYSTEM}, Correctness Invariant #5 —
 * Runtime Truth): when the conversation-scoped {@link AgentFileSystem} is in
 * the session's tool scope, {@link AdkAgentRuntime#buildRequestRunner} injects
 * the {@link AdkArtifactService} bridge via
 * {@code Runner.Builder.artifactService(...)} and registers the
 * {@code load_artifacts} / {@code save_artifact} tool pair; artifact saves
 * through the built Runner land in Atmosphere's store. Also pins the
 * negative space: BUILTIN mode and fs-free sessions leave the native surface
 * off.
 */
class AdkAgentRuntimeVfsTest {

    @TempDir
    Path tempDir;

    private AgentFileSystem fileSystem;

    @BeforeEach
    void setUp() {
        // buildRequestRunner's default path constructs a Gemini client from the
        // configured settings; the key is never dialed in these tests.
        AiConfig.configure("remote", "gemini-2.5-flash", "test-key", null);
        fileSystem = WorkspaceAgentFileSystem.forConversation(
                tempDir, "conv-1", AgentFileSystem.Limits.defaults());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(AiConfig.FILESYSTEM_PROPERTY);
    }

    @Test
    void buildRequestRunnerInjectsArtifactServiceBridge() {
        var runner = new AdkAgentRuntime()
                .buildRequestRunner(textContext(), sessionWith(
                        Map.of(AgentFileSystem.class, fileSystem)));

        var artifactService = assertInstanceOf(AdkArtifactService.class,
                runner.artifactService(),
                "the Runner must carry the Atmosphere bridge, not ADK's default "
                        + "InMemoryArtifactService");

        // Save/load through the Runner-held bridge must hit AgentFileSystem.
        artifactService.saveArtifact("atmosphere", "user-1", "session-1",
                "notes.md", Part.fromText("bridged")).blockingGet();
        assertEquals("bridged", fileSystem.read("notes.md"));
        assertEquals("bridged", artifactService
                .loadArtifact("atmosphere", "user-1", "session-1", "notes.md", null)
                .blockingGet().text().orElseThrow());
    }

    @Test
    void buildRequestRunnerRegistersNativeFileToolPair() {
        var runner = new AdkAgentRuntime()
                .buildRequestRunner(textContext(), sessionWith(
                        Map.of(AgentFileSystem.class, fileSystem)));

        var toolNames = ((LlmAgent) runner.agent()).tools().blockingGet().stream()
                .map(BaseTool::name)
                .toList();
        assertTrue(toolNames.contains("load_artifacts"),
                "ADK's shipped read tool must be registered: " + toolNames);
        assertTrue(toolNames.contains(AdkSaveArtifactTool.NAME),
                "the write complement must be registered: " + toolNames);
    }

    @Test
    void builtinModeSuppressesTheNativeSurface() {
        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "builtin");

        assertNull(AdkAgentRuntime.resolveAgentFileSystem(sessionWith(
                        Map.of(AgentFileSystem.class, fileSystem))),
                "BUILTIN mode: the portable tool floor owns the surface — wiring "
                        + "the native bridge too would duplicate the file tools");

        var runner = new AdkAgentRuntime()
                .buildRequestRunner(textContext(), sessionWith(
                        Map.of(AgentFileSystem.class, fileSystem)));
        assertFalse(runner.artifactService() instanceof AdkArtifactService,
                "no Atmosphere bridge in BUILTIN mode");
        var toolNames = ((LlmAgent) runner.agent()).tools().blockingGet().stream()
                .map(BaseTool::name)
                .toList();
        assertFalse(toolNames.contains(AdkSaveArtifactTool.NAME),
                "no native write tool in BUILTIN mode: " + toolNames);
    }

    @Test
    void fsFreeSessionResolvesNoStore() {
        assertNull(AdkAgentRuntime.resolveAgentFileSystem(sessionWith(Map.of())),
                "harness FILESYSTEM off: nothing in the tool scope, no native surface");
        assertNull(AdkAgentRuntime.resolveAgentFileSystem(null));
    }

    @Test
    void providerFallbackDerivesConversationScopedStore() {
        var provider = new AgentFileSystemProvider(
                tempDir, AgentFileSystem.Limits.defaults());

        var resolved = AdkAgentRuntime.resolveAgentFileSystem(sessionWith(
                Map.of(AgentFileSystemProvider.class, provider)));

        assertNotNull(resolved,
                "resource-free paths carry the provider — the bridge must derive "
                        + "the conversation-scoped view from it");
        assertSame(resolved, provider.forConversation("default"),
                "no resource/session identity in scope collapses to the provider's "
                        + "default conversation view");
    }

    @Test
    void directStoreWinsOverProvider() {
        var provider = new AgentFileSystemProvider(
                tempDir, AgentFileSystem.Limits.defaults());

        var resolved = AdkAgentRuntime.resolveAgentFileSystem(sessionWith(Map.of(
                AgentFileSystem.class, fileSystem,
                AgentFileSystemProvider.class, provider)));

        assertSame(fileSystem, resolved,
                "the dispatch-seam-published store must win over the "
                        + "registration-time provider");
    }

    @Test
    void saveArtifactToolRoutesThroughToolContext() {
        var toolContext = mock(ToolContext.class);
        when(toolContext.saveArtifact(eq("a.txt"), any(Part.class)))
                .thenReturn(Completable.complete());

        var result = new AdkSaveArtifactTool()
                .runAsync(Map.of("filename", "a.txt", "content", "hello"), toolContext)
                .blockingGet();

        assertEquals("success", result.get("status"));
        verify(toolContext).saveArtifact(eq("a.txt"), any(Part.class));
    }

    @Test
    void saveArtifactToolSurfacesStoreRejectionsAsToolResult() {
        var toolContext = mock(ToolContext.class);
        when(toolContext.saveArtifact(eq("big.txt"), any(Part.class)))
                .thenReturn(Completable.error(new IllegalArgumentException(
                        "Write rejected: big.txt is over the per-file limit")));

        var result = new AdkSaveArtifactTool()
                .runAsync(Map.of("filename", "big.txt", "content", "x"), toolContext)
                .blockingGet();

        assertEquals("error", result.get("status"));
        assertTrue(result.get("error").toString().contains("Write rejected"),
                "the model must see the store's rejection reason: " + result);
    }

    @Test
    void saveArtifactToolRejectsMissingFilename() {
        var result = new AdkSaveArtifactTool()
                .runAsync(Map.of("content", "orphan"), mock(ToolContext.class))
                .blockingGet();

        assertEquals("error", result.get("status"));
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "gemini-2.5-flash",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    private static StreamingSession sessionWith(Map<Class<?>, Object> injectables) {
        return new StreamingSession() {
            @Override public String sessionId() {
                return "session-1";
            }

            @Override public Map<Class<?>, Object> injectables() {
                return injectables;
            }

            @Override public void send(String text) { }

            @Override public void sendMetadata(String key, Object value) { }

            @Override public void progress(String message) { }

            @Override public void complete() { }

            @Override public void complete(String summary) { }

            @Override public void error(Throwable t) { }

            @Override public boolean isClosed() {
                return false;
            }
        };
    }
}
