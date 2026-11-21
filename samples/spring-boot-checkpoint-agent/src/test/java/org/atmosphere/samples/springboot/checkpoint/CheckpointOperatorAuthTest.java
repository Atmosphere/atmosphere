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
package org.atmosphere.samples.springboot.checkpoint;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code atmosphere.checkpoint.operator-tokens} parsing. An entry was split on
 * its first {@code =}, so a token holding one (base64 padding) was cut and its
 * remainder moved into the principal: the configured token never authenticated.
 */
class CheckpointOperatorAuthTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(CheckpointOperatorAuth.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void aTokenWithBase64PaddingKeepsItsPadding() {
        assertEquals(Map.of("c2VjcmV0==", "alice", "plain", "bob"),
                CheckpointOperatorAuth.parse("c2VjcmV0===alice, plain=bob"));

        var auth = new CheckpointOperatorAuth("c2VjcmV0===alice");
        assertEquals(Optional.of("alice"), auth.authenticate("c2VjcmV0==", null));
        assertEquals(Optional.empty(), auth.authenticate("c2VjcmV0", null));
    }

    @Test
    void aMalformedEntryIsSkippedWithAWarning() {
        var parsed = CheckpointOperatorAuth.parse("noequals, =nobody, token=, good=carol");

        assertEquals(Map.of("good", "carol"), parsed);
        var warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(3, warnings.size(), "one warning per malformed entry: " + warnings);
        assertTrue(warnings.stream().allMatch(w -> w.contains("malformed")), warnings::toString);
        assertFalse(warnings.stream().anyMatch(w -> w.contains("noequals")),
                "the entry, which may hold a secret, is not logged");
    }

    @Test
    void blankEntriesAreIgnoredQuietly() {
        assertEquals(Map.of("t", "dave"), CheckpointOperatorAuth.parse("t=dave, ,"));
        assertTrue(appender.list.isEmpty(), appender.list::toString);
    }
}
