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
package org.atmosphere.coordinator.processor;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.llm.FakeLlmClient;
import org.atmosphere.ai.preset.HarnessPreset;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code task} tool a coordinator's harness registers spawns its subagents
 * on the runtime of the coordinator's application, whichever thread the tool
 * is called on. Before, it resolved the runtime through the static
 * {@code AgentRuntimeResolver}, which on a thread bound to no application, with
 * two applications running, is the process default's.
 */
class CoordinatorSpawnSubagentEnvironmentTest {

    private final List<AiEnvironment> environments = new ArrayList<>();

    @AfterEach
    void tearDown() {
        environments.forEach(AiEnvironment::close);
        AiConfig.resetHostConfig();
    }

    /** One application whose model answers every prompt with {@code report}. */
    private AiEnvironment application(String report) {
        var environment = AiEnvironment.create(Map.of(AiConfig.RUNTIME_PROPERTY, "built-in")::get);
        environments.add(environment);
        environment.configure("fake", "model", null, null);
        environment.installClient(new FakeLlmClient("model", 0, report));
        return environment;
    }

    private static HarnessPreset preset() {
        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());
        when(framework.getAtmosphereConfig()).thenReturn(config);
        return HarnessPreset.install(framework);
    }

    @Test
    void theTaskToolSpawnsOnTheCoordinatorsApplicationRuntime() throws Exception {
        var a = application("report from application A");
        application("report from application B");
        var registry = new DefaultToolRegistry();

        new CoordinatorProcessor().registerPresetDelegation(registry, preset(), true, "coord",
                List.of(), a);

        var task = registry.getTool("task").orElseThrow();
        var report = task.executor().execute(Map.of("description", "Compare the venue options"));
        assertEquals("report from application A", report,
                "the subagent of application A's coordinator must run on A's runtime");
    }
}
