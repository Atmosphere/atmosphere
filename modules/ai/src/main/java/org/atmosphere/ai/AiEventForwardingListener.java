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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapter that translates {@link AgentLifecycleListener} model-lifecycle
 * fires ({@link #onModelStart}, {@link #onModelEnd}, {@link #onModelError})
 * into {@link AiEvent.Progress} frames on a {@link StreamingSession}, so
 * browser clients receive a uniform per-round observability event stream
 * regardless of which {@link AgentRuntime} executed the request.
 *
 * <p>Without this adapter, listeners observing model lifecycle events would
 * have to do their own session-emission. With it, callers register a single
 * instance against the request's listener list and the runtime takes care
 * of the wire-protocol fan-out:</p>
 *
 * <pre>{@code
 * var session = StreamingSessions.start("chat", resource);
 * var listeners = List.of(new AiEventForwardingListener(session));
 * runtime.execute(context.withListeners(listeners), session);
 * // → browser receives:
 * //   {"type":"progress","message":"model:start (gpt-4o, msgs=3, tools=2)"}
 * //   {"type":"progress","message":"model:end (gpt-4o, in=120, out=85, ms=842)"}
 * }</pre>
 *
 * <p>This adapter is the canonical "everything-on-the-wire" sink for the
 * model-lifecycle hooks. Custom listeners (Micrometer recorders, audit
 * appenders, structured-log writers) can sit alongside it.</p>
 */
public class AiEventForwardingListener implements AgentLifecycleListener {

    private final StreamingSession session;

    /**
     * @param session the session to emit {@link AiEvent.Progress} frames to.
     *                Must not be null.
     */
    public AiEventForwardingListener(StreamingSession session) {
        if (session == null) {
            throw new IllegalArgumentException("session is required");
        }
        this.session = session;
    }

    @Override
    public void onModelStart(String model, int messageCount, int toolCount) {
        if (session.isClosed()) {
            return;
        }
        var message = "model:start (" + model + ", msgs=" + messageCount
                + ", tools=" + toolCount + ")";
        session.emit(new AiEvent.Progress(message, null));
    }

    @Override
    public void onModelEnd(String model, org.atmosphere.ai.TokenUsage usage, long durationMillis) {
        if (session.isClosed()) {
            return;
        }
        var sb = new StringBuilder("model:end (").append(model);
        if (usage != null) {
            sb.append(", in=").append(usage.input())
              .append(", out=").append(usage.output());
        }
        sb.append(", ms=").append(durationMillis).append(')');
        session.emit(new AiEvent.Progress(sb.toString(), null));
    }

    @Override
    public void onModelError(String model, Throwable error) {
        if (session.isClosed()) {
            return;
        }
        var message = "model:error (" + model + ", "
                + (error != null ? error.getClass().getSimpleName() : "unknown") + ")";
        // Emit on the Progress channel rather than Error: a model-dispatch
        // failure may still resolve via retries (the runtime decides whether
        // to ultimately call session.error). The Progress frame is purely
        // observational — it tells the client "an attempt failed" without
        // claiming the request itself has failed.
        session.emit(new AiEvent.Progress(message, null));
    }

    /**
     * Convenience: build the structured payload that
     * {@code AiEvent.AgentStep#data()} accepts, mirroring the model-lifecycle
     * hook fields. Intended for callers building their own {@link AgentLifecycleListener}
     * who want consistent payload keys without re-implementing the formatting.
     */
    public static Map<String, Object> modelStartPayload(String model, int messageCount, int toolCount) {
        var data = new LinkedHashMap<String, Object>();
        data.put("model", model);
        data.put("messageCount", messageCount);
        data.put("toolCount", toolCount);
        return data;
    }

    /**
     * Convenience companion to {@link #modelStartPayload}: the structured
     * payload for an end-of-call event.
     */
    public static Map<String, Object> modelEndPayload(String model,
                                                       org.atmosphere.ai.TokenUsage usage,
                                                       long durationMillis) {
        var data = new LinkedHashMap<String, Object>();
        data.put("model", model);
        if (usage != null) {
            data.put("inputTokens", usage.input());
            data.put("outputTokens", usage.output());
            data.put("totalTokens", usage.total());
        }
        data.put("durationMillis", durationMillis);
        return data;
    }
}
