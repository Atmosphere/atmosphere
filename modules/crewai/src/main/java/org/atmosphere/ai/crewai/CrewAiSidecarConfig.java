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
package org.atmosphere.ai.crewai;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;

/**
 * Discovery + connection settings for the out-of-process CrewAI Python
 * sidecar. The Java half stays simple: where to reach the sidecar, and
 * how long to wait for it.
 *
 * <p>Resolution order — first non-blank value wins:</p>
 * <ol>
 *   <li>{@code ATMOSPHERE_CREWAI_SIDECAR_URL} environment variable</li>
 *   <li>{@code atmosphere.crewai.sidecar.url} system property</li>
 * </ol>
 *
 * <p>Returns {@link Optional#empty()} from {@link #discover()} when neither
 * is set so the runtime can advertise {@code isAvailable() == false} per
 * Correctness Invariant #5 (Runtime Truth) instead of pretending a sidecar
 * exists.</p>
 *
 * <p>Timeouts:</p>
 * <ul>
 *   <li>{@code atmosphere.crewai.sidecar.healthTimeoutMs} — {@code GET /health}
 *       probe timeout (default {@code 2000ms})</li>
 *   <li>{@code atmosphere.crewai.sidecar.requestTimeoutMs} — per-request
 *       timeout for session-control endpoints (default {@code 60000ms})</li>
 * </ul>
 */
public record CrewAiSidecarConfig(URI baseUrl, Duration healthTimeout, Duration requestTimeout) {

    /** Environment variable consulted first by {@link #discover()}. */
    public static final String ENV_URL = "ATMOSPHERE_CREWAI_SIDECAR_URL";

    /** System property consulted second by {@link #discover()}. */
    public static final String SYS_URL = "atmosphere.crewai.sidecar.url";

    /** System property for the health-probe timeout in milliseconds. */
    public static final String SYS_HEALTH_TIMEOUT_MS = "atmosphere.crewai.sidecar.healthTimeoutMs";

    /** System property for per-request timeout in milliseconds. */
    public static final String SYS_REQUEST_TIMEOUT_MS = "atmosphere.crewai.sidecar.requestTimeoutMs";

    /** Default health-probe timeout — short, so a missing sidecar fails fast. */
    public static final Duration DEFAULT_HEALTH_TIMEOUT = Duration.ofSeconds(2);

    /** Default per-request timeout — long, so a real crew can run. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    public CrewAiSidecarConfig {
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl must not be null");
        }
        if (healthTimeout == null || healthTimeout.isNegative() || healthTimeout.isZero()) {
            throw new IllegalArgumentException("healthTimeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    /**
     * Resolve a sidecar config from the environment / system properties.
     *
     * @return a populated config when the sidecar URL is discoverable, or
     *         {@link Optional#empty()} when neither {@link #ENV_URL} nor
     *         {@link #SYS_URL} is set.
     */
    public static Optional<CrewAiSidecarConfig> discover() {
        var raw = firstNonBlank(System.getenv(ENV_URL), System.getProperty(SYS_URL));
        if (raw == null) {
            return Optional.empty();
        }
        URI parsed;
        try {
            parsed = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (parsed.getScheme() == null || parsed.getHost() == null) {
            // Boundary-safety: reject malformed input here rather than
            // letting HttpClient throw later. Returns empty so
            // isAvailable() honestly reports "no sidecar reachable".
            return Optional.empty();
        }
        return Optional.of(new CrewAiSidecarConfig(
                parsed,
                durationFromProperty(SYS_HEALTH_TIMEOUT_MS, DEFAULT_HEALTH_TIMEOUT),
                durationFromProperty(SYS_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT)));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static Duration durationFromProperty(String key, Duration fallback) {
        var raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            var millis = Long.parseLong(raw.trim());
            if (millis <= 0) {
                return fallback;
            }
            return Duration.ofMillis(millis);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
