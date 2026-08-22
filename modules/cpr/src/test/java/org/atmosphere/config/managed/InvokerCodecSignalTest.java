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
package org.atmosphere.config.managed;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (registre#40): two components registering an Encoder/Decoder
 * for the same type produced silent last-wins, order-dependent behaviour,
 * and decoder exceptions were swallowed at TRACE. Conflicts and the first
 * decode failure must surface at WARN.
 */
class InvokerCodecSignalTest {

    public static class UpperDecoder implements Decoder<String, String> {
        @Override public String decode(String s) { return s.toUpperCase(java.util.Locale.ROOT); }
    }

    public static class LowerDecoder implements Decoder<String, String> {
        @Override public String decode(String s) { return s.toLowerCase(java.util.Locale.ROOT); }
    }

    public static class ThrowingDecoder implements Decoder<String, String> {
        @Override public String decode(String s) { throw new IllegalStateException("bad payload"); }
    }

    public static class UpperEncoder implements Encoder<String, String> {
        @Override public String encode(String s) { return s.toUpperCase(java.util.Locale.ROOT); }
    }

    public static class LowerEncoder implements Encoder<String, String> {
        @Override public String encode(String s) { return s.toLowerCase(java.util.Locale.ROOT); }
    }

    private ListAppender<ILoggingEvent> appender;
    private Level savedLevel;

    @BeforeEach
    void setUp() {
        Invoker.resetWarningsForTests();
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Invoker.class);
        // logback-test.xml pins org.atmosphere at ERROR; the assertions
        // below are about WARN events, so open the gate for this class only.
        savedLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Invoker.class);
        logger.detachAppender(appender);
        logger.setLevel(savedLevel);
        Invoker.resetWarningsForTests();
    }

    private long warns(String needle) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains(needle))
                .count();
    }

    @Test
    void conflictingDecodersWarnOnceNamingBoth() {
        var result = Invoker.matchDecoder("MiXeD",
                List.of(new UpperDecoder(), new LowerDecoder()));

        assertEquals("mixed", result, "last-wins semantics stay unchanged");
        assertEquals(1, warns("Multiple Decoders match"),
                "an order-dependent registration conflict must be visible: "
                        + appender.list);
        assertTrue(appender.list.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("UpperDecoder")
                                && e.getFormattedMessage().contains("LowerDecoder")),
                "the warning must name both conflicting decoders");
    }

    @Test
    void conflictingEncodersWarnOnce() {
        var result = Invoker.matchEncoder("MiXeD",
                List.of(new UpperEncoder(), new LowerEncoder()));

        assertEquals("mixed", result);
        assertEquals(1, warns("Multiple Encoders match"), String.valueOf(appender.list));
    }

    @Test
    void firstDecoderFailureWarnsThenGoesQuiet() {
        Invoker.matchDecoder("payload-1", List.of(new ThrowingDecoder()));
        Invoker.matchDecoder("payload-2", List.of(new ThrowingDecoder()));

        assertEquals(1, warns("threw decoding"),
                "a throwing decoder is an application bug the developer must "
                + "see once, without hot-loop spam: " + appender.list);
    }
}
