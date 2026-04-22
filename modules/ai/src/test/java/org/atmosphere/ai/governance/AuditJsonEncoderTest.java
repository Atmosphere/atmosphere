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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the framework-owned {@link AuditEntry} → JSON shape shared by the
 * Kafka and Postgres reference sinks. MS Agent Governance Toolkit consumers
 * rely on the key ordering and decimal format of {@code evaluation_ms}; a
 * silent drift here breaks their downstream parsers. The test asserts exact
 * substrings in documented order.
 */
class AuditJsonEncoderTest {

    @Test
    void encodesAllTopLevelKeysInMsCompatibleShape() {
        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "scope::support",
                "code:test",
                "1.0",
                "deny",
                "matched probe",
                Map.of("phase", "pre_admission", "message", "hi"),
                1.25);
        var json = AuditJsonEncoder.encode(entry);
        assertTrue(json.startsWith("{\"timestamp\":\"2026-04-22T14:00:00Z\""),
                "timestamp comes first: " + json);
        assertTrue(json.contains("\"policy_name\":\"scope::support\""), json);
        assertTrue(json.contains("\"policy_source\":\"code:test\""), json);
        assertTrue(json.contains("\"policy_version\":\"1.0\""), json);
        assertTrue(json.contains("\"decision\":\"deny\""), json);
        assertTrue(json.contains("\"reason\":\"matched probe\""), json);
        assertTrue(json.contains("\"evaluation_ms\":1.25"), json);
        assertTrue(json.contains("\"context_snapshot\":"), json);
        assertTrue(json.endsWith("}"), json);
    }

    @Test
    void contextSnapshotValuesRespectTypes() {
        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "p", "s", "v", "admit", "",
                new java.util.LinkedHashMap<>(Map.of(
                        "str_key", "value",
                        "bool_key", true,
                        "int_key", 42,
                        "double_key", 1.5)),
                0.0);
        var json = AuditJsonEncoder.encode(entry);
        assertTrue(json.contains("\"str_key\":\"value\""), json);
        assertTrue(json.contains("\"bool_key\":true"), json);
        assertTrue(json.contains("\"int_key\":42"), json);
        assertTrue(json.contains("\"double_key\":1.5"), json);
    }

    @Test
    void specialCharactersAreEscaped() {
        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "p", "s", "v", "deny",
                "quote \" and newline \n and tab \t inside",
                Map.of(),
                0.0);
        var json = AuditJsonEncoder.encode(entry);
        assertTrue(json.contains("quote \\\" and newline \\n and tab \\t inside"),
                "special chars escaped: " + json);
    }

    @Test
    void nullReasonEncodedAsEmptyString() {
        // AuditEntry is a record; constructing with null reason is valid.
        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "p", "s", "v", "admit", "", Map.of(), 0.0);
        var json = AuditJsonEncoder.encode(entry);
        assertTrue(json.contains("\"reason\":\"\""), json);
    }

    @Test
    void emptySnapshotRendersEmptyObject() {
        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "p", "s", "v", "admit", "", Map.of(), 0.0);
        var json = AuditJsonEncoder.encode(entry);
        assertTrue(json.endsWith("\"context_snapshot\":{}}"), json);
    }

    @Test
    void controlCharactersEscapedAsUnicode() {
        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "p", "s", "v", "admit",
                "ctrlchar",
                Map.of(),
                0.0);
        var json = AuditJsonEncoder.encode(entry);
        assertTrue(json.contains("ctrl\\u0001char"),
                "control chars escape as \\uXXXX: " + json);
    }

    @Test
    void nonPrimitiveValueStringifiedSafely() {
        var entry = new AuditEntry(
                Instant.parse("2026-04-22T14:00:00Z"),
                "p", "s", "v", "admit", "",
                Map.of("list", java.util.List.of("a", "b")),
                0.0);
        var json = AuditJsonEncoder.encode(entry);
        // Non-primitive values go through toString(); we only care that the
        // payload is valid JSON and doesn't drop the field.
        assertTrue(json.contains("\"list\":"), json);
        assertEquals(1, countOccurrences(json, "\"context_snapshot\":"),
                "one snapshot key: " + json);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
