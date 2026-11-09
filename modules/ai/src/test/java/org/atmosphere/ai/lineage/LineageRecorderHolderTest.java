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
package org.atmosphere.ai.lineage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineageRecorderHolderTest {

    @AfterEach
    void reset() {
        LineageRecorderHolder.reset();
    }

    @Test
    void installIfAbsentNeverReplacesAnInstalledRecorder() {
        var applications = new InMemoryLineageRecorder();
        LineageRecorderHolder.install(applications);

        assertFalse(LineageRecorderHolder.installIfAbsent(new InMemoryLineageRecorder(10, false)));
        assertSame(applications, LineageRecorderHolder.get());
    }

    @Test
    void installIfAbsentInstallsOverNoop() {
        var integrations = new InMemoryLineageRecorder(10, false);

        assertTrue(LineageRecorderHolder.installIfAbsent(integrations));
        assertSame(integrations, LineageRecorderHolder.get());
    }

    @Test
    void resetIfOnlyRemovesTheCallersOwnRecorder() {
        var integrations = new InMemoryLineageRecorder(10, false);
        LineageRecorderHolder.installIfAbsent(integrations);
        var replacement = new InMemoryLineageRecorder();
        LineageRecorderHolder.install(replacement);

        assertFalse(LineageRecorderHolder.resetIf(integrations));
        assertSame(replacement, LineageRecorderHolder.get());

        assertTrue(LineageRecorderHolder.resetIf(replacement));
        assertSame(LineageRecorder.NOOP, LineageRecorderHolder.get());
    }
}
