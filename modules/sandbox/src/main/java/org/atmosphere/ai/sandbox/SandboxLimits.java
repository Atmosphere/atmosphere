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
package org.atmosphere.ai.sandbox;

import java.time.Duration;

/**
 * Resource bounds for a sandbox instance. Defaults match the v0.6 plan
 * open-question answer: 1 CPU · 512 MB · 5 min wall · no network.
 *
 * @param cpuFraction   CPU fraction (e.g. {@code 1.0} for one full core)
 * @param memoryBytes   maximum memory in bytes
 * @param wallTime      maximum wall-clock time for any single exec
 * @param networkPolicy egress policy; {@link NetworkPolicy#NONE} by default
 */
public record SandboxLimits(
        double cpuFraction,
        long memoryBytes,
        Duration wallTime,
        NetworkPolicy networkPolicy) {

    public static final SandboxLimits DEFAULT = new SandboxLimits(
            1.0,
            512L * 1024L * 1024L,
            Duration.ofMinutes(5),
            NetworkPolicy.NONE);

    public SandboxLimits {
        if (cpuFraction <= 0) {
            throw new IllegalArgumentException("cpuFraction must be > 0, got " + cpuFraction);
        }
        if (memoryBytes <= 0) {
            throw new IllegalArgumentException("memoryBytes must be > 0, got " + memoryBytes);
        }
        if (wallTime == null || wallTime.isNegative() || wallTime.isZero()) {
            throw new IllegalArgumentException("wallTime must be positive, got " + wallTime);
        }
        if (networkPolicy == null) {
            networkPolicy = NetworkPolicy.NONE;
        }
    }

    /**
     * Legacy boolean-network constructor kept for callers that have not
     * migrated to {@link NetworkPolicy}. {@code true} maps to
     * {@link NetworkPolicy#FULL}; {@code false} maps to
     * {@link NetworkPolicy#NONE}. Prefer the {@link NetworkPolicy} overload
     * for new code.
     */
    public SandboxLimits(double cpuFraction, long memoryBytes, Duration wallTime, boolean network) {
        this(cpuFraction, memoryBytes, wallTime,
                network ? NetworkPolicy.FULL : NetworkPolicy.NONE);
    }

    /**
     * Whether this sandbox has any network egress. Equivalent to
     * {@code networkPolicy().hasEgress()}.
     */
    public boolean network() {
        return networkPolicy.hasEgress();
    }
}
