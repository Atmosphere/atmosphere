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
package org.atmosphere.ai.code;

import java.util.Locale;

/**
 * Configuration for the in-process {@code eval} tool — a sandboxed JavaScript
 * evaluator distinct from the container-backed {@code code_exec}. Resolved from
 * {@code org.atmosphere.ai.eval.*} system properties (each overridable by the
 * equivalent {@code ORG_ATMOSPHERE_AI_EVAL_*} environment variable).
 *
 * <p><strong>Default deny.</strong> {@link #enabled()} defaults to {@code false}:
 * evaluating arbitrary model-generated code is an explicit opt-in (Correctness
 * Invariant #6). When enabled the evaluator is still bounded — {@link
 * #instructionBudget()} caps CPU so a runaway loop throws instead of hanging,
 * {@link #timeout()} is a wall-clock ceiling, and {@link #maxOutputChars()}
 * caps the returned text.</p>
 *
 * @param enabled           master switch; {@code false} (default) disables the tool entirely
 * @param instructionBudget interpreted-instruction ceiling per evaluation (CPU guard)
 * @param timeoutMillis      wall-clock ceiling per evaluation, in milliseconds
 * @param maxOutputChars    cap on the serialized result length returned to the model
 */
public record EvalConfig(
        boolean enabled,
        int instructionBudget,
        long timeoutMillis,
        int maxOutputChars) {

    /** System property (env {@code ORG_ATMOSPHERE_AI_EVAL_ENABLED}) — master switch. */
    public static final String ENABLED = "org.atmosphere.ai.eval.enabled";
    /** System property — interpreted-instruction ceiling per evaluation. */
    public static final String INSTRUCTION_BUDGET = "org.atmosphere.ai.eval.instructionBudget";
    /** System property — wall-clock ceiling per evaluation, in milliseconds. */
    public static final String TIMEOUT_MILLIS = "org.atmosphere.ai.eval.timeoutMillis";
    /** System property — cap on the serialized result length. */
    public static final String MAX_OUTPUT_CHARS = "org.atmosphere.ai.eval.maxOutputChars";

    /** Default instruction budget — generous for computation, fatal to infinite loops. */
    public static final int DEFAULT_INSTRUCTION_BUDGET = 10_000_000;
    /** Default wall-clock ceiling per evaluation. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;
    /** Default cap on returned text. */
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 8_000;

    /** Canonicalize: floor the bounds so a misconfiguration cannot disable the guards. */
    public EvalConfig {
        if (instructionBudget < 1_000) {
            instructionBudget = 1_000;
        }
        if (timeoutMillis < 100L) {
            timeoutMillis = 100L;
        }
        if (maxOutputChars < 1) {
            maxOutputChars = 1;
        }
    }

    /** Resolve from {@code org.atmosphere.ai.eval.*} configuration (default deny). */
    public static EvalConfig fromSystemProperties() {
        return new EvalConfig(
                parseBoolean(ENABLED, false),
                parseInt(INSTRUCTION_BUDGET, DEFAULT_INSTRUCTION_BUDGET),
                parseLong(TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS),
                parseInt(MAX_OUTPUT_CHARS, DEFAULT_MAX_OUTPUT_CHARS));
    }

    private static String resolve(String key, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key.replace('.', '_').toUpperCase(Locale.ROOT));
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean parseBoolean(String key, boolean fallback) {
        String value = resolve(key, null);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int parseInt(String key, int fallback) {
        String value = resolve(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String key, long fallback) {
        String value = resolve(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
