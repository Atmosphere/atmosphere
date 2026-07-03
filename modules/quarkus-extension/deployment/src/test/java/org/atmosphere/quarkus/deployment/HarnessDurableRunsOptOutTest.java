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

import org.atmosphere.ai.resume.DurableRunSpineHolder;
import org.atmosphere.quarkus.runtime.AtmosphereDurableRunsProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the agent-harness preset's durable-runs opt-out: because a
 * {@code @WithDefault} mapping cannot distinguish an unset
 * {@code quarkus.atmosphere.durable-runs.enabled} from an explicit
 * {@code false}, the harness's opt-out is its own key —
 * {@code quarkus.atmosphere.ai.harness.durable-runs=false} must keep the
 * spine at the disabled default even with the harness explicitly on.
 */
public class HarnessDurableRunsOptOutTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(HarnessDurableRunsOptOutTest.class))
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.atmosphere.ai.harness.enabled", "true")
            .overrideConfigKey("quarkus.atmosphere.ai.harness.durable-runs", "false");

    @Inject
    AtmosphereDurableRunsProducer producer;

    @Test
    public void optOutKeyBlocksTheImpliedDurableRunSpine() {
        assertNotNull(producer, "AtmosphereDurableRunsProducer must be CDI-resolvable");
        assertFalse(producer.installed(),
                "ai.harness.durable-runs=false must block the harness's durable-runs implication");
        assertFalse(DurableRunSpineHolder.get().enabled(),
                "the spine must stay at the disabled default when the opt-out key is set");
    }
}
