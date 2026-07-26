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
package org.atmosphere.quarkus.runtime;

/**
 * Runtime-truth holder for the console's metrics capability flag on Quarkus.
 *
 * <p>{@link AtmosphereMetricsProducer} marks a Prometheus-backed Micrometer
 * registry as live during {@code StartupEvent} — the exact condition under
 * which {@code quarkus-micrometer-registry-prometheus} serves its export
 * route (default {@code /q/metrics}). {@link AtmosphereConsoleInfoServlet}
 * reads the flag when reporting {@code hasMetrics} so the console only
 * fetches a metrics endpoint that genuinely booted, never one merely implied
 * by the build classpath (Runtime Truth — Invariant #5).</p>
 *
 * <p>Deliberately free of Micrometer imports: this class is always loadable
 * by the (always-registered) console-info servlet, including in native
 * images, whether or not the optional metrics stack is present.</p>
 */
final class ConsoleMetricsState {

    private static volatile boolean prometheusLive;

    private ConsoleMetricsState() {
    }

    /** Recorded by {@link AtmosphereMetricsProducer} once the registry booted. */
    static void markPrometheusLive() {
        prometheusLive = true;
    }

    /**
     * Whether a Prometheus registry was confirmed live at startup.
     *
     * @return {@code true} once {@link #markPrometheusLive()} has run
     */
    static boolean prometheusLive() {
        return prometheusLive;
    }
}
