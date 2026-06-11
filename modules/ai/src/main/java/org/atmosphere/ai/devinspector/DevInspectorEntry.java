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
package org.atmosphere.ai.devinspector;

import java.time.Instant;
import java.util.List;

/**
 * One recorded AI turn for the inner-loop dev inspector: the prompt, the
 * response, any tool calls, token usage, and terminal status. Previews are
 * length-capped at construction — this surface is dev-only and must not retain
 * unbounded prompt/response data (Backpressure, Invariant #3).
 *
 * @param at              when the turn completed
 * @param sessionId       the streaming session id
 * @param model           the model used (may be {@code ""})
 * @param promptPreview   truncated prompt text
 * @param responsePreview truncated response text
 * @param toolCalls       tool-call fragments observed during the turn
 * @param tokensIn        input tokens (0 when unknown)
 * @param tokensOut       output tokens (0 when unknown)
 * @param status          {@code "OK"} or {@code "ERROR"}
 * @param error           error message when status is {@code "ERROR"}, else {@code ""}
 */
public record DevInspectorEntry(
        Instant at,
        String sessionId,
        String model,
        String promptPreview,
        String responsePreview,
        List<String> toolCalls,
        long tokensIn,
        long tokensOut,
        String status,
        String error) {

    /** Maximum retained characters per prompt/response preview. */
    public static final int PREVIEW_CAP = 2000;

    public DevInspectorEntry {
        sessionId = sessionId != null ? sessionId : "";
        model = model != null ? model : "";
        promptPreview = truncate(promptPreview);
        responsePreview = truncate(responsePreview);
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        status = status != null ? status : "OK";
        error = error != null ? error : "";
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > PREVIEW_CAP ? s.substring(0, PREVIEW_CAP) + "…[truncated]" : s;
    }
}
