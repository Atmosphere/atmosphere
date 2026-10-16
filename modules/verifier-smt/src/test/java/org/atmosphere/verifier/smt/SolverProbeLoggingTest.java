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
package org.atmosphere.verifier.smt;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.sosy_lab.java_smt.SolverContextFactory.Solvers;
import org.sosy_lab.java_smt.api.SolverContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A solver that is not installed is an expected state, not a failure: the availability
 * probe reports it on one DEBUG line and keeps the stack trace for TRACE. The 2026-10-16
 * sweep found a full {@code NullPointerException} stack in the boot log of a sample that
 * runs the verifier at DEBUG, thrown by java-smt's native-library lookup for Z3, which
 * the sample does not ship.
 */
class SolverProbeLoggingTest {

    /** Fails the way java-smt's Z3 loader failed in the sweep's packaged sample. */
    private static final class MissingNativeSolver extends AbstractJavaSmtChecker {
        @Override
        public String name() {
            return "missing";
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        protected Solvers solver() {
            return Solvers.Z3;
        }

        @Override
        SolverContext newContext() {
            throw new NullPointerException(
                    "Cannot invoke \"java.nio.file.Path.getParent()\" because \"path\" is null");
        }
    }

    private final Logger probeLogger = (Logger) LoggerFactory.getLogger(AbstractJavaSmtChecker.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureProbeLog() {
        previousLevel = probeLogger.getLevel();
        probeLogger.setLevel(Level.TRACE);
        appender.start();
        probeLogger.addAppender(appender);
    }

    @AfterEach
    void releaseProbeLog() {
        probeLogger.detachAppender(appender);
        appender.stop();
        probeLogger.setLevel(previousLevel);
    }

    @Test
    void anUnloadableSolverIsOneDebugLineWithTheStackOnlyAtTrace() {
        assertFalse(new MissingNativeSolver().isAvailable(), "a solver that cannot load is unavailable");

        List<ILoggingEvent> debug = appender.list.stream().filter(e -> e.getLevel() == Level.DEBUG).toList();
        assertEquals(1, debug.size(), "expected one DEBUG line for the unavailable solver, got " + debug);
        var line = debug.get(0);
        assertNull(line.getThrowableProxy(),
                "the DEBUG line carries a stack trace: " + line.getFormattedMessage());
        assertTrue(line.getFormattedMessage().contains("Z3"), line.getFormattedMessage());
        assertTrue(line.getFormattedMessage().contains("NullPointerException"),
                "the DEBUG line should still name the cause: " + line.getFormattedMessage());

        var trace = appender.list.stream().filter(e -> e.getLevel() == Level.TRACE).findFirst();
        assertTrue(trace.isPresent(), "the stack trace must still be available at TRACE");
        assertNotNull(trace.get().getThrowableProxy(), "the TRACE line must carry the stack trace");
    }
}
