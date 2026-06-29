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
 * entities.json --request-json request.json --verbose}, then parses the
 * decision the binary actually emits.</p>
 *
 * <p><b>Real {@code cedar authorize} contract (verified against
 * cedar-policy-cli 4.11.x).</b> {@code --entities} is a <i>mandatory</i>
 * argument — omitting it makes the CLI exit with a usage error, never an
 * authorization decision. The command prints a plain-text decision token
 * on stdout — {@code ALLOW} or {@code DENY}, one per line (preceded by a
 * blank line) — there is <i>no</i> {@code --format json} on
 * {@code authorize} and no {@code {"decision": ...}} JSON envelope. Exit
 * codes: {@code 0} on Allow, {@code 2} on Deny, {@code 1} on a
 * policy/entities/request parse error. {@code --verbose} appends a
 * {@code note: this decision was due to the following policies:} block
 * listing the matched policy ids (the {@code @id(...)} annotation when
 * present, otherwise {@code policyN}).</p>
 *
 * <p>Because a real Deny and a CLI usage error both exit {@code 2}, the
 * decision is read from the stdout token and corroborated against the
 * exit code; any other combination (empty stdout, parse-error
 * diagnostic, mismatched exit code) is treated as an error and denied
 * fail-closed.</p>
 *
 * <p>Fail-closed on subprocess error, timeout, or unparseable output —
 * matches the admission-gate contract (Correctness Invariant #2).</p>
 */
public final class CedarCliAuthorizer implements CedarAuthorizer {

    private static final Logger logger = LoggerFactory.getLogger(CedarCliAuthorizer.class);

    /** Default timeout per evaluation. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Entity hierarchy passed to the mandatory {@code --entities} argument.
     * The default policy shape ({@code permit(principal, action, resource)}
     * keyed on entity UIDs) declares no entities, so an empty hierarchy
     * {@code []} satisfies the argument. Operators with an entity store
     * implement their own {@link CedarAuthorizer} (the SPI) and supply a
     * populated hierarchy.
     */
    private static final String ENTITIES_HIERARCHY = "[]";

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
        Path entitiesFile = null;
        Path requestFile = null;
        try {
            policyFile = Files.createTempFile("atmosphere-cedar-", ".cedar");
            entitiesFile = Files.createTempFile("atmosphere-cedar-ent-", ".json");
            requestFile = Files.createTempFile("atmosphere-cedar-req-", ".json");
            Files.writeString(policyFile, cedarSource, StandardCharsets.UTF_8);
            // cedar authorize REQUIRES --entities; an empty hierarchy is "[]".
            Files.writeString(entitiesFile, ENTITIES_HIERARCHY, StandardCharsets.UTF_8);
            Files.writeString(requestFile,
                    buildRequestJson(principal, action, resource, context),
                    StandardCharsets.UTF_8);

            var pb = new ProcessBuilder(cedarPath, "authorize",
                    "--policies", policyFile.toAbsolutePath().toString(),
                    "--entities", entitiesFile.toAbsolutePath().toString(),
                    "--request-json", requestFile.toAbsolutePath().toString(),
                    "--verbose");
            pb.redirectErrorStream(false);
            var process = pb.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                logger.warn("cedar authorize timed out after {} ms — denying fail-closed",
                        timeout.toMillis());
                return Result.deny("cedar authorize timeout", List.of());
            }
            var out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            var err = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return parseDecision(process.exitValue(), out, err);
        } catch (IOException e) {
            logger.error("cedar authorize IO failed: {}", e.toString());
            return Result.deny("cedar authorize io failure: " + e.getMessage(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.deny("cedar authorize interrupted", List.of());
        } finally {
            safeDelete(policyFile);
            safeDelete(entitiesFile);
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
     * Parse the decision the real {@code cedar authorize --verbose} binary
     * emits. stdout carries a plain-text {@code ALLOW} / {@code DENY} token
     * (one line, after a leading blank line), optionally followed by a
     * {@code note: this decision was due to the following policies:} block.
     * Exit codes: {@code 0} → Allow, {@code 2} → Deny. A real Deny and a CLI
     * usage error both exit {@code 2}, so the decision is taken from the
     * stdout token <i>corroborated</i> by the exit code; any inconsistency
     * (empty stdout, parse-error diagnostic, mismatched code) denies
     * fail-closed with the diagnostic. Package-private for tests.
     *
     * @param exitCode the process exit value
     * @param stdout   the captured standard output
     * @param stderr   the captured standard error (diagnostic on failure)
     */
    static Result parseDecision(int exitCode, String stdout, String stderr) {
        var decision = decisionToken(stdout);
        if ("ALLOW".equals(decision) && exitCode == 0) {
            return Result.allow(parseMatchedPolicies(stdout));
        }
        if ("DENY".equals(decision) && exitCode == 2) {
            return Result.deny("cedar policy denied", parseMatchedPolicies(stdout));
        }
        var diag = !stderr.isBlank() ? stderr.trim()
                : (stdout != null && !stdout.isBlank() ? stdout.trim() : "no output");
        logger.warn("cedar authorize did not yield a clean decision (exit {}): {}",
                exitCode, diag);
        return Result.deny("cedar authorize error (exit " + exitCode + "): " + diag,
                List.of());
    }

    /**
     * First non-blank line of stdout, upper-cased, when it is exactly
     * {@code ALLOW} or {@code DENY}; otherwise {@code null}. Parse-error
     * diagnostics (which start with miette graphics, not a bare token) and
     * empty output return {@code null}.
     */
    private static String decisionToken(String stdout) {
        if (stdout == null) {
            return null;
        }
        for (var line : stdout.split("\n", -1)) {
            var trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            var upper = trimmed.toUpperCase(java.util.Locale.ROOT);
            return ("ALLOW".equals(upper) || "DENY".equals(upper)) ? upper : null;
        }
        return null;
    }

    private static final String POLICIES_NOTE =
            "note: this decision was due to the following policies:";

    /**
     * Parse the verbose matched-policy block — the indented policy ids that
     * follow the {@code note: this decision was due to the following
     * policies:} line, up to the next blank line.
     */
    private static List<String> parseMatchedPolicies(String stdout) {
        if (stdout == null) {
            return List.of();
        }
        var lines = stdout.split("\n", -1);
        var list = new java.util.ArrayList<String>();
        var collecting = false;
        for (var line : lines) {
            if (!collecting) {
                if (line.strip().startsWith(POLICIES_NOTE)) {
                    collecting = true;
                }
                continue;
            }
            var trimmed = line.strip();
            if (trimmed.isEmpty()) {
                break;
            }
            list.add(trimmed);
        }
        return List.copyOf(list);
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
