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
package org.atmosphere.ai.resume;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.cpr.AtmosphereConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The long-polling bounds of a run's replay buffer come from the init
 * parameters of the application the run belongs to, and its warnings name the
 * run they are about.
 */
class RunEventReplayBufferLimitsTest {

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RunEventReplayBuffer.class)).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RunEventReplayBuffer.class))
                .detachAppender(appender);
    }

    private static AtmosphereConfig config(Map<String, String> params) {
        var config = mock(AtmosphereConfig.class);
        when(config.getInitParameter(anyString())).thenAnswer(inv -> params.get(inv.<String>getArgument(0)));
        return config;
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void theReplayPositionsARunRemembersAreAnInitParameter() {
        // The literal key of the README table: renaming it must fail here until the README is updated.
        var buffer = RunEventReplayBuffer.forRun("run-positions",
                config(Map.of("org.atmosphere.ai.longPolling.maxReplayPositionsPerRun", "2")));
        buffer.capture("streaming-text", "a");
        buffer.claimNew("poll-0");
        buffer.claimNew("poll-1");
        buffer.claimNew("poll-2");

        assertEquals(2, buffer.maxReplayPositions());
        assertEquals(1, buffer.claimNew("poll-0").size(), "past 2 consumers the oldest one is forgotten");
        assertTrue(buffer.claimNew("poll-2").isEmpty());
    }

    @Test
    void thePollsWaitingOnARunAreAnInitParameter() {
        var buffer = RunEventReplayBuffer.forRun("run-polls",
                config(Map.of("org.atmosphere.ai.longPolling.maxWaitingPollsPerRun", "1")));

        assertTrue(buffer.awaitNext("poll-0", e -> { }).live());
        assertFalse(buffer.awaitNext("poll-1", e -> { }).live(), "one consumer's poll waits at most");
        assertTrue(buffer.awaitNext("poll-0", e -> { }).live(), "a newer poll of the same consumer takes over");
        assertTrue(warnings().stream().anyMatch(w -> w.contains("run-polls")), warnings().toString());
    }

    @Test
    void aBoundThatIsNotAPositiveIntegerIsTheDefaultWithAWarning() {
        var buffer = RunEventReplayBuffer.forRun("run-invalid", config(Map.of(
                RunEventReplayBuffer.MAX_REPLAY_POSITIONS_PARAM, "0",
                RunEventReplayBuffer.MAX_WAITING_POLLS_PARAM, "many")));

        assertEquals(RunEventReplayBuffer.MAX_REPLAY_POSITIONS, buffer.maxReplayPositions());
        assertEquals(RunEventReplayBuffer.MAX_WAITING_POLLS, buffer.maxWaitingPolls());
        assertTrue(warnings().stream().anyMatch(w -> w.contains(RunEventReplayBuffer.MAX_REPLAY_POSITIONS_PARAM)
                && w.contains("0")), warnings().toString());
        assertTrue(warnings().stream().anyMatch(w -> w.contains(RunEventReplayBuffer.MAX_WAITING_POLLS_PARAM)
                && w.contains("many")), warnings().toString());
    }

    @Test
    void withNoApplicationTheBoundsAreTheDefaults() {
        var buffer = RunEventReplayBuffer.forRun("run-defaults", null);

        assertEquals("run-defaults", buffer.runId());
        assertEquals(RunEventReplayBuffer.DEFAULT_CAPACITY, buffer.capacity());
        assertEquals(RunEventReplayBuffer.MAX_REPLAY_POSITIONS, buffer.maxReplayPositions());
        assertEquals(RunEventReplayBuffer.MAX_WAITING_POLLS, buffer.maxWaitingPolls());
    }
}
