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
package org.atmosphere.ai.policy.rego;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link RegoEvaluator} that shells out to the {@code opa eval} binary.
 * Requires the {@code opa} CLI on the operator's PATH (or an absolute
 * path via {@link #OpaSubprocessEvaluator(String, Duration)}).
 *
 * <p>Writes the Rego module and input JSON to temp files, invokes
 * {@code opa eval -i input.json -d policy.rego 'data.atmosphere.allow'},
 * parses the JSON result. Opa's decision shape is
 * {@code {"result": [{"expressions": [{"value": true, "text": "...", "location": {...}}]}]}};
 * the parser only reads {@code result[0].expressions[0].value} — a boolean
 * or an object with {@code allow} + {@code reason}.</p>
 *
 * <p><b>Note:</b> the framework ships <i>no JSON library</i> as a required
 * dep for this module — we parse the minimal shape by hand. Operators who
 * need richer OPA integration implement their own {@link RegoEvaluator}
 * with their preferred JSON library.</p>
 */
public final class OpaSubprocessEvaluator implements RegoEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(OpaSubprocessEvaluator.class);

    /** Default timeout per evaluation — OPA eval is fast (~ms) locally. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final String opaPath;
    private final Duration timeout;

    /** Default: {@code opa} on PATH, 5 s timeout. */
    public OpaSubprocessEvaluator() {
        this("opa", DEFAULT_TIMEOUT);
    }

    /**
     * @param opaPath absolute or PATH-relative path to the {@code opa} binary
     * @param timeout per-evaluation deadline
     */
    public OpaSubprocessEvaluator(String opaPath, Duration timeout) {
        if (opaPath == null || opaPath.isBlank()) {
            throw new IllegalArgumentException("opaPath must not be blank");
        }
        this.opaPath = opaPath;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public Result evaluate(String regoSource, String query, Map<String, Object> input) {
        Path regoFile = null;
        Path inputFile = null;
        try {
            regoFile = Files.createTempFile("atmosphere-rego-", ".rego");
            inputFile = Files.createTempFile("atmosphere-rego-input-", ".json");
            Files.writeString(regoFile, regoSource, StandardCharsets.UTF_8);
            Files.writeString(inputFile, toJson(input), StandardCharsets.UTF_8);

            var pb = new ProcessBuilder(opaPath, "eval",
                    "--format", "json",
                    "--data", regoFile.toAbsolutePath().toString(),
                    "--input", inputFile.toAbsolutePath().toString(),
                    query);
            pb.redirectErrorStream(false);
            var process = pb.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                logger.warn("opa eval timed out after {} ms — denying fail-closed", timeout.toMillis());
                return Result.deny("opa eval timeout", "");
            }
            if (process.exitValue() != 0) {
                var err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                logger.warn("opa eval exited {}: {}", process.exitValue(), err);
                return Result.deny("opa eval failed: " + err.trim(), "");
            }
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseResult(out);
        } catch (IOException e) {
            logger.error("opa eval IO failed: {}", e.toString());
            return Result.deny("opa eval io failure: " + e.getMessage(), "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.deny("opa eval interrupted", "");
        } finally {
            safeDelete(regoFile);
            safeDelete(inputFile);
        }
    }

    /**
     * Parse the minimal shape we need:
     * {@code {"result": [{"expressions": [{"value": <v>}]}]}}.
     * {@code <v>} is either a boolean (traditional OPA allow=true/false) or
     * an object with {@code allow} / {@code reason} / {@code matched_rule}.
     * Package-private for tests.
     */
    static Result parseResult(String json) {
        if (json == null || json.isBlank()) {
            return Result.deny("opa eval produced no output", "");
        }
        // Surgical extraction — looking only for the outermost "value" field.
        var valueStart = json.indexOf("\"value\"");
        if (valueStart < 0) {
            return Result.deny("opa output missing 'value' field", "");
        }
        var colon = json.indexOf(':', valueStart);
        if (colon < 0) {
            return Result.deny("opa output malformed near 'value'", "");
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) {
            return Result.deny("opa output truncated", "");
        }
        char c = json.charAt(i);
        if (c == 't' && json.regionMatches(i, "true", 0, 4)) {
            return Result.allow();
        }
        if (c == 'f' && json.regionMatches(i, "false", 0, 5)) {
            return Result.deny("opa policy returned false", "");
        }
        if (c == '{') {
            return parseAllowObject(json, i);
        }
        return Result.deny("opa output value is neither boolean nor object: "
                + truncate(json, 80), "");
    }

    private static Result parseAllowObject(String json, int start) {
        // Look for "allow": true/false and optional "reason" / "matched_rule".
        var allowIdx = indexOfField(json, "allow", start);
        if (allowIdx < 0) {
            return Result.deny("opa object missing 'allow'", "");
        }
        int v = skipToValue(json, allowIdx);
        if (v < 0) return Result.deny("opa 'allow' value malformed", "");
        if (json.regionMatches(v, "true", 0, 4)) {
            return Result.allow();
        }
        String reason = "";
        String matched = "";
        int reasonIdx = indexOfField(json, "reason", start);
        if (reasonIdx >= 0) {
            reason = readString(json, skipToValue(json, reasonIdx));
        }
        int matchedIdx = indexOfField(json, "matched_rule", start);
        if (matchedIdx >= 0) {
            matched = readString(json, skipToValue(json, matchedIdx));
        }
        return Result.deny(reason, matched);
    }

    private static int indexOfField(String json, String field, int from) {
        // Cheap substring search for "<field>" — adequate because our
        // parser handles a small, controlled set of fields.
        var needle = "\"" + field + "\"";
        return json.indexOf(needle, from);
    }

    private static int skipToValue(String json, int fieldStart) {
        int colon = json.indexOf(':', fieldStart);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        return i;
    }

    private static String readString(String json, int start) {
        if (start < 0 || start >= json.length() || json.charAt(start) != '"') return "";
        var sb = new StringBuilder();
        int i = start + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                var n = json.charAt(i + 1);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> sb.append(n);
                }
                i += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Minimal JSON encoder for the input document. Values are strings,
     * numbers, booleans, nested maps, or lists. Anything else is toString()-coerced.
     * Package-private for tests.
     */
    static String toJson(Map<String, Object> input) {
        var sb = new StringBuilder("{");
        var first = true;
        if (input != null) {
            for (var entry : input.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(entry.getKey())).append("\":");
                appendValue(sb, entry.getValue());
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Boolean b) sb.append(b);
        else if (v instanceof Number n) sb.append(n);
        else if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            var cast = (Map<String, Object>) m;
            sb.append(toJson(cast));
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            var first = true;
            for (var e : it) {
                if (!first) sb.append(',');
                first = false;
                appendValue(sb, e);
            }
            sb.append(']');
        } else {
            sb.append('"').append(escape(v.toString())).append('"');
        }
    }

    private static String escape(String s) {
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
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
