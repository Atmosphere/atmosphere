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
package org.atmosphere.a2a.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Deterministic JSON serialization for JWS signing input. Produces the same
 * bytes for semantically-equal JSON regardless of property insertion order,
 * so a signer and a verifier compute an identical payload.
 *
 * <h2>Scope (RFC 8785 JCS subset)</h2>
 *
 * Implements the JSON Canonicalization Scheme for the value space the A2A
 * {@code AgentCard} graph actually uses — objects, arrays, strings, booleans,
 * {@code null}, and integral numbers:
 * <ul>
 *   <li>object members are emitted sorted by property name using UTF-16 code
 *       unit order (all card keys are ASCII, where this coincides with both
 *       RFC 8785's code-point order and {@link String#compareTo});</li>
 *   <li>no insignificant whitespace;</li>
 *   <li>strings are escaped exactly as JSON / {@code JSON.stringify} (the
 *       escaping JCS mandates) — delegated to Jackson so control characters,
 *       quotes, and backslashes match the spec and non-ASCII stays literal
 *       UTF-8.</li>
 * </ul>
 *
 * <p>Floating-point canonicalization (RFC 8785's ECMAScript number algorithm)
 * is deliberately <em>not</em> implemented: the {@code AgentCard} object graph
 * contains no fractional numbers. Encountering one throws rather than emitting
 * a non-canonical approximation — a signature must never be computed over bytes
 * that a different conformant verifier could not reproduce (Correctness
 * Invariant #5, Runtime Truth: do not advertise an interoperable signature we
 * cannot actually produce).</p>
 */
public final class JsonCanonicalizer {

    private final ObjectMapper mapper;

    public JsonCanonicalizer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Canonical UTF-8 bytes for {@code node}. */
    public byte[] canonicalize(JsonNode node) {
        var sb = new StringBuilder();
        write(node, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void write(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
        } else if (node.isObject()) {
            writeObject(node, sb);
        } else if (node.isArray()) {
            writeArray(node, sb);
        } else if (node.isBoolean()) {
            sb.append(node.booleanValue() ? "true" : "false");
        } else if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                sb.append(node.bigIntegerValue().toString());
            } else {
                throw new UnsupportedOperationException(
                        "JCS floating-point canonicalization is not implemented; "
                                + "the AgentCard graph must not contain fractional numbers");
            }
        } else {
            // String — let Jackson apply spec-correct JSON string escaping.
            sb.append(mapper.writeValueAsString(node.stringValue()));
        }
    }

    private void writeObject(JsonNode node, StringBuilder sb) {
        var names = new ArrayList<String>();
        node.propertyNames().forEach(names::add);
        // RFC 8785: sort by the UTF-16 code units of the property name.
        names.sort(null);
        sb.append('{');
        for (var i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            var name = names.get(i);
            sb.append(mapper.writeValueAsString(name)).append(':');
            write(node.get(name), sb);
        }
        sb.append('}');
    }

    private void writeArray(JsonNode node, StringBuilder sb) {
        sb.append('[');
        for (var i = 0; i < node.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            write(node.get(i), sb);
        }
        sb.append(']');
    }
}
