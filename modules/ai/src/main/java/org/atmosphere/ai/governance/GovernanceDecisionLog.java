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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe ring buffer of recent {@link AuditEntry} instances plus the
 * framework-wide installation point. Pattern mirrors {@code AiGatewayHolder}
 * in {@code modules/ai} — static accessor with a NOOP default, installed by
 * whatever admin module wires the decision surface (Spring Boot admin
 * auto-configuration, Quarkus admin extension, or direct caller code).
 *
 * <h2>Capacity</h2>
 * {@link #DEFAULT_CAPACITY} (500) balances memory with triage value. Flip via
 * {@link #install(int)} at wiring time; changing it after entries land is
 * safe (existing entries carry over up to the new capacity).
 *
 * <h2>Snapshotting the context</h2>
 * Call sites should use {@link #snapshotContext(PolicyContext)} to build the
 * {@link AuditEntry#contextSnapshot()} map — the snapshot is redaction-safe
 * (truncated message, string/primitive-only metadata values) and matches the
 * MS {@code audit_entry.context_snapshot} shape.
 */
public final class GovernanceDecisionLog {

    private static final Logger logger = LoggerFactory.getLogger(GovernanceDecisionLog.class);

    /** Maximum length of the {@code message} field recorded in the snapshot. */
    public static final int MESSAGE_SNAPSHOT_MAX_CHARS = 200;

    /** Default ring-buffer size when {@link #install(int)} isn't called. */
    public static final int DEFAULT_CAPACITY = 500;

    private static final GovernanceDecisionLog NOOP = new GovernanceDecisionLog(0);
    private static volatile GovernanceDecisionLog installed = NOOP;

    private final int capacity;
    private final Deque<AuditEntry> entries;
    // Persistent sinks fan out from record() — thread-safe list so operators
    // can add/remove sinks while admission traffic is flowing. A failing
    // sink is logged and skipped; the ring-buffer write always succeeds so
    // the admin console view stays available even when Kafka / Postgres are
    // unreachable.
    private final CopyOnWriteArrayList<AuditSink> sinks = new CopyOnWriteArrayList<>();

    private GovernanceDecisionLog(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be >= 0, got: " + capacity);
        }
        this.capacity = capacity;
        this.entries = new ArrayDeque<>(Math.max(16, capacity));
    }

    /** Install a decision log with the given ring-buffer capacity. */
    public static GovernanceDecisionLog install(int capacity) {
        var log = new GovernanceDecisionLog(capacity);
        installed = log;
        return log;
    }

    /** Reset to the NOOP log — intended for tests. Closes all sinks on the outgoing log. */
    public static void reset() {
        var previous = installed;
        installed = NOOP;
        if (previous != NOOP) {
            previous.closeAllSinks();
        }
    }

    /** Current installed log; NOOP when nothing has called {@link #install(int)}. */
    public static GovernanceDecisionLog installed() {
        return installed;
    }

    /**
     * Append an entry to the ring buffer AND fan out to registered
     * {@link AuditSink}s. A sink throwing an exception is logged and
     * isolated — it neither blocks the admission path nor prevents the
     * other sinks from receiving the entry. The ring-buffer update always
     * succeeds on an installed log so the admin console decisions view
     * stays live even when every persistent sink is unreachable.
     *
     * <p>No-op on the default NOOP log (capacity 0).</p>
     */
    public void record(AuditEntry entry) {
        if (capacity == 0 || entry == null) {
            return;
        }
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
        if (!sinks.isEmpty()) {
            for (var sink : sinks) {
                try {
                    sink.write(entry);
                } catch (RuntimeException e) {
                    logger.warn("AuditSink '{}' failed for entry policy={} decision={}: {}",
                            sink.name(), entry.policyName(), entry.decision(),
                            e.toString());
                }
            }
        }
    }

    /**
     * Register a persistent sink. Fan-out is ordered by registration;
     * failures in one sink do not affect others. Safe to call while
     * admission traffic is flowing.
     */
    public GovernanceDecisionLog addSink(AuditSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink must not be null");
        }
        sinks.add(sink);
        return this;
    }

    /** Uninstall a previously-registered sink. Returns true if the sink was present. */
    public boolean removeSink(AuditSink sink) {
        return sinks.remove(sink);
    }

    /** Immutable snapshot of registered sinks — for admin introspection. */
    public List<AuditSink> sinks() {
        return List.copyOf(sinks);
    }

    private void closeAllSinks() {
        for (var sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException e) {
                logger.warn("AuditSink '{}' failed to close: {}", sink.name(), e.toString());
            }
        }
        sinks.clear();
    }

    /** Snapshot of the most-recent entries, newest first, up to {@code limit}. */
    public List<AuditEntry> recent(int limit) {
        if (limit <= 0 || capacity == 0) {
            return List.of();
        }
        synchronized (entries) {
            var snapshot = new ArrayList<AuditEntry>(Math.min(limit, entries.size()));
            var it = entries.descendingIterator();
            while (it.hasNext() && snapshot.size() < limit) {
                snapshot.add(it.next());
            }
            return List.copyOf(snapshot);
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Build a redaction-safe {@link AuditEntry#contextSnapshot()} from a
     * {@link PolicyContext}. Message text is truncated to
     * {@link #MESSAGE_SNAPSHOT_MAX_CHARS}; metadata values that aren't
     * string / boolean / number are coerced via {@code toString()}.
     */
    public static Map<String, Object> snapshotContext(PolicyContext context) {
        if (context == null) {
            return Map.of();
        }
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("phase", context.phase() == PolicyContext.Phase.PRE_ADMISSION
                ? "pre_admission" : "post_response");
        var request = context.request();
        if (request != null) {
            snapshot.put("message", truncate(request.message()));
            putIfNotNull(snapshot, "model", request.model());
            putIfNotNull(snapshot, "user_id", request.userId());
            putIfNotNull(snapshot, "session_id", request.sessionId());
            putIfNotNull(snapshot, "agent_id", request.agentId());
            putIfNotNull(snapshot, "conversation_id", request.conversationId());
            if (request.metadata() != null) {
                for (var entry : request.metadata().entrySet()) {
                    if (entry.getKey() == null) continue;
                    snapshot.putIfAbsent(entry.getKey(), coerce(entry.getValue()));
                }
            }
        }
        if (!context.accumulatedResponse().isEmpty()) {
            snapshot.put("response_preview", truncate(context.accumulatedResponse()));
        }
        return snapshot;
    }

    private static Object coerce(Object value) {
        if (value == null) return "";
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        return value.toString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > MESSAGE_SNAPSHOT_MAX_CHARS
                ? s.substring(0, MESSAGE_SNAPSHOT_MAX_CHARS) + "…"
                : s;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * Convenience — build an {@link AuditEntry} from a {@link GovernancePolicy}
     * evaluation. Call sites pre-compute {@code evaluationMs} and pass the
     * policy's identity fields so a null policy (e.g. a pre-admission deny
     * before a match) doesn't NPE.
     */
    public static AuditEntry entry(GovernancePolicy policy,
                                    PolicyContext context,
                                    String decision,
                                    String reason,
                                    double evaluationMs) {
        var name = policy != null ? policy.name() : "unknown";
        var source = policy != null ? policy.source() : "";
        var version = policy != null ? policy.version() : "";
        return new AuditEntry(
                Instant.now(), name, source, version,
                decision, reason, snapshotContext(context), evaluationMs);
    }

    /** Build an {@link AuditEntry} with an explicit context snapshot (tests). */
    public static AuditEntry entryWithSnapshot(GovernancePolicy policy,
                                                 Map<String, Object> snapshot,
                                                 String decision,
                                                 String reason,
                                                 double evaluationMs) {
        var name = policy != null ? policy.name() : "unknown";
        var source = policy != null ? policy.source() : "";
        var version = policy != null ? policy.version() : "";
        return new AuditEntry(
                Instant.now(), name, source, version, decision, reason, snapshot, evaluationMs);
    }

    /** Package-private — used in the test helper below to build the snapshot standalone. */
    static Map<String, Object> snapshot(AiRequest request, PolicyContext.Phase phase) {
        return snapshotContext(new PolicyContext(phase, request, ""));
    }
}
