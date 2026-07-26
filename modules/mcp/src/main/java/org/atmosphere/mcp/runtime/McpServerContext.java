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
package org.atmosphere.mcp.runtime;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Per-invocation handle giving an {@code @McpTool} method access to the
 * server→client request surface: sampling ({@code sampling/createMessage}),
 * roots ({@code roots/list}), and elicitation ({@code elicitation/create}).
 *
 * <p>Declare it as a method parameter and the framework injects it — it never
 * appears in the tool's JSON Schema, so the model is never asked to supply
 * it:</p>
 *
 * <pre>{@code
 * @McpTool(name = "summarize_doc", description = "Summarize a document")
 * public String summarize(@McpParam(name = "uri") String uri,
 *                         McpServerContext ctx) throws Exception {
 *     var text = load(uri);
 *     var reply = ctx.sample(
 *             List.of(Map.of("role", "user",
 *                            "content", Map.of("type", "text", "text", "Summarize:\n" + text))),
 *             Map.of("maxTokens", 300))
 *         .orTimeout(30, TimeUnit.SECONDS)
 *         .get();
 *     return reply.path("result").path("content").path("text").asString();
 * }
 * }</pre>
 *
 * <p>Capability gating is the caller's safety net, not a courtesy: sampling
 * and roots are <em>client</em>-declared capabilities in MCP, so when the
 * connected client did not advertise them the returned future fails fast with
 * an {@link IllegalStateException} rather than sending a request the client
 * cannot answer (Correctness Invariant #5). Check {@link #canSample()} /
 * {@link #canListRoots()} first to take a different path.</p>
 *
 * <p>The returned futures complete when the client's response envelope arrives
 * over the same session. They have no built-in deadline — apply
 * {@link CompletableFuture#orTimeout} so a silent client cannot park the tool
 * (and hence the turn) forever.</p>
 */
public final class McpServerContext {

    private static final McpServerContext EMPTY = new McpServerContext(null, null);

    private final McpProtocolHandler handler;
    private final McpSession session;

    McpServerContext(McpProtocolHandler handler, McpSession session) {
        this.handler = handler;
        this.session = session;
    }

    /**
     * A context with no live session — what a tool sees on a transport that
     * does not carry one (the stateless {@code 2026-07-28} dialect). Every
     * request method fails fast; the {@code can*} predicates return false.
     */
    static McpServerContext empty() {
        return EMPTY;
    }

    /** The MCP session behind this invocation, empty on session-less transports. */
    public Optional<McpSession> session() {
        return Optional.ofNullable(session);
    }

    /** Whether the connected client advertised the {@code sampling} capability. */
    public boolean canSample() {
        return hasCapability("sampling");
    }

    /** Whether the connected client advertised the {@code roots} capability. */
    public boolean canListRoots() {
        return hasCapability("roots");
    }

    /** Whether the connected client advertised the {@code elicitation} capability. */
    public boolean canElicit() {
        return hasCapability("elicitation");
    }

    /**
     * Ask the client to run an LLM completion on the server's behalf
     * ({@code sampling/createMessage}).
     *
     * @param messages the conversation to complete, in MCP
     *                 {@code SamplingMessage} shape
     * @param options  extra request fields ({@code systemPrompt},
     *                 {@code maxTokens}, {@code modelPreferences}, ...);
     *                 may be {@code null}
     * @return future resolving to the client's response envelope
     */
    public CompletableFuture<JsonNode> sample(List<Map<String, Object>> messages,
                                              Map<String, Object> options) {
        if (handler == null) {
            return CompletableFuture.failedFuture(noSession("sampling/createMessage"));
        }
        return handler.sample(session, messages, options);
    }

    /**
     * Ask the client which filesystem roots it exposes ({@code roots/list}).
     *
     * @return future resolving to the client's {@code ListRootsResult} envelope
     */
    public CompletableFuture<JsonNode> listRoots() {
        if (handler == null) {
            return CompletableFuture.failedFuture(noSession("roots/list"));
        }
        return handler.listRoots(session);
    }

    /**
     * Ask the user, via the client, for structured input
     * ({@code elicitation/create}).
     *
     * @param message         human-readable prompt shown to the user
     * @param requestedSchema JSON Schema describing the expected response
     * @return future resolving to the client's {@code ElicitResult} envelope
     */
    public CompletableFuture<JsonNode> elicit(String message, Map<String, Object> requestedSchema) {
        if (handler == null) {
            return CompletableFuture.failedFuture(noSession("elicitation/create"));
        }
        return handler.elicit(session, message, requestedSchema);
    }

    private boolean hasCapability(String capability) {
        if (session == null) {
            return false;
        }
        var caps = session.clientCapabilities();
        return caps != null && caps.containsKey(capability);
    }

    private static IllegalStateException noSession(String method) {
        return new IllegalStateException("No MCP session is bound to this invocation; "
                + method + " requires a session-model transport.");
    }
}
