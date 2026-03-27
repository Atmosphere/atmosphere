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

import org.atmosphere.coordinator.fleet.AgentResult;

import java.time.Duration;

/**
 * Fluent assertions for {@link AgentResult} in coordinator tests.
 *
 * <pre>{@code
 * CoordinatorAssertions.assertThat(result)
 *     .succeeded()
 *     .containsText("weather")
 *     .fromAgent("weather-agent")
 *     .completedWithin(Duration.ofSeconds(5));
 * }</pre>
 */
public final class CoordinatorAssertions {

    private final AgentResult result;

    private CoordinatorAssertions(AgentResult result) {
        if (result == null) {
            throw new AssertionError("AgentResult is null");
        }
        this.result = result;
    }

    public static CoordinatorAssertions assertThat(AgentResult result) {
        return new CoordinatorAssertions(result);
    }

    /** Assert the result was successful. */
    public CoordinatorAssertions succeeded() {
        if (!result.success()) {
            throw new AssertionError(
                    "Expected success but got failure: " + result.text());
        }
        return this;
    }

    /** Assert the result was a failure. */
    public CoordinatorAssertions failed() {
        if (result.success()) {
            throw new AssertionError(
                    "Expected failure but got success: " + result.text());
        }
        return this;
    }

    /** Assert the result text contains the expected substring. */
    public CoordinatorAssertions containsText(String expected) {
        if (result.text() == null || !result.text().contains(expected)) {
            throw new AssertionError(
                    "Expected text to contain '" + expected + "' but was: " + result.text());
        }
        return this;
    }

    /** Assert the result completed within the given duration. */
    public CoordinatorAssertions completedWithin(Duration maxDuration) {
        if (result.duration().compareTo(maxDuration) > 0) {
            throw new AssertionError(
                    "Expected completion within " + maxDuration
                            + " but took " + result.duration());
        }
        return this;
    }

    /** Assert the result came from the expected agent. */
    public CoordinatorAssertions fromAgent(String expectedAgent) {
        if (!expectedAgent.equals(result.agentName())) {
            throw new AssertionError(
                    "Expected agent '" + expectedAgent
                            + "' but was '" + result.agentName() + "'");
        }
        return this;
    }

    /** Return the underlying result for further inspection. */
    public AgentResult result() {
        return result;
    }
}
