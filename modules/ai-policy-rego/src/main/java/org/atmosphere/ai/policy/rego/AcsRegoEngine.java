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

import org.atmosphere.ai.governance.acs.AcsPolicyEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@link AcsPolicyEngine} for {@code type: rego} ACS policies, backed by the
 * real {@code opa} binary. The manifest's {@code bundle:} reference resolves
 * relative to the manifest directory to a {@code .rego} file, a bundle
 * directory, or a {@code .tar.gz} bundle — the latter two are passed with
 * {@code opa eval --bundle}, a single file with {@code --data}.
 *
 * <p>Fail-closed on every terminal path (Correctness Invariant #6, upstream
 * ACS safety model): missing or escaping bundle paths, opa absence, timeout,
 * non-zero exit and unparseable verdicts all produce a deny verdict with an
 * {@code acs_runtime_error} reason.</p>
 *
 * <p>The verdict query must produce the ACS shape
 * {@code {"decision": "allow"|"deny"|"transform", "reason", "message",
 * "transform": {"path", "value"}}}. A bare boolean result is also accepted
 * ({@code true} → allow, {@code false} → deny) for plain authz queries.</p>
 */
public final class AcsRegoEngine implements AcsPolicyEngine {

    private static final Logger logger = LoggerFactory.getLogger(AcsRegoEngine.class);

    private final String opaPath;
    private final Duration timeout;

    /**
     * ServiceLoader constructor. The binary resolves from the
     * {@code atmosphere.opa.path} system property, then the
     * {@code OPA_BINARY} environment variable, then {@code opa} on PATH —
     * so operators (and CI) point the engine at a specific binary without
     * code. 5 s timeout.
     */
    public AcsRegoEngine() {
        this(resolveOpaPath(), OpaSubprocessEvaluator.DEFAULT_TIMEOUT);
    }

    private static String resolveOpaPath() {
        var candidate = System.getProperty("atmosphere.opa.path");
        if (candidate == null || candidate.isBlank()) {
            candidate = System.getenv("OPA_BINARY");
        }
        return candidate == null || candidate.isBlank() ? "opa" : candidate;
    }

    /**
     * @param opaPath absolute or PATH-relative path to the {@code opa} binary
     * @param timeout per-evaluation deadline
     */
    public AcsRegoEngine(String opaPath, Duration timeout) {
        if (opaPath == null || opaPath.isBlank()) {
            throw new IllegalArgumentException("opaPath must not be blank");
        }
        this.opaPath = opaPath;
        this.timeout = timeout == null ? OpaSubprocessEvaluator.DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public String type() {
        return "rego";
    }

    @Override
    public Verdict evaluate(Evaluation evaluation) {
        var bundleRef = evaluation.policy().bundle();
        if (bundleRef == null || bundleRef.isBlank()) {
            return Verdict.deny(RUNTIME_ERROR,
                    "rego policy '" + evaluation.policyId() + "' declares no bundle");
        }
        Path bundle;
        try {
            bundle = resolveBundle(evaluation.manifestDir(), bundleRef);
        } catch (IllegalArgumentException e) {
            return Verdict.deny(RUNTIME_ERROR, e.getMessage());
        }

        Path inputFile = null;
        try {
            inputFile = Files.createTempFile("atmosphere-acs-input-", ".json");
            Files.writeString(inputFile,
                    OpaSubprocessEvaluator.toJson(evaluation.input()), StandardCharsets.UTF_8);

            var dataFlag = useBundleMode(bundle) ? "--bundle" : "--data";
            var pb = new ProcessBuilder(opaPath, "eval",
                    "--format", "json",
                    dataFlag, bundle.toString(),
                    "--input", inputFile.toAbsolutePath().toString(),
                    evaluation.query());
            pb.redirectErrorStream(false);
            var process = pb.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                logger.warn("opa eval timed out after {} ms for ACS policy '{}' — denying",
                        timeout.toMillis(), evaluation.policyId());
                return Verdict.deny(RUNTIME_ERROR, "opa eval timeout");
            }
            if (process.exitValue() != 0) {
                var err = new String(process.getErrorStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                logger.warn("opa eval exited {} for ACS policy '{}': {}",
                        process.exitValue(), evaluation.policyId(), err.trim());
                return Verdict.deny(RUNTIME_ERROR, "opa eval failed: " + err.trim());
            }
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return parseVerdict(out);
        } catch (IOException e) {
            logger.warn("opa eval IO failure for ACS policy '{}': {}",
                    evaluation.policyId(), e.toString());
            return Verdict.deny(RUNTIME_ERROR, "opa eval io failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.deny(RUNTIME_ERROR, "opa eval interrupted");
        } finally {
            if (inputFile != null) {
                try {
                    Files.deleteIfExists(inputFile);
                } catch (IOException e) {
                    logger.trace("failed to delete temp input {}", inputFile, e);
                }
            }
        }
    }

    /**
     * Resolve the bundle reference against the manifest directory with the
     * standard confinement check: the result must stay under the manifest
     * directory on <em>canonical</em> paths — both sides are canonicalized
     * via {@code toRealPath} once the bundle is known to exist, so a symlink
     * under the manifest directory cannot smuggle the bundle outside it
     * (Correctness Invariant #4 — upstream ships the same escape-rejection
     * semantic in its extends-confinement fixtures).
     */
    static Path resolveBundle(Path manifestDir, String bundleRef) {
        var candidate = Path.of(bundleRef);
        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException(
                    "acs bundle reference must be manifest-relative, got absolute: " + bundleRef);
        }
        if (manifestDir == null) {
            throw new IllegalArgumentException(
                    "acs manifest has no filesystem directory — cannot resolve bundle " + bundleRef);
        }
        var base = manifestDir.toAbsolutePath().normalize();
        var resolved = base.resolve(candidate).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                    "acs bundle reference escapes the manifest directory: " + bundleRef);
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("acs bundle does not exist: " + resolved);
        }
        try {
            // The bundle exists — decide confinement on real paths, not on
            // symlink names the normalize-only check above cannot see through.
            base = base.toRealPath();
            resolved = resolved.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "acs bundle path cannot be canonicalized: " + bundleRef, e);
        }
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                    "acs bundle reference escapes the manifest directory: " + bundleRef);
        }
        return resolved;
    }

    private static boolean useBundleMode(Path bundle) {
        return Files.isDirectory(bundle)
                || bundle.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tar.gz");
    }

    /**
     * Parse {@code result[0].expressions[0].value} as an ACS verdict object
     * or a bare boolean. Package-private for tests.
     */
    static Verdict parseVerdict(String json) {
        if (json == null || json.isBlank()) {
            return Verdict.deny(RUNTIME_ERROR, "opa eval produced no output");
        }
        var valueStart = json.indexOf("\"value\"");
        if (valueStart < 0) {
            // An undefined query result (empty "result": []) means the verdict
            // rule produced nothing — fail closed, never default-allow.
            return Verdict.deny(RUNTIME_ERROR, "opa query produced no verdict value");
        }
        var colon = json.indexOf(':', valueStart);
        if (colon < 0) {
            return Verdict.deny(RUNTIME_ERROR, "opa output malformed near 'value'");
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length()) {
            return Verdict.deny(RUNTIME_ERROR, "opa output truncated");
        }
        char c = json.charAt(i);
        if (c == 't' && json.regionMatches(i, "true", 0, 4)) {
            return Verdict.allow();
        }
        if (c == 'f' && json.regionMatches(i, "false", 0, 5)) {
            return Verdict.deny("rego policy returned false", "");
        }
        if (c != '{') {
            return Verdict.deny(RUNTIME_ERROR,
                    "opa verdict is neither boolean nor object");
        }

        var decision = JsonFields.string(json, i, "decision");
        if (decision == null) {
            return Verdict.deny(RUNTIME_ERROR, "opa verdict object missing 'decision'");
        }
        var reason = orEmpty(JsonFields.string(json, i, "reason"));
        var message = orEmpty(JsonFields.string(json, i, "message"));
        return switch (decision) {
            case "allow" -> Verdict.allow();
            case "deny" -> Verdict.deny(reason, message);
            case "transform" -> {
                var transformStart = JsonFields.objectStart(json, i, "transform");
                if (transformStart < 0) {
                    yield Verdict.deny(RUNTIME_ERROR,
                            "transform verdict missing 'transform' object");
                }
                var path = JsonFields.string(json, transformStart, "path");
                var value = JsonFields.string(json, transformStart, "value");
                if (path == null || value == null) {
                    yield Verdict.deny(RUNTIME_ERROR,
                            "transform verdict missing 'path' or 'value'");
                }
                yield Verdict.transform(path, value);
            }
            default -> Verdict.deny(RUNTIME_ERROR,
                    "unknown ACS decision '" + decision + "'");
        };
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Minimal field extraction over the small, controlled opa output shape —
     * mirrors {@link OpaSubprocessEvaluator}'s approach; this module ships
     * no JSON library by design.
     */
    private static final class JsonFields {

        private JsonFields() {
        }

        static String string(String json, int from, String field) {
            int i = valueStart(json, from, field);
            if (i < 0 || i >= json.length() || json.charAt(i) != '"') {
                return null;
            }
            var sb = new StringBuilder();
            i++;
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
                if (c == '"') {
                    break;
                }
                sb.append(c);
                i++;
            }
            return sb.toString();
        }

        static int objectStart(String json, int from, String field) {
            int i = valueStart(json, from, field);
            return i >= 0 && i < json.length() && json.charAt(i) == '{' ? i : -1;
        }

        private static int valueStart(String json, int from, String field) {
            var needle = '"' + field + '"';
            var idx = from;
            while ((idx = json.indexOf(needle, idx)) >= 0) {
                // Only a KEY has a colon straight after the closing quote —
                // a string VALUE that happens to equal the field name (e.g.
                // "decision": "transform") is followed by ',' or '}'.
                int i = idx + needle.length();
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                    i++;
                }
                if (i < json.length() && json.charAt(i) == ':') {
                    i++;
                    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                        i++;
                    }
                    return i;
                }
                idx += needle.length();
            }
            return -1;
        }
    }
}
