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

import java.time.Duration;

/**
 * Resolved configuration for the code-as-action sandbox substrate. Read from
 * {@code org.atmosphere.ai.code.*} system properties (overridable by environment
 * variables of the same name with dots replaced by underscores, upper-cased).
 *
 * <p><strong>Default deny.</strong> {@link #enabled()} defaults to {@code false}
 * — executing model-generated code is off unless an operator explicitly opts in
 * (Correctness Invariant #6). Every other field is a hardening bound applied to
 * the per-session sandbox.</p>
 *
 * @param enabled        master switch; {@code false} (default) disables code execution entirely
 * @param engine         container engine: {@code "auto"} (default), {@code "docker"}, or {@code "podman"}
 * @param image          container image providing the interpreters + browsers; required when enabled
 * @param network        container network mode; {@code "none"} (default) = no network
 * @param memory         memory cap in container-engine syntax (e.g. {@code "512m"})
 * @param cpus           CPU cap (e.g. {@code 1.0})
 * @param pidsLimit      max processes inside the sandbox
 * @param execTimeout    default per-command wall-clock budget
 * @param sandboxTtl     max total lifetime of a sandbox before it is force-closed
 * @param maxOutputBytes per-command stdout/stderr capture cap (Backpressure, Invariant #3)
 * @param setup          optional one-time bootstrap command run (via {@code bash -lc})
 *                       once when the sandbox container starts — e.g. to install a
 *                       language package the image does not ship. Blank = no setup.
 */
public record CodeSandboxConfig(
        boolean enabled,
        String engine,
        String image,
        String network,
        String memory,
        double cpus,
        int pidsLimit,
        Duration execTimeout,
        Duration sandboxTtl,
        int maxOutputBytes,
        String setup) {

    public static final String ENABLED = "org.atmosphere.ai.code.enabled";
    public static final String ENGINE = "org.atmosphere.ai.code.engine";
    public static final String IMAGE = "org.atmosphere.ai.code.image";
    public static final String NETWORK = "org.atmosphere.ai.code.network";
    public static final String MEMORY = "org.atmosphere.ai.code.memory";
    public static final String CPUS = "org.atmosphere.ai.code.cpus";
    public static final String PIDS_LIMIT = "org.atmosphere.ai.code.pidsLimit";
    public static final String EXEC_TIMEOUT_SECONDS = "org.atmosphere.ai.code.execTimeoutSeconds";
    public static final String SANDBOX_TTL_SECONDS = "org.atmosphere.ai.code.sandboxTtlSeconds";
    public static final String MAX_OUTPUT_BYTES = "org.atmosphere.ai.code.maxOutputBytes";
    public static final String SETUP = "org.atmosphere.ai.code.setup";

    /**
     * Default sandbox image: Microsoft's official Playwright image (Ubuntu
     * noble), which bundles node, the browsers, and python3. Pinned to a
     * specific tag rather than {@code latest} for reproducibility; verified
     * against the {@code mcr.microsoft.com} registry. Operators override via
     * {@link #IMAGE}.
     */
    public static final String DEFAULT_IMAGE = "mcr.microsoft.com/playwright:v1.60.0-noble";

    private static final String DEFAULT_ENGINE = "auto";
    private static final String DEFAULT_NETWORK = "none";
    private static final String DEFAULT_MEMORY = "512m";
    private static final double DEFAULT_CPUS = 1.0d;
    private static final int DEFAULT_PIDS_LIMIT = 256;
    private static final Duration DEFAULT_EXEC_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_SANDBOX_TTL = Duration.ofSeconds(300);
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 256 * 1024;

    public CodeSandboxConfig {
        engine = blankToDefault(engine, DEFAULT_ENGINE);
        network = blankToDefault(network, DEFAULT_NETWORK);
        memory = blankToDefault(memory, DEFAULT_MEMORY);
        image = image == null ? "" : image.trim();
        if (cpus <= 0) {
            cpus = DEFAULT_CPUS;
        }
        if (pidsLimit <= 0) {
            pidsLimit = DEFAULT_PIDS_LIMIT;
        }
        execTimeout = positiveOrDefault(execTimeout, DEFAULT_EXEC_TIMEOUT);
        sandboxTtl = positiveOrDefault(sandboxTtl, DEFAULT_SANDBOX_TTL);
        if (maxOutputBytes <= 0) {
            maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        }
        setup = setup == null ? "" : setup.trim();
    }

    /**
     * A disabled configuration — the default-deny baseline used when nothing is
     * configured.
     */
    public static CodeSandboxConfig disabled() {
        return new CodeSandboxConfig(false, DEFAULT_ENGINE, "", DEFAULT_NETWORK,
                DEFAULT_MEMORY, DEFAULT_CPUS, DEFAULT_PIDS_LIMIT,
                DEFAULT_EXEC_TIMEOUT, DEFAULT_SANDBOX_TTL, DEFAULT_MAX_OUTPUT_BYTES, "");
    }

    /**
     * Resolve configuration from {@code org.atmosphere.ai.code.*} system
     * properties / environment variables. Missing keys fall back to the
     * hardened defaults; {@link #enabled()} is {@code false} unless explicitly
     * set truthy.
     */
    public static CodeSandboxConfig fromSystemProperties() {
        return new CodeSandboxConfig(
                parseBoolean(ENABLED, false),
                resolve(ENGINE, DEFAULT_ENGINE),
                resolve(IMAGE, DEFAULT_IMAGE),
                resolve(NETWORK, DEFAULT_NETWORK),
                resolve(MEMORY, DEFAULT_MEMORY),
                parseDouble(CPUS, DEFAULT_CPUS),
                parseInt(PIDS_LIMIT, DEFAULT_PIDS_LIMIT),
                Duration.ofSeconds(parseLong(EXEC_TIMEOUT_SECONDS, DEFAULT_EXEC_TIMEOUT.toSeconds())),
                Duration.ofSeconds(parseLong(SANDBOX_TTL_SECONDS, DEFAULT_SANDBOX_TTL.toSeconds())),
                parseInt(MAX_OUTPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES),
                resolve(SETUP, ""));
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    // --- system property / environment resolution -----------------------------

    private static String resolve(String key, String fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            value = System.getenv(key.replace('.', '_').toUpperCase(java.util.Locale.ROOT));
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

    private static double parseDouble(String key, double fallback) {
        String value = resolve(key, null);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
