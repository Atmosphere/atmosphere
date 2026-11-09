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

import org.atmosphere.ai.lineage.LineageRecorder;
import org.atmosphere.ai.lineage.LineageRecorderHolder;
import org.atmosphere.quarkus.runtime.AtmosphereLineageRecorderProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@code quarkus.atmosphere.ai.lineage.enabled=false} installs nothing. */
public class LineageDisabledTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(LineageDisabledTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.atmosphere.ai.lineage.enabled", "false")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @Inject
    AtmosphereLineageRecorderProducer producer;

    @Test
    public void holderStaysAtNoop() {
        assertSame(LineageRecorder.NOOP, LineageRecorderHolder.get());
        assertNull(producer.installedRecorder());
    }
}
