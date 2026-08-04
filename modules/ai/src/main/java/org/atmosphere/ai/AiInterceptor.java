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

import org.atmosphere.cpr.AtmosphereResource;

/**
 * Interceptor for AI requests. Allows cross-cutting concerns like RAG context
 * augmentation, guardrails, logging, and cost tracking to be applied without
 * modifying the {@code @Prompt} method.
 *
 * <p>Interceptors are specified on {@link org.atmosphere.ai.annotation.AiEndpoint#interceptors()}
 * and are executed in declaration order across three phases:</p>
 * <ul>
 *   <li>{@link #preProcess} runs FIFO (first declared → first executed),
 *       before the request is dispatched to the runtime</li>
 *   <li>{@link #beforeCompletion} runs LIFO, from inside the terminal
 *       {@code complete()} — immediately BEFORE the terminal {@code complete}
 *       frame is forwarded to the client, while the session still accepts
 *       {@link StreamingSession#sendMetadata}/{@link StreamingSession#emit}</li>
 *   <li>{@link #postProcess} runs LIFO (last declared → first executed),
 *       matching the {@code AtmosphereInterceptor} convention — strictly AFTER
 *       the terminal frame has been forwarded (runtimes complete the session
 *       mid-execute), so outbound frames sent from it are dropped</li>
 * </ul>
 *
 * <p>Interceptors run only on the {@code @AiEndpoint} dispatch path.
 * {@link AiPipeline} (channel bridge, {@code @Coordinator}, A2A, AG-UI)
 * intentionally runs no {@code AiInterceptor}s because this interface requires
 * an {@code AtmosphereResource} the pipeline does not have; on that path
 * {@code routing.*} metadata comes from
 * {@code org.atmosphere.ai.routing.RoutingLlmClient} mid-stream.</p>
 */
public interface AiInterceptor {

    /**
     * Called before the request is sent to the {@link AiSupport}.
     * Return a modified request (e.g., with augmented message or different model)
     * or the original request unchanged.
     *
     * @param request  the AI request
     * @param resource the atmosphere resource for this client
     * @return the (possibly modified) request
     */
    default AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        return request;
    }

    /**
     * Called on the {@code @AiEndpoint} dispatch path immediately BEFORE the
     * terminal {@code complete} frame is forwarded to the client — the last
     * moment {@link StreamingSession#sendMetadata} and
     * {@link StreamingSession#emit} still reach the wire. Runs in LIFO order
     * (last declared → first executed), mirroring {@link #postProcess}.
     *
     * <p>Contract:</p>
     * <ul>
     *   <li>Implementations must be fast — no LLM calls, no blocking I/O.
     *       Work that may be slow belongs in {@link #postProcess}, which runs
     *       after the terminal frame and cannot delay it.</li>
     *   <li>Hooks MUST NOT invoke terminal methods
     *       ({@link StreamingSession#complete()} /
     *       {@link StreamingSession#error(Throwable)}) on the session — the
     *       dispatcher is already inside terminal processing and forwards the
     *       terminal frame itself once all hooks finish. A re-entrant terminal
     *       call is logged at WARN and ignored.</li>
     *   <li>Anything thrown — {@link Exception} or {@link Error} alike — is
     *       caught and logged by the dispatcher and never suppresses the
     *       terminal frame (Correctness Invariant #2).</li>
     *   <li>Invoked at most once per {@code stream()} invocation; a second
     *       {@code stream()} on the same session arms it again. Bounded
     *       residual edge: a pathological trailing terminal call from a
     *       cancelled prior turn may consume the new turn's arming and fire
     *       the hook early — at most one early fire, never two fires, never
     *       a hang.</li>
     *   <li>NOT invoked on the {@code error()} terminal path — and an
     *       {@code error()} disarms the turn, so a bridge that completes the
     *       session after erroring does not fire the hook either.</li>
     *   <li>Invoked on endpoint response-cache hits too — the turn completed,
     *       it just never reached the runtime.</li>
     * </ul>
     *
     * @param request  the AI request (as modified by preProcess)
     * @param session  the composed streaming session for this turn — the same
     *                 decorator target stored under
     *                 {@link AiStreamingSession#STREAMING_SESSION_ATTR}.
     *                 Frames sent here reach the wire and the streaming
     *                 observers, but layers that finalize on {@code complete()}
     *                 (e.g. lineage capture) have already snapshotted their
     *                 state by the time the hook runs
     * @param resource the atmosphere resource for this client
     */
    default void beforeCompletion(AiRequest request, StreamingSession session,
                                  AtmosphereResource resource) {
    }

    /**
     * Called after the {@link AiSupport} has finished streaming and the
     * terminal frame has been forwarded — the underlying session no longer
     * accepts outbound frames at this point. Use {@link #beforeCompletion}
     * for anything that must reach the client.
     *
     * @param request  the AI request (as modified by preProcess)
     * @param resource the atmosphere resource for this client
     */
    default void postProcess(AiRequest request, AtmosphereResource resource) {
    }

    /**
     * Called when a client disconnects, BEFORE conversation memory is cleared.
     * Interceptors can use this to extract facts, persist summaries, or perform
     * other cleanup that requires access to the conversation history.
     *
     * <p>Called in FIFO order (declaration order).</p>
     *
     * @param userId         the user identifier (may be null)
     * @param conversationId the conversation identifier (resource UUID)
     * @param history        the conversation history (unmodifiable, may be empty)
     */
    default void onDisconnect(String userId, String conversationId,
                              java.util.List<org.atmosphere.ai.llm.ChatMessage> history) {
    }
}
