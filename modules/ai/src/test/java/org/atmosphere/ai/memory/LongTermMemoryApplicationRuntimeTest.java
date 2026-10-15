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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The long-term memory a harness preset attaches to an endpoint extracts facts
 * with its own application's runtime while another application runs in the
 * JVM, not with the runtime the JVM-wide static API resolves then.
 */
class LongTermMemoryApplicationRuntimeTest {

    private final List<AiEnvironment> environments = new ArrayList<>();

    @AfterEach
    void tearDown() {
        environments.forEach(AiEnvironment::close);
        AgentRuntimeResolver.reset();
    }

    @Test
    void thePresetMemoryExtractsWithItsApplicationsRuntime() throws Exception {
        var first = framework("model-first");
        var second = framework("model-second");

        var interceptors = LongTermMemories.withPresetLongTermMemory(
                List.of(), HarnessPreset.install(second), true, "/atmosphere/agent/memory", second);
        var memory = (LongTermMemoryInterceptor) interceptors.getFirst();
        var field = LongTermMemoryInterceptor.class.getDeclaredField("extractionRuntime");
        field.setAccessible(true);

        assertSame(AiEnvironment.of(second.getAtmosphereConfig()).runtimes().resolve(), field.get(memory),
                "the memory must extract with its own application's runtime instance");
        assertSame(AiEnvironment.of(first.getAtmosphereConfig()), AiEnvironment.find(first.getAtmosphereConfig())
                .orElseThrow(), "the other application keeps its own environment");
    }

    private AtmosphereFramework framework(String model) {
        var framework = new AtmosphereFramework();
        var environment = AiEnvironment.create(framework.getAtmosphereConfig(), key -> null);
        environments.add(environment);
        environment.configure("remote", model, "k", "http://127.0.0.1:9/v1");
        return framework;
    }
}
