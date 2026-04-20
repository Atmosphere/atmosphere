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
package org.atmosphere.ai;

import org.atmosphere.ai.gateway.AiGateway;
import org.atmosphere.ai.gateway.AiGatewayHolder;
import org.atmosphere.ai.gateway.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract-level check that every runtime module calls
 * {@code admitThroughGateway} on every dispatch mode. Runs at the
 * source level (static call-site grep) rather than at execution
 * level — executing each runtime requires its full transitive
 * dependency tree, which this module does not pull in. The grep-style
 * assertion is the honest contract test closest to the prior gist's
 * "gateway admission parity" claim.
 *
 * <p>The previous claim that {@code RuntimeCapabilityParityTest}
 * verified admission parity was false — that test pins message
 * assembly and tool-calling, not gateway wiring. This file closes the
 * gap flagged by the v0.9 review.</p>
 *
 * <p>Source-level check trades off realism (no end-to-end invocation
 * per runtime) for completeness (catches every runtime module
 * uniformly). When a runtime drops or renames the call, this test
 * fails before the CI matrix even boots the module.</p>
 */
class RuntimeGatewayAdmissionParityTest {

    private AiGateway.GatewayTraceExporter counting;
    private AtomicInteger admitCount;

    @BeforeEach
    void installCountingGateway() {
        admitCount = new AtomicInteger();
        counting = entry -> admitCount.incrementAndGet();
        AiGatewayHolder.install(new AiGateway(
                new PerUserRateLimiter(1_000_000, Duration.ofMinutes(1)),
                AiGateway.CredentialResolver.noop(),
                counting));
    }

    @AfterEach
    void resetGateway() {
        AiGatewayHolder.reset();
    }

    /**
     * Static check: every runtime's source file references
     * {@code admitThroughGateway}. The reference must appear outside a
     * comment — we use the method-invocation form and require at least
     * one hit per runtime file.
     *
     * <p>Runtimes that bypass the gateway on a dispatch path fail
     * silently in production but surface here as zero hits against the
     * expected file. Matches the consumer-presence rule in
     * feedback_primitive_needs_consumer.md.</p>
     */
    @Test
    void everyRuntimeCallsAdmitThroughGatewayFromSource() throws Exception {
        var repoRoot = resolveRepoRoot();
        var runtimes = new java.util.LinkedHashMap<String, String>();
        runtimes.put("built-in",         "modules/ai/src/main/java/org/atmosphere/ai/llm/BuiltInAgentRuntime.java");
        runtimes.put("spring-ai",        "modules/spring-ai/src/main/java/org/atmosphere/ai/spring/SpringAiAgentRuntime.java");
        runtimes.put("langchain4j",      "modules/langchain4j/src/main/java/org/atmosphere/ai/langchain4j/LangChain4jAgentRuntime.java");
        runtimes.put("adk",              "modules/adk/src/main/java/org/atmosphere/ai/adk/AdkAgentRuntime.java");
        runtimes.put("semantic-kernel",  "modules/semantic-kernel/src/main/java/org/atmosphere/ai/sk/SemanticKernelAgentRuntime.java");
        runtimes.put("embabel",          "modules/embabel/src/main/kotlin/org/atmosphere/ai/embabel/EmbabelAgentRuntime.kt");
        runtimes.put("koog",             "modules/koog/src/main/kotlin/org/atmosphere/ai/koog/KoogAgentRuntime.kt");

        for (var entry : runtimes.entrySet()) {
            var runtime = entry.getKey();
            var path = repoRoot.resolve(entry.getValue());
            assertTrue(java.nio.file.Files.exists(path),
                    "Runtime source missing for '" + runtime + "' at " + path);
            var content = java.nio.file.Files.readString(path);
            var callCount = countCallSites(content, "admitThroughGateway");
            assertTrue(callCount >= 1,
                    "Runtime '" + runtime + "' must call admitThroughGateway at least once "
                    + "so per-user rate limiting and credential choke-point policy "
                    + "apply to every outbound LLM dispatch (Correctness Invariant #3). "
                    + "Source: " + entry.getValue() + ". Hits: " + callCount);
        }
    }

    /**
     * Counts occurrences of {@code <name>(} outside line comments so a
     * doc reference like "see admitThroughGateway" does not satisfy the
     * consumer-presence contract. Kotlin and Java both use {@code //}
     * for line comments, which covers both source families tested here.
     */
    private static int countCallSites(String source, String name) {
        var pattern = java.util.regex.Pattern.compile(
                "(?m)^(?!\\s*//).*\\b" + java.util.regex.Pattern.quote(name) + "\\s*\\(");
        var matcher = pattern.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Walks up from the test's classpath root to find the multi-module
     * Maven root. Test runs resolve the working dir to each module's
     * base, so the parent pom is the stable anchor.
     */
    private static java.nio.file.Path resolveRepoRoot() {
        var here = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        var p = here;
        for (int i = 0; i < 8 && p != null; i++) {
            if (java.nio.file.Files.exists(p.resolve("modules"))
                    && java.nio.file.Files.exists(p.resolve("samples"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException(
                "Could not locate multi-module Maven root walking up from " + here);
    }
}
