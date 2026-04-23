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
package org.atmosphere.ai.governance.sre;

import java.time.Duration;

/**
 * Declarative SLO — target success ratio over a rolling window. Feeds
 * {@link SloTracker} which accumulates admission-path observations and
 * computes remaining error budget. Tier 5.2 primitive.
 *
 * <p>Tracks two SRE-native quantities:</p>
 * <ul>
 *   <li><b>Error budget</b> — fraction of allowed failures
 *       {@code (1 - target)}. For a 99.9% SLO over 30 days that's ~43
 *       minutes of downtime.</li>
 *   <li><b>Burn rate</b> — how fast the current period is consuming the
 *       budget vs the ideal rate. {@code > 2x} is the canonical "page
 *       the SRE" threshold.</li>
 * </ul>
 *
 * @param name       unique identifier surfaced on metrics + admin API
 * @param target     target success ratio in (0, 1] — e.g. {@code 0.999}
 * @param window     rolling window over which the target applies
 * @param description operator-facing description
 */
public record ServiceLevelObjective(
        String name,
        double target,
        Duration window,
        String description) {

    public ServiceLevelObjective {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (target <= 0.0 || target > 1.0) {
            throw new IllegalArgumentException(
                    "target must be in (0, 1], got: " + target);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "window must be a positive duration");
        }
        description = description == null ? "" : description;
    }

    /** Error budget — fraction of failures the SLO allows. */
    public double errorBudget() {
        return 1.0 - target;
    }

    /** Pre-baked 99.9% availability / 30-day SLO — reasonable default. */
    public static ServiceLevelObjective threeNines(String name) {
        return new ServiceLevelObjective(name, 0.999, Duration.ofDays(30),
                "99.9% availability over 30 days");
    }

    /** Pre-baked 99.99% availability / 30-day SLO — high-availability tier. */
    public static ServiceLevelObjective fourNines(String name) {
        return new ServiceLevelObjective(name, 0.9999, Duration.ofDays(30),
                "99.99% availability over 30 days");
    }
}
