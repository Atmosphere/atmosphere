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
package org.atmosphere.ai.lineage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Bounded ring-buffer {@link LineageRecorder}: the recorder the Spring Boot
 * and Quarkus integrations install by default, read back by the admin's
 * {@code GET /api/admin/ai/lineage}. Mirrors
 * {@code GovernanceDecisionLog}'s ring-buffer shape so operators get a
 * uniform mental model across the audit surfaces.
 *
 * <p>Production deployments wire a recorder that persists to Kafka /
 * Postgres / S3 — this class is not designed to survive process restart.</p>
 *
 * <p>Built with {@code captureContent = false} — what the Spring Boot and
 * Quarkus integrations install by default — the recorder blanks
 * {@link LineageEntry#userPrompt()} and every tool call's
 * {@link LineageEntry.ToolCall#resultSummary()} before buffering, drops the
 * streamed tool-argument attributes ({@code ai.toolCall.delta.*}) and cuts
 * every other attribute value to {@value #MAX_REDACTED_ATTRIBUTE_CHARS}
 * characters. What stays is the attribution: ids, the {@code ai.agent.*}
 * fingerprint attributes, tool names and times, RAG source identifiers,
 * tokens, cost and the terminal reason. {@code ai.*} metadata an application
 * sends itself is kept (cut to that length), so an application that puts
 * user content there redacts it itself. The constructors without that flag
 * keep the content.</p>
 */
public final class InMemoryLineageRecorder implements LineageRecorder {

    /**
     * Default capacity — bounded so the recorder never exhibits unbounded
     * growth (Correctness Invariant #3, Backpressure). Operators that want
     * more history install a recorder backed by a persistent sink.
     */
    public static final int DEFAULT_CAPACITY = 1000;

    /** Longest attribute value kept when content capture is off. */
    public static final int MAX_REDACTED_ATTRIBUTE_CHARS = 256;

    private static final String TOOL_ARGUMENT_DELTA_PREFIX = "ai.toolCall.delta.";

    private final int capacity;
    private final boolean captureContent;
    private final Deque<LineageEntry> entries;

    public InMemoryLineageRecorder() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryLineageRecorder(int capacity) {
        this(capacity, true);
    }

    /**
     * @param capacity       maximum number of buffered entries; the oldest is
     *                       evicted first
     * @param captureContent {@code false} to blank the user prompt and tool
     *                       result summaries before buffering
     */
    public InMemoryLineageRecorder(int capacity, boolean captureContent) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        }
        this.capacity = capacity;
        this.captureContent = captureContent;
        this.entries = new ArrayDeque<>(Math.min(capacity, 256));
    }

    /** @return whether prompt and tool-result text are kept */
    public boolean captureContent() {
        return captureContent;
    }

    /** @return the maximum number of buffered entries */
    public int capacity() {
        return capacity;
    }

    @Override
    public void record(LineageEntry entry) {
        if (entry == null) {
            return;
        }
        if (!captureContent) {
            entry = withoutContent(entry);
        }
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
    }

    /** Snapshot of the most-recent entries, newest first, up to {@code limit}. */
    public List<LineageEntry> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (entries) {
            var size = entries.size();
            var n = Math.min(limit, size);
            var out = new ArrayList<LineageEntry>(n);
            var it = entries.descendingIterator();
            while (it.hasNext() && out.size() < n) {
                out.add(it.next());
            }
            return List.copyOf(out);
        }
    }

    /** Total entries currently buffered. */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** Clear every entry. */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private static LineageEntry withoutContent(LineageEntry entry) {
        var toolCalls = new ArrayList<LineageEntry.ToolCall>(entry.toolCalls().size());
        for (var call : entry.toolCalls()) {
            toolCalls.add(new LineageEntry.ToolCall(call.name(), call.invokedAt(), ""));
        }
        var attributes = new LinkedHashMap<String, String>();
        entry.attributes().forEach((key, value) -> {
            if (!key.startsWith(TOOL_ARGUMENT_DELTA_PREFIX)) {
                attributes.put(key, value.length() <= MAX_REDACTED_ATTRIBUTE_CHARS
                        ? value
                        : value.substring(0, MAX_REDACTED_ATTRIBUTE_CHARS - 1) + "…");
            }
        });
        return new LineageEntry(entry.requestId(), entry.userId(), entry.agentId(),
                entry.conversationId(), entry.sessionId(), entry.timestamp(), "",
                toolCalls, entry.ragSources(), entry.tokens(), entry.cost(),
                entry.terminalReason(), attributes);
    }
}
