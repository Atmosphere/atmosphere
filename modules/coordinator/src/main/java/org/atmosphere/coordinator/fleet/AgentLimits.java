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
package org.atmosphere.coordinator.fleet;

import java.time.Duration;

/**
 * Per-agent resource constraints. Replaces the blunt global
 * {@code parallelTimeoutMs} with fine-grained per-agent control.
 *
 * <p>Configured via {@code @AgentRef(timeoutMs = 30000)} or constructed
 * programmatically. Zero/default values mean "use fleet default".</p>
 *
 * @param timeout  maximum time for a single agent call (0 = fleet default)
 * @param maxTurns maximum round-trips for iterative protocols (Integer.MAX_VALUE = unlimited)
 */
public record AgentLimits(Duration timeout, int maxTurns) {

    /** Default limits: 120s timeout, unlimited turns. */
    public static final AgentLimits DEFAULT = new AgentLimits(
            Duration.ofSeconds(120), Integer.MAX_VALUE);

    /** Create limits with only a timeout override. */
    public static AgentLimits withTimeout(Duration timeout) {
        return new AgentLimits(timeout, Integer.MAX_VALUE);
    }

    /** Returns true if this instance uses the default timeout. */
    public boolean isDefaultTimeout() {
        return timeout.equals(DEFAULT.timeout);
    }
}
