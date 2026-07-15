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
package org.atmosphere.ai.crewai;

import org.atmosphere.ai.AbstractAgentRuntime;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link org.atmosphere.ai.AgentRuntime} backed by an out-of-process CrewAI
 * Python sidecar. The Java half is HTTP + SSE only — no Python runtime
 * loads in this JVM. The companion Python sidecar (`atmosphere-crewai-bridge`)
 * ships under {@code modules/crewai/sidecar/}; the Java runtime requires
 * {@link CrewAiSidecarConfig#ENV_URL ATMOSPHERE_CREWAI_SIDECAR_URL} (or the
 * matching system property) to point at a running sidecar instance —
 * {@link #isAvailable()} gates on a live {@code GET /health} probe rather
 * than on classpath presence (Correctness Invariant #5 — Runtime Truth).
 *
 * <p>Capabilities declared: {@code TEXT_STREAMING}, {@code TOKEN_USAGE},
 * {@code AGENT_ORCHESTRATION}, {@code CANCELLATION}, {@code TOOL_CALLING},
 * {@code SYSTEM_PROMPT}, {@code TOOL_APPROVAL}, {@code STRUCTURED_OUTPUT},
 * {@code PER_REQUEST_RETRY} — pinned by {@code CrewAiRuntimeContractTest}
 * and the capability snapshot.</p>
 */
public class CrewAiAgentRuntime extends AbstractAgentRuntime<CrewAiSidecarClient> {

    private static final Logger logger = LoggerFactory.getLogger(CrewAiAgentRuntime.class);

    private final AtomicReference<CrewAiSidecarConfig> resolvedConfig = new AtomicReference<>();

    @Override
    public String name() {
        return "crewai";
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    protected String nativeClientClassName() {
        // Stable JDK 21 class — always present. The real availability
        // check happens in isAvailable() against a live /health probe.
        return "java.net.http.HttpClient";
    }

    @Override
    protected String clientDescription() {
        return "CrewAiSidecarClient";
    }

    @Override
    protected String configurationHint() {
        return "Set " + CrewAiSidecarConfig.ENV_URL + " (or the "
                + CrewAiSidecarConfig.SYS_URL + " system property) to a running "
                + "CrewAI sidecar URL.";
    }

    /**
     * Build the sidecar HTTP client and probe its {@code /health} endpoint
     * before declaring the runtime configured. A failed probe leaves the
     * native client null so {@link AbstractAgentRuntime}'s lazy resolve
     * path surfaces a clear {@code IllegalStateException} on the first
     * {@code execute()} call.
     */
    @Override
    protected CrewAiSidecarClient createNativeClient(AiConfig.LlmSettings settings) {
        var configOpt = CrewAiSidecarConfig.discover();
        if (configOpt.isEmpty()) {
            logger.info("CrewAI sidecar: no URL configured ({} / {}); runtime stays unavailable.",
                    CrewAiSidecarConfig.ENV_URL, CrewAiSidecarConfig.SYS_URL);
            return null;
        }
        var config = configOpt.get();
        resolvedConfig.set(config);
        var client = new HttpSseSidecarClient(config);
        if (!client.health()) {
            logger.warn("CrewAI sidecar at {} did not respond OK to /health within {}ms; "
                            + "runtime stays unavailable until the sidecar is reachable.",
                    config.baseUrl(), config.healthTimeout().toMillis());
            return null;
        }
        logger.info("CrewAI sidecar reachable at {} (request timeout {}ms).",
                config.baseUrl(), config.requestTimeout().toMillis());
        return client;
    }

    @Override
    public void configure(AiConfig.LlmSettings settings) {
        if (getNativeClient() == null) {
            setNativeClient(createNativeClient(settings));
        }
    }

    @Override
    public boolean isAvailable() {
        // Confirmed runtime state per Invariant #5: never claim availability
        // off classpath presence. The runtime is available only when a
        // sidecar URL is configured AND its /health probe succeeds. If the
        // runtime has already been configured (native client is non-null),
        // trust that state — re-probing every isAvailable() call would
        // burn budget on the discovery hot path.
        if (getNativeClient() != null) {
            return true;
        }
        var configOpt = CrewAiSidecarConfig.discover();
        if (configOpt.isEmpty()) {
            return false;
        }
        var probeClient = new HttpSseSidecarClient(configOpt.get());
        return probeClient.health();
    }

    @Override
    protected void doExecute(CrewAiSidecarClient client,
                             AgentExecutionContext context,
                             StreamingSession session) {
        admitThroughGateway(context);
        ToolCallbackServer callbackServer = null;
        try {
            callbackServer = startCallbackServerIfNeeded(context, session);
            drainStream(client, context, session, null, callbackServer);
        } finally {
            // Terminal-path completeness (Correctness Invariant #2): the
            // callback server lifecycle is per-execution. Whether the stream
            // ended in done/error/exception/cancel, the listener must release
            // its socket + thread pool here. stop() is idempotent so a second
            // call from a defensive caller is safe.
            if (callbackServer != null) {
                callbackServer.stop();
            }
        }
    }

    @Override
    protected ExecutionHandle doExecuteWithHandle(CrewAiSidecarClient client,
                                                  AgentExecutionContext context,
                                                  StreamingSession session) {
        admitThroughGateway(context);
        var cancelled = new AtomicBoolean();
        var sidecarSessionId = new AtomicReference<String>();
        var done = new CompletableFuture<Void>();
        var callbackServerRef = new AtomicReference<ToolCallbackServer>();
        Thread.startVirtualThread(() -> {
            try {
                var srv = startCallbackServerIfNeeded(context, session);
                callbackServerRef.set(srv);
                drainStream(client, context, session, ctx -> {
                    sidecarSessionId.set(ctx);
                    if (cancelled.get()) {
                        client.cancelSession(ctx);
                    }
                }, srv);
                done.complete(null);
            } catch (RuntimeException t) {
                done.completeExceptionally(t);
            } finally {
                var srv = callbackServerRef.get();
                if (srv != null) {
                    srv.stop();
                }
            }
        });
        return new ExecutionHandle() {
            private final AtomicBoolean fired = new AtomicBoolean();

            @Override
            public void cancel() {
                // Idempotent — only the first call fires the sidecar DELETE.
                if (!fired.compareAndSet(false, true)) {
                    return;
                }
                cancelled.set(true);
                var id = sidecarSessionId.get();
                if (id != null) {
                    client.cancelSession(id);
                }
            }

            @Override
            public boolean isDone() {
                return done.isDone();
            }

            @Override
            public CompletableFuture<Void> whenDone() {
                return done;
            }
        };
    }

    /**
     * Build and start a callback server when {@code context.tools()} is
     * non-empty. Returns {@code null} when no tools are wired, keeping the
     * pre-tool-bridge fast path allocation-free.
     */
    private ToolCallbackServer startCallbackServerIfNeeded(AgentExecutionContext context,
                                                           StreamingSession session) {
        if (context == null || context.tools() == null || context.tools().isEmpty()) {
            return null;
        }
        var registry = ToolCallbackServer.indexByName(context.tools());
        if (registry.isEmpty()) {
            return null;
        }
        var server = new ToolCallbackServer(registry, session, context.approvalStrategy());
        try {
            server.start();
        } catch (IOException e) {
            // Surface the failure as a CrewAi sidecar exception so the
            // runtime's error path produces the same terminal shape as any
            // other startup failure (Correctness Invariant #2). Stop the
            // server in case it half-bound before throwing.
            server.stop();
            throw new CrewAiSidecarException(
                    "Failed to bind CrewAI tool callback server on loopback", e);
        }
        logger.debug("CrewAI tool callback server started at {} for {} tool(s)",
                server.callbackUrl(), registry.size());
        return server;
    }

    /**
     * Common terminal-path-complete dispatch loop shared by
     * {@link #doExecute} and {@link #doExecuteWithHandle}. The
     * {@code sessionIdSink} callback is invoked once the sidecar session id
     * is known so the cancel-aware path can wire the DELETE call.
     */
    private void drainStream(CrewAiSidecarClient client,
                             AgentExecutionContext context,
                             StreamingSession session,
                             java.util.function.Consumer<String> sessionIdSink,
                             ToolCallbackServer callbackServer) {
        var history = new ArrayList<CrewAiSidecarClient.HistoryEntry>();
        for (var msg : context.history()) {
            history.add(new CrewAiSidecarClient.HistoryEntry(msg.role(), msg.content()));
        }
        var tools = toolDescriptors(context.tools());
        var callbackUrl = callbackServer != null ? callbackServer.callbackUrl() : null;
        var callbackToken = callbackServer != null ? callbackServer.token() : null;
        var request = new CrewAiSidecarClient.StartRequest(
                context.message(),
                effectiveModel(context),
                history,
                java.util.Map.of(),
                context.systemPrompt(),
                tools,
                callbackUrl,
                callbackToken);
        CrewAiSidecarClient.SidecarSession sidecarSession;
        try {
            sidecarSession = client.startSession(request);
        } catch (RuntimeException e) {
            session.error(e);
            return;
        }
        if (sessionIdSink != null && sidecarSession.sessionId() != null) {
            sessionIdSink.accept(sidecarSession.sessionId());
        }

        // try-with-resources guarantees the SSE connection is released on
        // every terminal path (Correctness Invariant #1, #2).
        try (sidecarSession) {
            boolean terminal = false;
            var iterator = sidecarSession.events();
            while (iterator.hasNext()) {
                var event = iterator.next();
                if (event instanceof CrewAiSidecarClient.SidecarEvent.Token token) {
                    if (!token.text().isEmpty()) {
                        session.send(token.text());
                    }
                } else if (event instanceof CrewAiSidecarClient.SidecarEvent.Usage usage) {
                    if (usage.usage() != null && usage.usage().hasCounts()) {
                        session.usage(usage.usage());
                    }
                } else if (event instanceof CrewAiSidecarClient.SidecarEvent.Done) {
                    session.complete();
                    terminal = true;
                    break;
                } else if (event instanceof CrewAiSidecarClient.SidecarEvent.Error err) {
                    session.error(new CrewAiSidecarException(err.message()));
                    terminal = true;
                    break;
                }
            }
            if (!terminal) {
                // Stream ended without a Done or Error frame — surface a
                // synthetic error so callers observe a terminal state
                // (Correctness Invariant #2).
                session.error(new CrewAiSidecarException(
                        "CrewAI sidecar stream ended without a terminal event"));
            }
        } catch (RuntimeException e) {
            // Catch-all to guarantee the session reaches a terminal state
            // even when the iterator or session close throws.
            if (!session.isClosed()) {
                session.error(e);
            }
        }
    }

    /**
     * Translate Atmosphere {@link ToolDefinition} records into the sidecar
     * wire shape. Each parameter's {@link ToolParameter#type()} is the JSON
     * Schema string the Python side ({@code build_remote_tool} in
     * {@code tools.py}) maps to a pydantic field type.
     */
    private static List<CrewAiSidecarClient.ToolDescriptor> toolDescriptors(
            List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<CrewAiSidecarClient.ToolDescriptor>(tools.size());
        for (var tool : tools) {
            if (tool == null) {
                continue;
            }
            var params = new ArrayList<CrewAiSidecarClient.ParameterDescriptor>(
                    tool.parameters().size());
            for (var p : tool.parameters()) {
                params.add(new CrewAiSidecarClient.ParameterDescriptor(
                        p.name(), p.type(), p.description(), p.required()));
            }
            out.add(new CrewAiSidecarClient.ToolDescriptor(
                    tool.name(), tool.description(), params, tool.returnType()));
        }
        return out;
    }

    private static String effectiveModel(AgentExecutionContext context) {
        if (context != null && context.model() != null && !context.model().isBlank()) {
            return context.model();
        }
        var settings = AiConfig.get();
        if (settings != null && settings.model() != null && !settings.model().isBlank()) {
            return settings.model();
        }
        return null;
    }

    @Override
    public Set<AiCapability> capabilities() {
        // Honest floor — every entry corresponds to a code path
        // drainStream() actually exercises:
        //   TEXT_STREAMING        — token events forwarded via session.send()
        //   TOKEN_USAGE           — usage events forwarded via session.usage()
        //   AGENT_ORCHESTRATION   — CrewAI is fundamentally a multi-agent
        //                            orchestration framework; the sidecar
        //                            owns the crew lifecycle and surfaces a
        //                            single text stream back here.
        //   CANCELLATION          — executeWithHandle wires DELETE /v1/sessions/{id}
        //                            via the returned ExecutionHandle.
        //   TOOL_CALLING          — context.tools() are serialised on the
        //                            StartRequest, materialised by the sidecar
        //                            as CrewAI BaseTool subclasses, and routed
        //                            back to the Java side via the
        //                            ToolCallbackServer for execution through
        //                            ToolExecutionHelper.executeWithApproval.
        //   TOOL_APPROVAL         — ToolCallbackServer routes every tool
        //                            invocation through
        //                            ToolExecutionHelper.executeWithApproval,
        //                            so @RequiresApproval gates fire on every
        //                            sidecar callback identically to the
        //                            in-process tool path (Correctness
        //                            Invariant #7 — Mode Parity).
        //   SYSTEM_PROMPT         — context.systemPrompt() is serialised on the
        //                            StartRequest; the sidecar prepends it to
        //                            each agent's backstory before kickoff.
        //   STRUCTURED_OUTPUT     — AiPipeline wraps the session in
        //                            StructuredOutputCapturingSession and
        //                            augments the system prompt with schema
        //                            instructions for every SYSTEM_PROMPT-
        //                            capable runtime; the sidecar honors the
        //                            augmented prompt unchanged so the
        //                            structured-output flow lands without
        //                            additional Java-side wiring.
        //   PER_REQUEST_RETRY     — AbstractAgentRuntime.executeWithOuterRetry
        //                            wraps every dispatch, so a per-request
        //                            RetryPolicy override (or the context
        //                            default) re-issues a fresh
        //                            POST /v1/sessions on a thrown
        //                            RuntimeException as long as the
        //                            session has not yet observed a
        //                            terminal frame (Correctness Invariant
        //                            #2 — Terminal Path Completeness).
        //
        // NOT claimed (yet):
        //   CONVERSATION_MEMORY   — history is forwarded on every start,
        //                            but the runtime does not own a memory
        //                            store; left off until a sidecar-side
        //                            checkpoint contract lands.
        return Set.of(
                AiCapability.TEXT_STREAMING,
                AiCapability.TOKEN_USAGE,
                AiCapability.AGENT_ORCHESTRATION,
                AiCapability.CANCELLATION,
                AiCapability.TOOL_CALLING,
                AiCapability.TOOL_APPROVAL,
                AiCapability.SYSTEM_PROMPT,
                AiCapability.STRUCTURED_OUTPUT,
                AiCapability.PER_REQUEST_RETRY);
    }
}
