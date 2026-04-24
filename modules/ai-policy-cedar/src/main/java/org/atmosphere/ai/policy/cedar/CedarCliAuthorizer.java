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
package org.atmosphere.ai.policy.cedar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link CedarAuthorizer} that shells out to the {@code cedar} CLI binary
 * via {@code cedar authorize}. Requires {@code cedar} on the operator's
 * PATH (or an absolute path via the constructor).
 *
 * <p>Builds a temporary policy file + entities file + request JSON,
 * invokes {@code cedar authorize --policies policy.cedar --entities
 * entities.json --request-json request.json}, parses the JSON result.
 * Cedar's decision shape is
 * {@code {"decision": "Allow"|"Deny", "diagnostics": {"reason": [...]}}}.</p>
 *
 * <p>Fail-closed on subprocess error, timeout, or malformed output —
 * matches the admission-gate contract (Correctness Invariant #2).</p>
 */
public final class CedarCliAuthorizer implements CedarAuthorizer {

    private static final Logger logger = LoggerFactory.getLogger(CedarCliAuthorizer.class);

    /** Default timeout per evaluation. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final String cedarPath;
    private final Duration timeout;

    public CedarCliAuthorizer() {
        this("cedar", DEFAULT_TIMEOUT);
    }

    public CedarCliAuthorizer(String cedarPath, Duration timeout) {
        if (cedarPath == null || cedarPath.isBlank()) {
            throw new IllegalArgumentException("cedarPath must not be blank");
        }
        this.cedarPath = cedarPath;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public Result authorize(String cedarSource,
                            String principal,
                            String action,
                            String resource,
                            Map<String, Object> context) {
        Path policyFile = null;
        Path requestFile = null;
        try {
            policyFile = Files.createTempFile("atmosphere-cedar-", ".cedar");
            requestFile = Files.createTempFile("atmosphere-cedar-req-", ".json");
            Files.writeString(policyFile, cedarSource, StandardCharsets.UTF_8);
            Files.writeString(requestFile,
                    buildRequestJson(principal, action, resource, context),
                    StandardCharsets.UTF_8);

            var pb = new ProcessBuilder(cedarPath, "authorize",
                    "--policies", policyFile.toAbsolutePath().toString(),
                    "--request-json", requestFile.toAbsolutePath().toString());
            pb.redirectErrorStream(false);
            var process = pb.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                logger.warn("cedar authorize timed out after {} ms — denying fail-closed",
                        timeout.toMillis());
                return Result.deny("cedar authorize timeout", List.of());
            }
            var exit = process.exitValue();
            if (exit != 0 && exit != 1) {
                // Cedar CLI returns 0 on Allow, 1 on Deny, anything else is error.
                var err = new String(process.getErrorStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                logger.warn("cedar authorize exited {}: {}", exit, err);
                return Result.deny("cedar authorize failed: " + err.trim(), List.of());
            }
            var out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return parseResult(out);
        } catch (IOException e) {
            logger.error("cedar authorize IO failed: {}", e.toString());
            return Result.deny("cedar authorize io failure: " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.deny("cedar authorize interrupted", List.of());
        } finally {
            safeDelete(policyFile);
            safeDelete(requestFile);
        }
    }

    /**
     * Build the Cedar CLI request JSON. Cedar expects:
     * <pre>{@code
     * {"principal": "...", "action": "...", "resource": "...", "context": {...}}
     * }</pre>
     * Package-private for tests.
     */
    static String buildRequestJson(String principal, String action, String resource,
                                   Map<String, Object> context) {
        var sb = new StringBuilder("{");
        sb.append("\"principal\":\"").append(escape(principal)).append('"');
        sb.append(",\"action\":\"").append(escape(action)).append('"');
        sb.append(",\"resource\":\"").append(escape(resource)).append('"');
        sb.append(",\"context\":");
        appendJson(sb, context);
        sb.append('}');
        return sb.toString();
    }

    /**
     * Parse Cedar CLI JSON output. Shape:
     * {@code {"decision": "Allow"|"Deny", "diagnostics": {"reason": [...], ...}}}.
     * Package-private for tests.
     */
    static Result parseResult(String json) {
        if (json == null || json.isBlank()) {
            return Result.deny("cedar authorize produced no output", List.of());
        }
        // Find decision field — it's a string literal.
        var decisionIdx = json.indexOf("\"decision\"");
        if (decisionIdx < 0) {
            return Result.deny("cedar output missing 'decision' field", List.of());
        }
        var colon = json.indexOf(':', decisionIdx);
        if (colon < 0) return Result.deny("cedar output malformed", List.of());
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') {
            return Result.deny("cedar 'decision' value not a string", List.of());
        }
        var valueStart = i + 1;
        var valueEnd = json.indexOf('"', valueStart);
        if (valueEnd < 0) return Result.deny("cedar decision malformed", List.of());
        var decision = json.substring(valueStart, valueEnd);
        var matched = parseMatchedPolicies(json);
        if ("Allow".equalsIgnoreCase(decision)) {
            return Result.allow(matched);
        }
        return Result.deny("cedar denied", matched);
    }

    private static List<String> parseMatchedPolicies(String json) {
        var idx = json.indexOf("\"reason\"");
        if (idx < 0) return List.of();
        var open = json.indexOf('[', idx);
        if (open < 0) return List.of();
        var close = json.indexOf(']', open);
        if (close < 0) return List.of();
        var list = new java.util.ArrayList<String>();
        var inner = json.substring(open + 1, close);
        int p = 0;
        while (p < inner.length()) {
            var q1 = inner.indexOf('"', p);
            if (q1 < 0) break;
            var q2 = inner.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            list.add(inner.substring(q1 + 1, q2));
            p = q2 + 1;
        }
        return list;
    }

    private static void appendJson(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            var first = true;
            for (var entry : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                appendJson(sb, entry.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            var first = true;
            for (var e : it) {
                if (!first) sb.append(',');
                first = false;
                appendJson(sb, e);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(v.toString())).append('"');
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            logger.trace("failed to delete temp file {}", p, e);
        }
    }
}
