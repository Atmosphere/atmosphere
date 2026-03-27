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

import java.util.List;

/**
 * Pluggable format for rendering coordination journal events as text.
 * Implement this interface for custom formats (Grafana, OpenTelemetry, etc.).
 */
@FunctionalInterface
public interface JournalFormat {

    /** Renders the given events as a formatted string. */
    String format(List<CoordinationEvent> events);

    /** Plain-text log, one line per event. */
    JournalFormat STANDARD_LOG = events -> {
        var sb = new StringBuilder();
        sb.append(events.size()).append(" events:\n");
        for (var event : events) {
            sb.append("  ").append(event.toLogLine()).append('\n');
        }
        return sb.toString();
    };

    /** Markdown table with Event, Agent, Detail, and Duration columns. */
    JournalFormat MARKDOWN = events -> {
        var sb = new StringBuilder();
        sb.append("| Event | Agent | Detail | Duration |\n");
        sb.append("|-------|-------|--------|----------|\n");
        for (var event : events) {
            switch (event) {
                case CoordinationEvent.CoordinationStarted e ->
                        row(sb, "START", "\u2014", e.coordinatorName(), "\u2014");
                case CoordinationEvent.AgentDispatched e ->
                        row(sb, "DISPATCH", e.agentName(), e.skill(), "\u2014");
                case CoordinationEvent.AgentCompleted e ->
                        row(sb, "DONE", e.agentName(), e.skill(),
                                e.duration().toMillis() + "ms");
                case CoordinationEvent.AgentFailed e ->
                        row(sb, "FAILED", e.agentName(), e.error(),
                                e.duration().toMillis() + "ms");
                case CoordinationEvent.AgentEvaluated e ->
                        row(sb, "EVAL", e.agentName(), "score=" + e.score(), "\u2014");
                case CoordinationEvent.CoordinationCompleted e ->
                        row(sb, "COMPLETE", "\u2014", e.agentCallCount() + " calls",
                                e.totalDuration().toMillis() + "ms");
            }
        }
        return sb.toString();
    };

    private static void row(StringBuilder sb, String event, String agent,
                            String detail, String duration) {
        sb.append("| ").append(event)
                .append(" | ").append(agent)
                .append(" | ").append(detail)
                .append(" | ").append(duration)
                .append(" |\n");
    }
}
