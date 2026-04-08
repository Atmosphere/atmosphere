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
package org.atmosphere.admin.coordinator;

import org.atmosphere.coordinator.fleet.AgentFleet;
import org.atmosphere.coordinator.fleet.AgentProxy;
import org.atmosphere.coordinator.journal.CoordinationEvent;
import org.atmosphere.coordinator.journal.CoordinationJournal;
import org.atmosphere.coordinator.journal.CoordinationQuery;
import org.atmosphere.coordinator.journal.JournalFormat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read operations for coordinator fleets and the coordination journal.
 *
 * <p>This controller operates on explicitly provided {@link AgentFleet} and
 * {@link CoordinationJournal} instances rather than discovering them from the
 * framework, since fleet instances are injected into coordinator handlers at
 * startup and not stored in a global registry.</p>
 *
 * @since 4.0
 */
public final class CoordinatorController {

    private final Map<String, AgentFleet> fleets;
    private final CoordinationJournal journal;

    /**
     * @param fleets  map of coordinator name to fleet instance
     * @param journal the coordination journal (may be {@link CoordinationJournal#NOOP})
     */
    public CoordinatorController(Map<String, AgentFleet> fleets,
                                  CoordinationJournal journal) {
        this.fleets = fleets != null ? Map.copyOf(fleets) : Map.of();
        this.journal = journal != null ? journal : CoordinationJournal.NOOP;
    }

    /**
     * List all coordinators and their fleet summaries.
     */
    public List<Map<String, Object>> listCoordinators() {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : fleets.entrySet()) {
            var info = new LinkedHashMap<String, Object>();
            info.put("name", entry.getKey());
            var fleet = entry.getValue();
            info.put("agentCount", fleet.agents().size());
            info.put("availableCount", fleet.available().size());
            result.add(info);
        }
        return result;
    }

    /**
     * Get the fleet detail for a specific coordinator.
     */
    public Optional<Map<String, Object>> getFleet(String coordinatorName) {
        var fleet = fleets.get(coordinatorName);
        if (fleet == null) {
            return Optional.empty();
        }
        var info = new LinkedHashMap<String, Object>();
        info.put("name", coordinatorName);

        var agents = new ArrayList<Map<String, Object>>();
        for (AgentProxy proxy : fleet.agents()) {
            var agentInfo = new LinkedHashMap<String, Object>();
            agentInfo.put("name", proxy.name());
            agentInfo.put("version", proxy.version());
            agentInfo.put("isAvailable", proxy.isAvailable());
            agentInfo.put("isLocal", proxy.isLocal());
            agentInfo.put("weight", proxy.weight());
            agents.add(agentInfo);
        }
        info.put("agents", agents);
        return Optional.of(info);
    }

    /**
     * Query the coordination journal with optional filters.
     */
    public List<Map<String, Object>> queryJournal(String coordinationId,
                                                   String agentName,
                                                   Instant since,
                                                   Instant until,
                                                   int limit) {
        var query = CoordinationQuery.all();
        if (coordinationId != null) {
            query = CoordinationQuery.forCoordination(coordinationId);
        } else if (agentName != null) {
            query = CoordinationQuery.forAgent(agentName);
        }
        // Apply time bounds and limit if the query supports them
        var events = journal.query(query);
        var result = new ArrayList<Map<String, Object>>();
        for (CoordinationEvent event : events) {
            if (since != null && event.timestamp().isBefore(since)) {
                continue;
            }
            if (until != null && event.timestamp().isAfter(until)) {
                continue;
            }
            result.add(eventToMap(event));
            if (limit > 0 && result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /**
     * Get the formatted log for a specific coordination.
     */
    public Optional<String> getJournalLog(String coordinationId) {
        var events = journal.retrieve(coordinationId);
        if (events.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(journal.formatLog(
                CoordinationQuery.forCoordination(coordinationId),
                JournalFormat.STANDARD_LOG));
    }

    private Map<String, Object> eventToMap(CoordinationEvent event) {
        var info = new LinkedHashMap<String, Object>();
        info.put("type", event.getClass().getSimpleName());
        info.put("coordinationId", event.coordinationId());
        info.put("timestamp", event.timestamp().toString());

        switch (event) {
            case CoordinationEvent.CoordinationStarted e ->
                    info.put("coordinatorName", e.coordinatorName());
            case CoordinationEvent.AgentDispatched e -> {
                info.put("agentName", e.agentName());
                info.put("skill", e.skill());
            }
            case CoordinationEvent.AgentCompleted e -> {
                info.put("agentName", e.agentName());
                info.put("skill", e.skill());
                info.put("duration", e.duration().toString());
            }
            case CoordinationEvent.AgentFailed e -> {
                info.put("agentName", e.agentName());
                info.put("skill", e.skill());
                info.put("error", e.error());
            }
            case CoordinationEvent.AgentEvaluated e -> {
                info.put("agentName", e.agentName());
                info.put("evaluatorName", e.evaluatorName());
                info.put("score", e.score());
                info.put("passed", e.passed());
            }
            case CoordinationEvent.AgentHandoff e -> {
                info.put("fromAgent", e.fromAgent());
                info.put("toAgent", e.toAgent());
                info.put("reason", e.reason());
            }
            case CoordinationEvent.RouteEvaluated e -> {
                info.put("inputAgent", e.inputAgent());
                info.put("selectedAgent", e.selectedAgent());
                info.put("matched", e.matched());
            }
            case CoordinationEvent.CoordinationCompleted e -> {
                info.put("totalDuration", e.totalDuration().toString());
                info.put("agentCallCount", e.agentCallCount());
            }
            case CoordinationEvent.AgentActivityChanged e -> {
                info.put("agentName", e.agentName());
                info.put("activityType", e.activityType());
                info.put("detail", e.detail());
            }
            case CoordinationEvent.CircuitStateChanged e -> {
                info.put("agentName", e.agentName());
                info.put("fromState", e.fromState());
                info.put("toState", e.toState());
            }
        }
        return info;
    }
}
