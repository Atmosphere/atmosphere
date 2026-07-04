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
package org.atmosphere.ai.anthropic;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.fs.AgentFileSystem;
import org.atmosphere.ai.fs.AgentFileSystemProvider;
import org.atmosphere.ai.fs.WorkspaceAgentFileSystem;
import org.atmosphere.ai.tool.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the Anthropic native virtual-filesystem bridge: each of the six
 * {@code memory_20250818} commands round-trips onto Atmosphere's
 * {@link AgentFileSystem} (view→read/ls, create→write, str_replace→edit,
 * insert→line splice, delete, rename), the {@code /memories} boundary and
 * store guards reject escapes with clear tool-result strings, and the wire
 * layer declares the tool exactly when the harness FILESYSTEM feature
 * scoped a store onto the session (Runtime Truth, Correctness Invariant #5).
 */
class AnthropicMemoryToolTest {

    @TempDir
    Path tmp;

    private WorkspaceAgentFileSystem fs() {
        return new WorkspaceAgentFileSystem(tmp.resolve("store"),
                AgentFileSystem.Limits.defaults());
    }

    // ---- command round-trips onto AgentFileSystem ----

    @Test
    void createWritesThroughTheStore() {
        var fs = fs();
        var result = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "create",
                "path", "/memories/notes.md",
                "file_text", "hello memory"));

        assertEquals("File created successfully at /memories/notes.md", result);
        assertEquals("hello memory", fs.read("notes.md"));
    }

    @Test
    void viewReadsAFileWithLineNumbers() {
        var fs = fs();
        fs.write("notes.md", "alpha\nbeta\ngamma\n");

        var result = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "view", "path", "/memories/notes.md"));

        assertEquals("1: alpha\n2: beta\n3: gamma", result);
    }

    @Test
    void viewHonorsViewRange() {
        var fs = fs();
        fs.write("notes.md", "alpha\nbeta\ngamma\n");

        assertEquals("2: beta", AnthropicMemoryTool.execute(fs, Map.of(
                "command", "view", "path", "/memories/notes.md",
                "view_range", List.of(2, 2))));
        // -1 means "to the end of the file" (SDK-verified view_range contract).
        assertEquals("2: beta\n3: gamma", AnthropicMemoryTool.execute(fs, Map.of(
                "command", "view", "path", "/memories/notes.md",
                "view_range", List.of(2, -1))));
    }

    @Test
    void viewListsDirectories() {
        var fs = fs();
        fs.write("notes.md", "12345");
        fs.write("projects/atmo.md", "x");

        var root = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "view", "path", "/memories"));
        assertEquals("""
                Directory: /memories
                - /memories/projects/
                - /memories/notes.md (5 bytes)""", root);

        var sub = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "view", "path", "/memories/projects"));
        assertTrue(sub.contains("- /memories/projects/atmo.md (1 bytes)"), sub);
    }

    @Test
    void strReplaceEditsWithUniqueMatchSemantics() {
        var fs = fs();
        fs.write("notes.md", "one two three");

        var result = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "str_replace", "path", "/memories/notes.md",
                "old_str", "two", "new_str", "2"));

        assertEquals("File /memories/notes.md has been edited", result);
        assertEquals("one 2 three", fs.read("notes.md"));

        var miss = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "str_replace", "path", "/memories/notes.md",
                "old_str", "absent", "new_str", "x"));
        assertTrue(miss.startsWith("Error: "), miss);
        assertEquals("one 2 three", fs.read("notes.md"), "a failed edit must change nothing");
    }

    @Test
    void insertSplicesALine() {
        var fs = fs();
        fs.write("notes.md", "alpha\ngamma\n");

        var result = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "insert", "path", "/memories/notes.md",
                "insert_line", 1, "insert_text", "beta"));

        assertEquals("Text inserted at line 1 in /memories/notes.md", result);
        assertEquals("alpha\nbeta\ngamma\n", fs.read("notes.md"));

        // Line 0 inserts at the beginning of the file.
        AnthropicMemoryTool.execute(fs, Map.of(
                "command", "insert", "path", "/memories/notes.md",
                "insert_line", 0, "insert_text", "zero"));
        assertEquals("zero\nalpha\nbeta\ngamma\n", fs.read("notes.md"));

        var outOfRange = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "insert", "path", "/memories/notes.md",
                "insert_line", 99, "insert_text", "nope"));
        assertTrue(outOfRange.startsWith("Error: "), outOfRange);
    }

    @Test
    void deleteRemovesThroughTheStore() {
        var fs = fs();
        fs.write("notes.md", "x");

        var result = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "delete", "path", "/memories/notes.md"));

        assertEquals("Deleted /memories/notes.md", result);
        assertThrows(IllegalArgumentException.class, () -> fs.read("notes.md"));

        var root = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "delete", "path", "/memories"));
        assertTrue(root.startsWith("Error: "), "the /memories root must not be deletable");
    }

    @Test
    void renameMovesThroughTheStore() {
        var fs = fs();
        fs.write("notes.md", "content");

        var result = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "rename",
                "old_path", "/memories/notes.md",
                "new_path", "/memories/archive/notes.md"));

        assertEquals("Renamed /memories/notes.md to /memories/archive/notes.md", result);
        assertEquals("content", fs.read("archive/notes.md"));
        assertThrows(IllegalArgumentException.class, () -> fs.read("notes.md"));
    }

    @Test
    void pathsOutsideMemoriesAndTraversalAreRejected() {
        var fs = fs();
        fs.write("notes.md", "x");

        for (var attack : new String[]{"/etc/passwd", "notes.md", "/memoriesX/f",
                "/memories/../escape", "/memories/a/../../escape"}) {
            var view = AnthropicMemoryTool.execute(fs, Map.of(
                    "command", "view", "path", attack));
            assertTrue(view.startsWith("Error: "), "view must reject '" + attack + "': " + view);
            var create = AnthropicMemoryTool.execute(fs, Map.of(
                    "command", "create", "path", attack, "file_text", "x"));
            assertTrue(create.startsWith("Error: "),
                    "create must reject '" + attack + "': " + create);
            var delete = AnthropicMemoryTool.execute(fs, Map.of(
                    "command", "delete", "path", attack));
            assertTrue(delete.startsWith("Error: "),
                    "delete must reject '" + attack + "': " + delete);
            var rename = AnthropicMemoryTool.execute(fs, Map.of(
                    "command", "rename", "old_path", "/memories/notes.md",
                    "new_path", attack));
            assertTrue(rename.startsWith("Error: "),
                    "rename must reject '" + attack + "': " + rename);
        }
        assertEquals("x", fs.read("notes.md"), "no rejected command may mutate the store");
    }

    @Test
    void boundsRejectionsSurfaceAsToolErrors() {
        var fs = new WorkspaceAgentFileSystem(tmp.resolve("bounded"),
                new AgentFileSystem.Limits(16, 4, 64));

        var result = AnthropicMemoryTool.execute(fs, Map.of(
                "command", "create", "path", "/memories/big.md",
                "file_text", "x".repeat(64)));

        assertTrue(result.startsWith("Error: "), result);
        assertTrue(result.contains("per-file limit"), result);
    }

    @Test
    void unknownAndMissingCommandsAreRejected() {
        var fs = fs();
        assertTrue(AnthropicMemoryTool.execute(fs, Map.of("command", "chmod"))
                .startsWith("Error: unsupported memory command"));
        assertTrue(AnthropicMemoryTool.execute(fs, Map.of())
                .startsWith("Error: unsupported memory command"));
        assertTrue(AnthropicMemoryTool.execute(fs, null)
                .startsWith("Error: unsupported memory command"));
    }

    // ---- injectables resolution (the gate) ----

    @Test
    void resolvePrefersTheScopedStoreThenTheProvider() {
        var fs = fs();
        assertEquals(fs, AnthropicMemoryTool.resolve(
                new InjectableSession(Map.of(AgentFileSystem.class, fs))));

        var provider = new AgentFileSystemProvider(tmp.resolve("agent-root"),
                AgentFileSystem.Limits.defaults());
        var viaProvider = AnthropicMemoryTool.resolve(
                new InjectableSession(Map.of(AgentFileSystemProvider.class, provider)));
        assertNotNull(viaProvider, "the provider fallback must scope by conversation id");

        assertNull(AnthropicMemoryTool.resolve(new CollectingSession()),
                "no injectables → no store → the memory tool stays undeclared");
        assertNull(AnthropicMemoryTool.resolve(null));
    }

    // ---- wire layer: declaration gating ----

    private static final String TEXT_RESPONSE = """
            data: {"type":"message_start","message":{"id":"msg_1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"input_tokens":7,"output_tokens":1}}

            data: {"type":"message_stop"}

            """;

    private static final String MEMORY_CREATE_ROUND = """
            data: {"type":"message_start","message":{"id":"msg_m1"}}

            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_m1","name":"memory","input":{}}}

            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"command\\":\\"create\\",\\"path\\":\\"/memories/notes.md\\",\\"file_text\\":\\"hello memory\\"}"}}

            data: {"type":"content_block_stop","index":0}

            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"input_tokens":15,"output_tokens":8}}

            data: {"type":"message_stop"}

            """;

    @Test
    void memoryToolIsDeclaredWhenTheSessionCarriesAStore() throws Exception {
        var httpClient = mockResponses(TEXT_RESPONSE);
        var client = client(httpClient);
        var session = new InjectableSession(Map.of(AgentFileSystem.class, fs()));

        client.stream("claude-sonnet-4-6", List.of(), null, "Hi",
                textContext(), session, null);
        session.await(Duration.ofSeconds(5));

        var body = firstBody(httpClient);
        assertTrue(body.contains("\"type\":\"memory_20250818\""), body);
        assertTrue(body.contains("\"name\":\"memory\""), body);
    }

    @Test
    void memoryToolIsAbsentWithoutTheHarnessStore() throws Exception {
        var httpClient = mockResponses(TEXT_RESPONSE);
        var client = client(httpClient);
        var session = new CollectingSession();

        client.stream("claude-sonnet-4-6", List.of(), null, "Hi",
                textContext(), session, null);
        session.await(Duration.ofSeconds(5));

        assertFalse(firstBody(httpClient).contains("memory_20250818"),
                "no scoped store → the memory tool must not be declared (Runtime Truth)");
    }

    @Test
    void builtinFilesystemModeSuppressesTheNativeSurface() throws Exception {
        System.setProperty(AiConfig.FILESYSTEM_PROPERTY, "builtin");
        try {
            var httpClient = mockResponses(TEXT_RESPONSE);
            var client = client(httpClient);
            var session = new InjectableSession(Map.of(AgentFileSystem.class, fs()));

            client.stream("claude-sonnet-4-6", List.of(), null, "Hi",
                    textContext(), session, null);
            session.await(Duration.ofSeconds(5));

            assertFalse(firstBody(httpClient).contains("memory_20250818"),
                    "BUILTIN pins the portable floor — the native surface must stay off");
        } finally {
            System.clearProperty(AiConfig.FILESYSTEM_PROPERTY);
        }
    }

    @Test
    void callerRegisteredMemoryToolWins() throws Exception {
        var httpClient = mockResponses(TEXT_RESPONSE);
        var client = client(httpClient);
        var session = new InjectableSession(Map.of(AgentFileSystem.class, fs()));
        var userMemory = ToolDefinition.builder("memory", "user-defined memory tool")
                .parameter("key", "the key", "string")
                .executor(args -> "user-tool")
                .build();
        var context = new AgentExecutionContext(
                "Hi", null, "claude-sonnet-4-6",
                null, "s1", "u1", "c1",
                List.of(userMemory), null, null, List.of(), Map.of(),
                List.of(), null, null);

        client.stream("claude-sonnet-4-6", List.of(), null, "Hi",
                context, session, null);
        session.await(Duration.ofSeconds(5));

        var body = firstBody(httpClient);
        assertFalse(body.contains("memory_20250818"),
                "a caller tool named 'memory' must suppress the native declaration");
        assertTrue(body.contains("\"name\":\"memory\""),
                "the caller's own tool still rides the wire");
    }

    // ---- wire layer: full command round-trip ----

    @Test
    void memoryCreateCommandRoundTripsOntoTheStore() throws Exception {
        var httpClient = mockResponses(MEMORY_CREATE_ROUND, TEXT_RESPONSE);
        var client = client(httpClient);
        var fs = fs();
        var session = new InjectableSession(Map.of(AgentFileSystem.class, fs));

        client.stream("claude-sonnet-4-6", List.of(), null,
                "Remember this", textContext(), session, null);
        session.await(Duration.ofSeconds(5));

        // The command mutated Atmosphere's store, not some parallel world.
        assertEquals("hello memory", fs.read("notes.md"));
        assertEquals("ok", session.text(), "the final round's text must stream through");

        // The success message rode back to the model as the tool_result of
        // the originating tool_use id.
        var secondBody = requestBodies(httpClient).get(1);
        assertTrue(secondBody.contains("\"tool_use_id\":\"toolu_m1\""), secondBody);
        assertTrue(secondBody.contains("File created successfully at /memories/notes.md"),
                secondBody);
    }

    // ---- scaffolding ----

    private static AnthropicMessagesClient client(HttpClient httpClient) {
        return AnthropicMessagesClient.builder()
                .apiKey("test-key")
                .httpClient(httpClient)
                .build();
    }

    private static AgentExecutionContext textContext() {
        return new AgentExecutionContext(
                "Hi", "You are helpful", "claude-sonnet-4-6",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), Map.of(),
                List.of(), null, null);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient mockResponses(String... bodies) throws Exception {
        var httpClient = mock(HttpClient.class);
        var responses = new java.util.ArrayList<HttpResponse<Object>>();
        for (var body : bodies) {
            var response = (HttpResponse<Object>) mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            responses.add(response);
        }
        var stubbing = when(httpClient.send(any(HttpRequest.class),
                any(HttpResponse.BodyHandler.class)));
        for (var response : responses) {
            stubbing = stubbing.thenReturn(response);
        }
        return httpClient;
    }

    private static String firstBody(HttpClient httpClient) throws Exception {
        return requestBodies(httpClient).get(0);
    }

    // Mocking HttpClient.send requires the raw BodyHandler class literal —
    // same posture as AnthropicMessagesClientTest.
    @SuppressWarnings("unchecked")
    private static List<String> requestBodies(HttpClient httpClient) throws Exception {
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, atLeastOnce())
                .send(captor.capture(), any(HttpResponse.BodyHandler.class));
        return captor.getAllValues().stream()
                .map(request -> drainBody(request.bodyPublisher().orElseThrow()))
                .toList();
    }

    private static String drainBody(HttpRequest.BodyPublisher publisher) {
        var collector = new java.util.concurrent.atomic.AtomicReference<String>();
        var done = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            private final StringBuilder buf = new StringBuilder();

            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                var bytes = new byte[item.remaining()];
                item.get(bytes);
                buf.append(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                collector.set(buf.toString());
                done.countDown();
            }
        });
        try {
            done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        var body = collector.get();
        assertNotNull(body, "request body must drain to a string");
        return body;
    }

    /**
     * {@link StreamingSession} carrying dispatch-time injectables, exactly
     * the way {@code AiEndpointHandler.setInjectables} publishes the scoped
     * {@link AgentFileSystem} in production ({@link CollectingSession} is
     * final, so this delegates instead of subclassing).
     */
    private static final class InjectableSession implements StreamingSession {

        private final CollectingSession delegate = new CollectingSession();
        private final Map<Class<?>, Object> injectables;

        InjectableSession(Map<Class<?>, Object> injectables) {
            this.injectables = injectables;
        }

        @Override
        public Map<Class<?>, Object> injectables() {
            return injectables;
        }

        @Override
        public String sessionId() {
            return delegate.sessionId();
        }

        @Override
        public void send(String text) {
            delegate.send(text);
        }

        @Override
        public void sendMetadata(String key, Object value) {
        }

        @Override
        public void progress(String message) {
        }

        @Override
        public void complete() {
            delegate.complete();
        }

        @Override
        public void complete(String summary) {
            delegate.complete(summary);
        }

        @Override
        public void error(Throwable t) {
            delegate.error(t);
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        boolean await(Duration timeout) {
            return delegate.await(timeout);
        }

        String text() {
            return delegate.text();
        }
    }
}
