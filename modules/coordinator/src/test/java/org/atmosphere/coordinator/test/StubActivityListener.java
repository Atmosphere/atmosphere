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
package org.atmosphere.coordinator.test;

import org.atmosphere.coordinator.fleet.AgentActivity;
import org.atmosphere.coordinator.fleet.AgentActivityListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test helper that captures {@link AgentActivity} events for assertion.
 *
 * <pre>{@code
 * var listener = new StubActivityListener();
 * var proxy = new DefaultAgentProxy("weather", "1.0.0", 1, true, 2,
 *                                    transport, List.of(listener));
 * proxy.call("search", Map.of());
 *
 * listener.assertTransition("weather", "Thinking", "Completed");
 * }</pre>
 */
public final class StubActivityListener implements AgentActivityListener {

    private final CopyOnWriteArrayList<AgentActivity> activities = new CopyOnWriteArrayList<>();

    @Override
    public void onActivity(AgentActivity activity) {
        activities.add(activity);
    }

    /** All captured activities. */
    public List<AgentActivity> activities() {
        return List.copyOf(activities);
    }

    /** Activities for a specific agent. */
    public List<AgentActivity> activitiesFor(String agentName) {
        return activities.stream()
                .filter(a -> a.agentName().equals(agentName))
                .toList();
    }

    /** Assert that an agent went through exactly the expected activity types, in order. */
    public void assertTransition(String agentName, String... expectedTypes) {
        var agentActivities = activitiesFor(agentName);
        if (agentActivities.size() != expectedTypes.length) {
            fail("Expected " + expectedTypes.length + " transitions for '" + agentName
                    + "' but got " + agentActivities.size() + ": " + typeNames(agentActivities));
        }
        for (var i = 0; i < expectedTypes.length; i++) {
            var actual = agentActivities.get(i).getClass().getSimpleName();
            assertEquals(expectedTypes[i], actual,
                    "Transition " + i + " for '" + agentName + "': expected "
                            + expectedTypes[i] + " but was " + actual
                            + " (full sequence: " + typeNames(agentActivities) + ")");
        }
    }

    /** Clear all recorded activities. */
    public void clear() {
        activities.clear();
    }

    private static List<String> typeNames(List<AgentActivity> activities) {
        return activities.stream()
                .map(a -> a.getClass().getSimpleName())
                .toList();
    }
}
