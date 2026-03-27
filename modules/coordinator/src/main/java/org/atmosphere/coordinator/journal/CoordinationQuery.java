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
package org.atmosphere.coordinator.journal;

import java.time.Instant;

/**
 * Query filter for retrieving coordination events from a {@link CoordinationJournal}.
 * Null fields are treated as wildcards (match all).
 */
public record CoordinationQuery(
        String coordinationId,
        String agentName,
        Instant since,
        Instant until,
        int limit
) {

    /** Query all events with no filter. */
    public static CoordinationQuery all() {
        return new CoordinationQuery(null, null, null, null, 0);
    }

    /** Query all events for a specific coordination. */
    public static CoordinationQuery forCoordination(String id) {
        return new CoordinationQuery(id, null, null, null, 0);
    }

    /** Query all events for a specific agent across all coordinations. */
    public static CoordinationQuery forAgent(String agentName) {
        return new CoordinationQuery(null, agentName, null, null, 0);
    }
}
