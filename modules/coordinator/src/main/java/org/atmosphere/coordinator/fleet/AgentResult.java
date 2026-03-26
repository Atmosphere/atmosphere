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
import java.util.Map;

/**
 * Result from an agent delegation call.
 */
public record AgentResult(
        String agentName,
        String skillId,
        String text,
        Map<String, Object> metadata,
        Duration duration,
        boolean success
) {

    public AgentResult {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /** Get text content or a fallback if the call failed. */
    public String textOr(String fallback) {
        return success ? text : fallback;
    }

    /** Create a failure result. */
    public static AgentResult failure(String agentName, String skillId,
                                      String error, Duration duration) {
        return new AgentResult(agentName, skillId, error, Map.of(), duration, false);
    }
}
