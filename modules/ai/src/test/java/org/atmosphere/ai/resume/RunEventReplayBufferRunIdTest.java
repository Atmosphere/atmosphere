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
import org.atmosphere.ai.ExecutionHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The warnings of a run's replay buffer name the run they are about, whether
 * or not a journal is attached, and never a {@code null} run.
 */
class RunEventReplayBufferRunIdTest {

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

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void theWarningsOfARunWithNoJournalNameTheRun() {
        var registry = new RunRegistry(Clock.systemUTC(), Duration.ofMinutes(5), RunJournal.NOOP);
        var handle = registry.register("agent", "alice", "s1", new ExecutionHandle.Settable(() -> { }));

        for (var i = 0; i <= RunEventReplayBuffer.MAX_SUBSCRIBERS; i++) {
            handle.replayBuffer().subscribe(e -> { });
        }

        var warnings = warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(handle.runId()),
                "the warning must name the run it is about: " + warnings.get(0));
        assertFalse(warnings.get(0).contains("null"), warnings.get(0));
    }

    @Test
    void theWarningsOfABufferOfNoRunDoNotNameANullRun() {
        var buffer = new RunEventReplayBuffer();

        for (var i = 0; i <= RunEventReplayBuffer.MAX_SUBSCRIBERS; i++) {
            buffer.subscribe(e -> { });
        }
        buffer.capture("streaming-text", "a");

        var warnings = warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertFalse(warnings.get(0).contains("null"), warnings.get(0));
    }
}
