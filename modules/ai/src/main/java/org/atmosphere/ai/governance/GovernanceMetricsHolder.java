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

/**
 * Static installation point for {@link GovernanceMetrics}, matching the
 * {@link GovernanceDecisionLog} pattern. Spring Boot / Quarkus auto-config
 * installs a Micrometer-backed impl on startup; unit-level callers stay on
 * {@link GovernanceMetrics#NOOP} unless they opt in via {@link #install}.
 */
public final class GovernanceMetricsHolder {

    private static volatile GovernanceMetrics installed = GovernanceMetrics.NOOP;

    private GovernanceMetricsHolder() { }

    public static void install(GovernanceMetrics metrics) {
        installed = metrics == null ? GovernanceMetrics.NOOP : metrics;
    }

    public static GovernanceMetrics get() {
        return installed;
    }

    /** Reset to NOOP — intended for tests. */
    public static void reset() {
        installed = GovernanceMetrics.NOOP;
    }
}
