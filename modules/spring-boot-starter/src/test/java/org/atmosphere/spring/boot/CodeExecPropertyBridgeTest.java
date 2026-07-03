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
package org.atmosphere.spring.boot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.atmosphere.ai.code.CodeSandboxConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code atmosphere.ai.code.*} → {@code org.atmosphere.ai.code.*}
 * system-property bridge: set Spring properties are bridged, a system property
 * the operator already set on the JVM is never overridden (JVM wins), unset
 * properties bridge nothing (default-deny survives), and on context shutdown
 * only the bridge-owned keys are cleared (Ownership, Invariant #1).
 */
class CodeExecPropertyBridgeTest {

    private static final List<String> CODE_KEYS = List.of(
            CodeSandboxConfig.ENABLED,
            CodeSandboxConfig.ENGINE,
            CodeSandboxConfig.IMAGE,
            CodeSandboxConfig.NETWORK,
            CodeSandboxConfig.MEMORY,
            CodeSandboxConfig.CPUS,
            CodeSandboxConfig.PIDS_LIMIT,
            CodeSandboxConfig.EXEC_TIMEOUT_SECONDS,
            CodeSandboxConfig.SANDBOX_TTL_SECONDS,
            CodeSandboxConfig.MAX_OUTPUT_BYTES,
            CodeSandboxConfig.SETUP);

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withBean(AtmosphereFramework.class, AtmosphereFramework::new)
            .withConfiguration(AutoConfigurations.of(AtmosphereAiAutoConfiguration.class));

    private final Map<String, String> savedSystemProperties = new HashMap<>();

    @BeforeEach
    void snapshotAndClearSystemProperties() {
        savedSystemProperties.clear();
        for (var key : CODE_KEYS) {
            savedSystemProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreSystemProperties() {
        for (var key : CODE_KEYS) {
            var saved = savedSystemProperties.get(key);
            if (saved == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, saved);
            }
        }
    }

    @Test
    void bridgesEverySetPropertyAndClearsThemOnShutdown() {
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.code.enabled=true",
                        "atmosphere.ai.code.engine=docker",
                        "atmosphere.ai.code.image=example/sandbox:1",
                        "atmosphere.ai.code.network=none",
                        "atmosphere.ai.code.memory=256m",
                        "atmosphere.ai.code.cpus=0.5",
                        "atmosphere.ai.code.pids-limit=64",
                        "atmosphere.ai.code.exec-timeout-seconds=30",
                        "atmosphere.ai.code.sandbox-ttl-seconds=120",
                        "atmosphere.ai.code.max-output-bytes=1024",
                        "atmosphere.ai.code.setup=pip install requests")
                .run(context -> {
                    assertThat(System.getProperty(CodeSandboxConfig.ENABLED)).isEqualTo("true");
                    assertThat(System.getProperty(CodeSandboxConfig.ENGINE)).isEqualTo("docker");
                    assertThat(System.getProperty(CodeSandboxConfig.IMAGE))
                            .isEqualTo("example/sandbox:1");
                    assertThat(System.getProperty(CodeSandboxConfig.NETWORK)).isEqualTo("none");
                    assertThat(System.getProperty(CodeSandboxConfig.MEMORY)).isEqualTo("256m");
                    assertThat(System.getProperty(CodeSandboxConfig.CPUS)).isEqualTo("0.5");
                    assertThat(System.getProperty(CodeSandboxConfig.PIDS_LIMIT)).isEqualTo("64");
                    assertThat(System.getProperty(CodeSandboxConfig.EXEC_TIMEOUT_SECONDS))
                            .isEqualTo("30");
                    assertThat(System.getProperty(CodeSandboxConfig.SANDBOX_TTL_SECONDS))
                            .isEqualTo("120");
                    assertThat(System.getProperty(CodeSandboxConfig.MAX_OUTPUT_BYTES))
                            .isEqualTo("1024");
                    assertThat(System.getProperty(CodeSandboxConfig.SETUP))
                            .isEqualTo("pip install requests");
                    var bridge = context.getBean(
                            AtmosphereAiAutoConfiguration.CodeExecPropertyBridge.class);
                    assertThat(bridge.ownedKeys()).containsExactlyInAnyOrderElementsOf(CODE_KEYS);
                    assertThat(bridge.skippedKeys()).isEmpty();
                });
        // The context is closed when run() returns — the bridge must have
        // cleared every key it set (terminal-path completeness).
        for (var key : CODE_KEYS) {
            assertThat(System.getProperty(key))
                    .as("bridge-owned key %s is cleared on shutdown", key)
                    .isNull();
        }
    }

    @Test
    void operatorSetJvmSystemPropertyWinsAndSurvivesShutdown() {
        System.setProperty(CodeSandboxConfig.IMAGE, "operator/image:9");
        contextRunner
                .withPropertyValues(
                        "atmosphere.ai.code.image=yaml/image:1",
                        "atmosphere.ai.code.network=bridge")
                .run(context -> {
                    assertThat(System.getProperty(CodeSandboxConfig.IMAGE))
                            .as("a JVM-set system property is never overridden")
                            .isEqualTo("operator/image:9");
                    assertThat(System.getProperty(CodeSandboxConfig.NETWORK)).isEqualTo("bridge");
                    var bridge = context.getBean(
                            AtmosphereAiAutoConfiguration.CodeExecPropertyBridge.class);
                    assertThat(bridge.skippedKeys()).containsExactly(CodeSandboxConfig.IMAGE);
                    assertThat(bridge.ownedKeys()).containsExactly(CodeSandboxConfig.NETWORK);
                });
        assertThat(System.getProperty(CodeSandboxConfig.IMAGE))
                .as("shutdown clears only bridge-owned keys, never operator-set ones")
                .isEqualTo("operator/image:9");
        assertThat(System.getProperty(CodeSandboxConfig.NETWORK)).isNull();
    }

    @Test
    void unsetPropertiesBridgeNothing() {
        contextRunner.run(context -> {
            var bridge = context.getBean(
                    AtmosphereAiAutoConfiguration.CodeExecPropertyBridge.class);
            assertThat(bridge.ownedKeys()).isEmpty();
            assertThat(bridge.skippedKeys()).isEmpty();
            for (var key : CODE_KEYS) {
                assertThat(System.getProperty(key)).isNull();
            }
        });
    }

    @Test
    void explicitFalseEnabledBridgesNothing() {
        // enabled=false equals the sysprop-layer default and could never beat a
        // JVM-set value anyway — the bridge writes nothing (default-deny stays).
        contextRunner
                .withPropertyValues("atmosphere.ai.code.enabled=false")
                .run(context -> {
                    var bridge = context.getBean(
                            AtmosphereAiAutoConfiguration.CodeExecPropertyBridge.class);
                    assertThat(bridge.ownedKeys()).isEmpty();
                    assertThat(System.getProperty(CodeSandboxConfig.ENABLED)).isNull();
                });
    }
}
