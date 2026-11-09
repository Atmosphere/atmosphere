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
package org.atmosphere.admin.ai;

import org.atmosphere.ai.lineage.InMemoryLineageRecorder;
import org.atmosphere.ai.lineage.LineageEntry;
import org.atmosphere.ai.lineage.LineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorderHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read surface for per-turn lineage: which agent configuration
 * ({@code ai.agent.*} fingerprint attributes) ran each turn, the tools it
 * called, its token use, cost and how it ended. Serves
 * {@code GET /api/admin/ai/lineage}.
 *
 * <p>Reads whatever {@link LineageRecorderHolder} holds on every call, so the
 * answer is the running state: the buffered entries when an
 * {@link InMemoryLineageRecorder} is installed, an empty list otherwise (no
 * recorder, or one that persists elsewhere and has nothing to read back
 * here). The entries carry user and conversation ids, so the HTTP routes sit
 * on the admin's content-read gate.</p>
 */
public final class LineageController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1000;

    /** Most-recent-first lineage entries, capped at {@code limit} (default 50, max 1000). */
    public List<Map<String, Object>> recent(int limit) {
        if (!(LineageRecorderHolder.get() instanceof InMemoryLineageRecorder buffer)) {
            return List.of();
        }
        var n = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
        var out = new ArrayList<Map<String, Object>>(n);
        for (var entry : buffer.recent(n)) {
            out.add(toMap(entry));
        }
        return out;
    }

    /**
     * What is recording: {@code recorder} is {@code none}, {@code in-memory}
     * or the installed recorder's class name; for the in-memory recorder,
     * its capacity, size and whether it keeps prompt and tool-result text.
     */
    public Map<String, Object> status() {
        var recorder = LineageRecorderHolder.get();
        var status = new LinkedHashMap<String, Object>();
        if (recorder == LineageRecorder.NOOP) {
            status.put("recorder", "none");
        } else if (recorder instanceof InMemoryLineageRecorder buffer) {
            status.put("recorder", "in-memory");
            status.put("capacity", buffer.capacity());
            status.put("size", buffer.size());
            status.put("captureContent", buffer.captureContent());
        } else {
            status.put("recorder", recorder.getClass().getName());
        }
        return status;
    }

    static Map<String, Object> toMap(LineageEntry entry) {
        var map = new LinkedHashMap<String, Object>();
        map.put("requestId", entry.requestId());
        map.put("userId", entry.userId());
        map.put("agentId", entry.agentId());
        map.put("conversationId", entry.conversationId());
        map.put("sessionId", entry.sessionId());
        map.put("timestamp", entry.timestamp().toString());
        map.put("userPrompt", entry.userPrompt());
        var tools = new ArrayList<Map<String, Object>>(entry.toolCalls().size());
        for (var call : entry.toolCalls()) {
            tools.add(Map.of(
                    "name", call.name(),
                    "invokedAt", call.invokedAt().toString(),
                    "resultSummary", call.resultSummary()));
        }
        map.put("toolCalls", tools);
        map.put("ragSources", entry.ragSources());
        entry.tokens().ifPresent(usage -> {
            var tokens = new LinkedHashMap<String, Object>();
            tokens.put("input", usage.input());
            tokens.put("output", usage.output());
            tokens.put("cachedInput", usage.cachedInput());
            tokens.put("total", usage.total());
            if (usage.model() != null) {
                tokens.put("model", usage.model());
            }
            map.put("tokens", tokens);
        });
        entry.cost().ifPresent(cost -> map.put("costUsd", cost.toPlainString()));
        map.put("terminalReason", entry.terminalReason());
        map.put("attributes", entry.attributes());
        return map;
    }
}
