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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#32): the durable→in-memory fallback logged at
 * TRACE, so an operator who provisioned durable storage got no signal
 * when durability turned itself off — a deployment could run
 * indefinitely believing runs survive restart when they do not. The
 * FIRST failure must WARN; repeats stay quiet.
 */
class DurableDegradationWarningTest {

    private ListAppender<ILoggingEvent> registryAppender;
    private ListAppender<ILoggingEvent> bufferAppender;

    @BeforeEach
    void setUp() {
        RunRegistry.resetDegradationWarningForTests();
        RunEventReplayBuffer.resetDegradationWarningForTests();
        registryAppender = attach(RunRegistry.class);
        bufferAppender = attach(RunEventReplayBuffer.class);
    }

    @AfterEach
    void tearDown() {
        detach(RunRegistry.class, registryAppender);
        detach(RunEventReplayBuffer.class, bufferAppender);
        RunRegistry.resetDegradationWarningForTests();
        RunEventReplayBuffer.resetDegradationWarningForTests();
    }

    private static ListAppender<ILoggingEvent> attach(Class<?> type) {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type))
                .detachAppender(appender);
    }

    /** A journal whose writes always fail — a down database, full disk, etc. */
    private static final class FailingJournal implements RunJournal {
        @Override public void recordRun(RunRecord run) {
            throw new IllegalStateException("database down");
        }
        @Override public void appendEvent(String runId, RunEvent event) {
            throw new IllegalStateException("database down");
        }
        @Override public void removeRun(String runId) { }
        @Override public java.util.List<RunRecord> loadAll() {
            return java.util.List.of();
        }
        @Override public java.util.List<RunEvent> loadEvents(String runId) {
            return java.util.List.of();
        }
        @Override public boolean durable() {
            return true;
        }
    }

    @Test
    void firstRegistrationFailureWarnsOnceThenGoesQuiet() {
        var registry = new RunRegistry(Clock.systemUTC(), Duration.ofMinutes(5),
                new FailingJournal());

        registry.register("agent", "alice", "s1", new ExecutionHandle.Settable(() -> { }));
        registry.register("agent", "alice", "s2", new ExecutionHandle.Settable(() -> { }));

        var warns = registryAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("IN-MEMORY"))
                .count();
        assertEquals(1, warns,
                "the first durable-degradation must WARN exactly once — silence "
                + "hides it and repetition spams the hot path: "
                + registryAppender.list);
    }

    @Test
    void firstMirrorFailureWarnsOnceThenGoesQuiet() {
        var buffer = new RunEventReplayBuffer();
        buffer.attachJournal(new FailingJournal(), "run-1");

        buffer.capture("streaming-text", "one");
        buffer.capture("streaming-text", "two");

        var warns = bufferAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("IN-MEMORY"))
                .count();
        assertEquals(1, warns, String.valueOf(bufferAppender.list));
        assertEquals(2, buffer.snapshot().size(),
                "the live buffer keeps working through journal failures");
        assertTrue(buffer.snapshot().get(0).payload().contains("one"));
    }
}
