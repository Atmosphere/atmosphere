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

import java.util.Map;

/**
 * Minimal JSON encoder for {@link AuditEntry}, shared by the Kafka and
 * Postgres reference sinks. The shape is fixed so we don't pull Jackson
 * in as a transitive: eight top-level keys + a flat {@code context_snapshot}
 * map whose values are string / boolean / number (framework contract via
 * {@code GovernanceDecisionLog.snapshotContext}). Anything outside that
 * shape is coerced through {@code toString()} before emission.
 *
 * <p>Output matches the Microsoft Agent Governance Toolkit
 * {@code audit_entry} payload — a deployment that ships AuditEntry to
 * Kafka with this encoder can be consumed by the same downstream tooling
 * that reads MS's own audit topic.</p>
 */
public final class AuditJsonEncoder {

    private AuditJsonEncoder() { }

    public static String encode(AuditEntry entry) {
        var sb = new StringBuilder(256);
        sb.append('{');
        appendString(sb, "timestamp", entry.timestamp().toString());
        sb.append(',');
        appendString(sb, "policy_name", entry.policyName());
        sb.append(',');
        appendString(sb, "policy_source", entry.policySource());
        sb.append(',');
        appendString(sb, "policy_version", entry.policyVersion());
        sb.append(',');
        appendString(sb, "decision", entry.decision());
        sb.append(',');
        appendString(sb, "reason", entry.reason());
        sb.append(',');
        appendNumber(sb, "evaluation_ms", entry.evaluationMs());
        sb.append(',');
        sb.append("\"context_snapshot\":");
        appendMap(sb, entry.contextSnapshot());
        sb.append('}');
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append("\":");
        appendJsonString(sb, value);
    }

    private static void appendNumber(StringBuilder sb, String key, double value) {
        sb.append('"').append(key).append("\":").append(value);
    }

    private static void appendMap(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        if (map != null && !map.isEmpty()) {
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (entry.getKey() == null) continue;
                if (!first) sb.append(',');
                first = false;
                appendJsonString(sb, entry.getKey());
                sb.append(':');
                appendValue(sb, entry.getValue());
            }
        }
        sb.append('}');
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Number n) {
            sb.append(n);
        } else {
            appendJsonString(sb, value.toString());
        }
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
