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
package org.atmosphere.ai.governance;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Mode-parity invariant (CLAUDE.md §7): every policy source — YAML file /
 * classpath, Spring / Quarkus bean injection, ServiceLoader, or programmatic
 * construction — must yield identical admission semantics. This test spins
 * up the same policy shape from multiple sources and asserts the pipeline
 * decision is the same. Framework-level integration tests live in the Spring
 * starter and Quarkus extension modules; here we pin the contract so source
 * parity is guaranteed at the pipeline layer independent of bean-container
 * lifecycle.
 */
class PolicyPlaneSourceParityTest {

    private static final String YAML = """
            policies:
              - name: strict-pii
                type: pii-redaction
                version: "1.0"
                config:
                  mode: block
            """;

    private static final String OFFENDING_MESSAGE = "my email is test@example.com";

    @Test
    void yamlAndProgrammaticSourcesDenyIdentically() throws Exception {
        var fromYaml = new YamlPolicyParser().parse("yaml:parity-yaml",
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));

        var fromCode = List.<GovernancePolicy>of(
                new PolicyRegistry().build(new PolicyRegistry.PolicyDescriptor(
                        "strict-pii", "pii-redaction", "1.0",
                        "code:parity-test", Map.of("mode", "block"))));

        assertEquals(denyCount(fromYaml), denyCount(fromCode),
                "YAML vs programmatic construction must deny the same request");
    }

    @Test
    void serviceLoaderFactoryPinsSameDecisionAsYamlLoad() throws Exception {
        // Parser discovered via ServiceLoader (same path Quarkus / bare-JVM
        // take in production) must parse the identical document into
        // equivalent policies.
        PolicyParser parserFromLoader = null;
        for (var p : java.util.ServiceLoader.load(PolicyParser.class)) {
            if ("yaml".equals(p.format())) {
                parserFromLoader = p;
                break;
            }
        }
        if (parserFromLoader == null) {
            throw new AssertionError("YamlPolicyParser not discoverable via ServiceLoader");
        }

        var fromServiceLoader = parserFromLoader.parse("yaml:parity-spi",
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));
        var fromDirect = new YamlPolicyParser().parse("yaml:parity-direct",
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));

        assertEquals(denyCount(fromServiceLoader), denyCount(fromDirect),
                "ServiceLoader-discovered parser must yield identical decisions");
    }

    @Test
    void frameworkPropertyBridgeYieldsSameOutcomeAsDirectConstruction() throws Exception {
        // Direct construction: caller passes the policy list straight into
        // AiPipeline. Framework-property bridge: policies are simulated as
        // read from POLICIES_PROPERTY (see AiEndpointProcessor); in both
        // cases the pipeline must reject the same request.
        var fromYaml = new YamlPolicyParser().parse("yaml:test",
                new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));

        // Direct-construction pipeline
        var directExecuted = new AtomicBoolean(false);
        var directPipeline = new AiPipeline(new RecordingRuntime(directExecuted),
                null, null, null, null,
                List.of(), fromYaml, List.of(), null, null);
        directPipeline.execute("c1", OFFENDING_MESSAGE, new CollectingSession("direct"));

        // "Bridged" pipeline — same list, different reference path (emulates
        // framework-property flow into AiEndpointProcessor.instantiatePolicies).
        var bridged = List.copyOf(fromYaml);
        var bridgedExecuted = new AtomicBoolean(false);
        var bridgedPipeline = new AiPipeline(new RecordingRuntime(bridgedExecuted),
                null, null, null, null,
                List.of(), bridged, List.of(), null, null);
        bridgedPipeline.execute("c1", OFFENDING_MESSAGE, new CollectingSession("bridged"));

        assertEquals(directExecuted.get(), bridgedExecuted.get(),
                "direct construction and framework-property bridge must behave identically");
        assertFalse(directExecuted.get(), "sanity check — PII block policy must stop both paths");
    }

    private static int denyCount(List<GovernancePolicy> policies) {
        var executed = new AtomicBoolean(false);
        var pipeline = new AiPipeline(new RecordingRuntime(executed),
                null, null, null, null,
                List.of(), policies, List.of(), null, null);
        pipeline.execute("c1", OFFENDING_MESSAGE, new CollectingSession("count"));
        return executed.get() ? 0 : 1;
    }

    private record RecordingRuntime(AtomicBoolean executed) implements AgentRuntime {
        @Override public String name() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public int priority() { return 0; }
        @Override public void configure(AiConfig.LlmSettings settings) { }
        @Override public Set<AiCapability> capabilities() {
            return Set.of(AiCapability.TEXT_STREAMING);
        }
        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            executed.set(true);
            session.complete();
        }
    }
}
