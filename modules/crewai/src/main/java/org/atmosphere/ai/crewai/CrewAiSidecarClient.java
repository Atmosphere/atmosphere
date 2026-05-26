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

import org.atmosphere.ai.TokenUsage;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Contract between the {@link CrewAiAgentRuntime} and the out-of-process
 * Python CrewAI sidecar. Keeps the runtime free of HTTP-client wiring so
 * tests can stub the client with canned events, and so a future transport
 * (gRPC, WebSocket) can drop in without touching the runtime.
 *
 * <h3>Wire shape (HTTP+SSE)</h3>
 * <ul>
 *   <li>{@code GET  /health} — liveness probe; 200 = sidecar ready,
 *       anything else = unavailable.</li>
 *   <li>{@code POST /v1/sessions} — start a new crew run with a
 *       {@link StartRequest} JSON body; response opens an SSE stream of
 *       {@link SidecarEvent} frames and carries the assigned
 *       {@code sessionId} either as the {@code X-Atmosphere-CrewAI-Session}
 *       response header or as the first SSE frame's {@code id}.</li>
 *   <li>{@code DELETE /v1/sessions/&#123;id&#125;} — cooperative cancel; the sidecar
 *       stops the in-flight crew and closes the SSE stream.</li>
 * </ul>
 */
public interface CrewAiSidecarClient {

    /**
     * Probe the sidecar for liveness. MUST return {@code true} only when the
     * sidecar has confirmed it is running and ready — never on classpath
     * presence alone (Correctness Invariant #5 — Runtime Truth).
     */
    boolean health();

    /**
     * Open a new sidecar session and return a blocking iterator over its
     * SSE event stream. The returned {@link SidecarSession} is
     * {@link AutoCloseable}; callers MUST close it on every terminal path
     * so the underlying connection releases (Correctness Invariant #2 —
     * Terminal Path Completeness).
     *
     * @param request the start request body
     * @return an open session whose iterator yields events until a
     *         {@link SidecarEvent.Done} or {@link SidecarEvent.Error}
     *         arrives.
     */
    SidecarSession startSession(StartRequest request);

    /**
     * Cancel an in-flight sidecar session. Idempotent — calling this on a
     * session that has already terminated is a no-op.
     *
     * @param sessionId the session id returned by {@link SidecarSession#sessionId()}
     */
    void cancelSession(String sessionId);

    /**
     * Payload sent on {@code POST /v1/sessions}. The Java half is text-only
     * this session — {@code options} carries any sidecar-specific knobs
     * (crew name, agent overrides) so the wire schema stays stable while
     * the implementation evolves.
     *
     * <p>{@code systemPrompt}, {@code tools}, and {@code toolCallbackUrl}
     * are optional. When {@code tools} is empty AND {@code systemPrompt}
     * is null the wire body is identical to the pre-tool-bridge shape so
     * existing sidecars stay forward-compatible.</p>
     *
     * @param message         the user turn text
     * @param model           the model identifier; may be {@code null} so the
     *                        sidecar falls back to its own default
     * @param history         conversation history; never {@code null}, may be
     *                        empty
     * @param options         additional knobs; never {@code null}, may be
     *                        empty
     * @param systemPrompt    optional system-prompt directive prepended to
     *                        each agent's persona by the sidecar; may be
     *                        {@code null}
     * @param tools           tools available to the crew; never {@code null},
     *                        may be empty (no tool bridge running)
     * @param toolCallbackUrl URL the sidecar POSTs to when a tool is invoked;
     *                        MUST be non-null whenever {@code tools} is
     *                        non-empty
     */
    record StartRequest(String message, String model,
                        List<HistoryEntry> history, Map<String, Object> options,
                        String systemPrompt, List<ToolDescriptor> tools,
                        String toolCallbackUrl) {

        public StartRequest {
            history = history != null ? List.copyOf(history) : List.of();
            options = options != null ? Map.copyOf(options) : Map.of();
            tools = tools != null ? List.copyOf(tools) : List.of();
        }

        /**
         * Backwards-compatible constructor preserved for the pre-tool-bridge
         * call sites. Equivalent to passing {@code null}, {@code List.of()},
         * and {@code null} for the new fields.
         */
        public StartRequest(String message, String model,
                            List<HistoryEntry> history, Map<String, Object> options) {
            this(message, model, history, options, null, List.of(), null);
        }
    }

    /**
     * Tool advertised to the sidecar so CrewAI can present it to its agents.
     * The sidecar materialises a CrewAI {@code BaseTool} subclass from each
     * descriptor; invocations route back to the Java side via the
     * {@code toolCallbackUrl} on {@link StartRequest}.
     *
     * @param name        unique tool name as exposed to CrewAI
     * @param description human-readable description for the LLM
     * @param parameters  ordered list of parameter descriptors; may be empty
     * @param returnType  JSON-schema type of the return value (defaults to
     *                    {@code "string"} when null/blank)
     */
    record ToolDescriptor(String name, String description,
                          List<ParameterDescriptor> parameters, String returnType) {

        public ToolDescriptor {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be null/blank");
            }
            parameters = parameters != null ? List.copyOf(parameters) : List.of();
        }
    }

    /**
     * One parameter of a {@link ToolDescriptor}.
     *
     * @param name        parameter name (must be a valid identifier)
     * @param type        JSON-schema type (string, integer, number, boolean,
     *                    array, object)
     * @param description human-readable description; may be empty
     * @param required    whether the model must supply this parameter
     */
    record ParameterDescriptor(String name, String type,
                               String description, boolean required) {

        public ParameterDescriptor {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("parameter name must not be null/blank");
            }
        }
    }

    /**
     * One element of conversation history sent to the sidecar.
     *
     * @param role    {@code user}, {@code assistant}, or {@code system}
     * @param content the message text
     */
    record HistoryEntry(String role, String content) { }

    /**
     * Open SSE stream. Owns the underlying HTTP connection; callers MUST
     * call {@link #close()} on every terminal path.
     */
    interface SidecarSession extends AutoCloseable {

        /** The sidecar-assigned session id (used by {@link #cancelSession(String)}). */
        String sessionId();

        /**
         * Blocking iterator over the SSE event stream. {@link Iterator#hasNext()}
         * blocks until the next frame arrives or the stream ends.
         */
        Iterator<SidecarEvent> events();

        @Override
        void close();
    }

    /**
     * Wire-level event the sidecar may emit. Sealed so the dispatcher in
     * {@link CrewAiAgentRuntime} exhaustively handles every variant.
     */
    sealed interface SidecarEvent permits SidecarEvent.Token, SidecarEvent.Usage,
            SidecarEvent.Done, SidecarEvent.Error {

        /** One token / text chunk from the active agent. */
        record Token(String text) implements SidecarEvent { }

        /** Token usage report at end-of-run. */
        record Usage(TokenUsage usage) implements SidecarEvent { }

        /** Successful completion. The stream MUST end after this frame. */
        record Done() implements SidecarEvent { }

        /** Sidecar-reported error. The stream MUST end after this frame. */
        record Error(String message) implements SidecarEvent { }
    }
}
