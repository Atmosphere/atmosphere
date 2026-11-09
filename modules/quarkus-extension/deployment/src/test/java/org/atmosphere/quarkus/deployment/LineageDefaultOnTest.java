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
package org.atmosphere.quarkus.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

import org.atmosphere.ai.lineage.InMemoryLineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorderHolder;
import org.atmosphere.quarkus.runtime.AtmosphereLineageRecorderProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Lineage is on by default on Quarkus, as on Spring Boot (Mode Parity,
 * Correctness Invariant #7): with no {@code quarkus.atmosphere.ai.lineage.*}
 * config, a bounded in-memory recorder that keeps no prompt or tool-result
 * text is installed into {@link LineageRecorderHolder}.
 */
public class LineageDefaultOnTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(LineageDefaultOnTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    AtmosphereLineageRecorderProducer producer;

    @Test
    public void inMemoryRecorderWithoutContentIsInstalled() {
        var installed = LineageRecorderHolder.get();
        assertSame(producer.installedRecorder(), installed);
        var recorder = assertInstanceOf(InMemoryLineageRecorder.class, installed);
        assertFalse(recorder.captureContent(), "prompt and tool-result text are off by default");
        assertEquals(1000, recorder.capacity());
    }
}
