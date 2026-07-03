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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins relaxed (kebab → camel) binding of
 * {@link AtmosphereProperties.HarnessProperties} and
 * {@link AtmosphereProperties.CodeProperties}, plus their default contract:
 * the harness switch is tri-state (unset by default — {@code null}, never
 * {@code false}), list defaults are empty-not-null, code exec is off by
 * default, and every code-exec hardening bound is a nullable wrapper meaning
 * "unset = don't bridge".
 */
class HarnessPropertiesBindingTest {

    private static AtmosphereProperties bind(Map<String, String> props) {
        var source = new MapConfigurationPropertySource(props);
        var binder = new Binder(source);
        return binder.bind("atmosphere", AtmosphereProperties.class)
                .orElseGet(AtmosphereProperties::new);
    }

    @Test
    void harnessBindsViaRelaxedKebabToCamel() {
        var props = new HashMap<String, String>();
        props.put("atmosphere.ai.harness.enabled", "true");
        // Comma form — what users type in application.properties; the bridge
        // re-joins the bound list with "," for the init-param.
        props.put("atmosphere.ai.harness.exclude-paths", "/atmosphere/ops,/atmosphere/health");
        props.put("atmosphere.ai.harness.compaction", "summarizing");
        props.put("atmosphere.ai.harness.prompt-cache-default", "conservative");

        var harness = bind(props).getAi().getHarness();

        assertThat(harness.getEnabled()).isTrue();
        assertThat(harness.getExcludePaths())
                .containsExactly("/atmosphere/ops", "/atmosphere/health");
        assertThat(harness.getCompaction()).isEqualTo("summarizing");
        assertThat(harness.getPromptCacheDefault()).isEqualTo("conservative");
    }

    @Test
    void harnessExplicitFalseBindsAsTheKillSwitch() {
        var props = new HashMap<String, String>();
        props.put("atmosphere.ai.harness.enabled", "false");

        var harness = bind(props).getAi().getHarness();

        assertThat(harness.getEnabled())
                .as("an explicit false must bind as FALSE, not collapse to unset")
                .isFalse();
    }

    @Test
    void harnessDefaultsAreUnsetAndEmpty() {
        var harness = new AtmosphereProperties().getAi().getHarness();

        assertThat(harness.getEnabled())
                .as("the switch is tri-state: unset by default, not false")
                .isNull();
        assertThat(harness.getExcludePaths()).isNotNull().isEmpty();
        assertThat(harness.getCompaction()).isNull();
        assertThat(harness.getPromptCacheDefault()).isNull();
    }

    @Test
    void codeBindsViaRelaxedKebabToCamel() {
        var props = new HashMap<String, String>();
        props.put("atmosphere.ai.code.enabled", "true");
        props.put("atmosphere.ai.code.engine", "docker");
        props.put("atmosphere.ai.code.image", "example/sandbox:1");
        props.put("atmosphere.ai.code.network", "none");
        props.put("atmosphere.ai.code.memory", "256m");
        props.put("atmosphere.ai.code.cpus", "0.5");
        props.put("atmosphere.ai.code.pids-limit", "64");
        props.put("atmosphere.ai.code.exec-timeout-seconds", "30");
        props.put("atmosphere.ai.code.sandbox-ttl-seconds", "120");
        props.put("atmosphere.ai.code.max-output-bytes", "1024");
        props.put("atmosphere.ai.code.setup", "pip install requests");

        var code = bind(props).getAi().getCode();

        assertThat(code.isEnabled()).isTrue();
        assertThat(code.getEngine()).isEqualTo("docker");
        assertThat(code.getImage()).isEqualTo("example/sandbox:1");
        assertThat(code.getNetwork()).isEqualTo("none");
        assertThat(code.getMemory()).isEqualTo("256m");
        assertThat(code.getCpus()).isEqualTo(0.5);
        assertThat(code.getPidsLimit()).isEqualTo(64);
        assertThat(code.getExecTimeoutSeconds()).isEqualTo(30L);
        assertThat(code.getSandboxTtlSeconds()).isEqualTo(120L);
        assertThat(code.getMaxOutputBytes()).isEqualTo(1024);
        assertThat(code.getSetup()).isEqualTo("pip install requests");
    }

    @Test
    void codeDefaultsAreDisabledAndUnset() {
        var code = new AtmosphereProperties().getAi().getCode();

        assertThat(code.isEnabled()).as("code exec is default-deny").isFalse();
        assertThat(code.getEngine()).isNull();
        assertThat(code.getImage()).isNull();
        assertThat(code.getNetwork()).isNull();
        assertThat(code.getMemory()).isNull();
        assertThat(code.getCpus()).isNull();
        assertThat(code.getPidsLimit()).isNull();
        assertThat(code.getExecTimeoutSeconds()).isNull();
        assertThat(code.getSandboxTtlSeconds()).isNull();
        assertThat(code.getMaxOutputBytes()).isNull();
        assertThat(code.getSetup()).isNull();
    }
}
