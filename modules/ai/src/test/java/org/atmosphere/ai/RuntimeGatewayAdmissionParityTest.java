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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-runtime contract check — every {@code *AgentRuntime.{java,kt}}
 * file in the repo MUST invoke {@code admitThroughGateway(...)} from the
 * method bodies that dispatch an LLM call. Both entry points are
 * checked (plain execute + cancel-capable executeWithHandle), so a
 * runtime that bypasses the gateway on one mode but not the other
 * fails here.
 *
 * <p>Source-level check, not exec-level — the full transitive dep tree
 * of every runtime is not on this module's classpath. The BuiltIn
 * runtime's exec-path parity is covered by
 * {@link org.atmosphere.ai.llm.BuiltInExecuteWithHandleGatewayTest}.</p>
 *
 * <p>The prior version of this test included a vestigial counting
 * {@code AiGateway} installer that was never queried — dead scaffold
 * left over from a planned exec-level assertion. Removed in favour of
 * the per-method-body grep below, which catches the regression the
 * review flagged: a dead helper referencing {@code admitThroughGateway}
 * used to satisfy the "at least one hit" grep even when the real
 * dispatch methods bypassed the gateway.</p>
 */
class RuntimeGatewayAdmissionParityTest {

    /**
     * Each runtime owns admission at one of its two dispatch methods —
     * the other delegates. Three common shapes:
     * <ul>
     *   <li><b>BuiltIn, Koog</b>: admit in BOTH plain + handle paths.</li>
     *   <li><b>SpringAI, LangChain4j, ADK</b>: admit lives in the
     *       handle path; the plain {@code doExecute} delegates by calling
     *       {@code doExecuteWithHandle} itself.</li>
     *   <li><b>SemanticKernel, Embabel</b>: override only the plain
     *       path; the cancel-capable default on {@link AgentRuntime}
     *       calls {@code execute}, so admitting in {@code execute} is
     *       enough for parity.</li>
     * </ul>
     *
     * <p>The {@code admitMethods} array lists every method body that MUST
     * contain the call — a dead helper referencing the symbol at file
     * scope no longer passes the test.</p>
     */
    private record Runtime(String name, String path, String[] admitMethods) { }

    @Test
    void everyRuntimeCallsAdmitInTheDesignatedDispatchMethodBody() throws Exception {
        var repoRoot = resolveRepoRoot();
        var runtimes = java.util.List.of(
                new Runtime("built-in",
                        "modules/ai/src/main/java/org/atmosphere/ai/llm/BuiltInAgentRuntime.java",
                        new String[] { "doExecute", "doExecuteWithHandle" }),
                new Runtime("spring-ai",
                        "modules/spring-ai/src/main/java/org/atmosphere/ai/spring/SpringAiAgentRuntime.java",
                        new String[] { "doExecuteWithHandle" }),
                new Runtime("langchain4j",
                        "modules/langchain4j/src/main/java/org/atmosphere/ai/langchain4j/LangChain4jAgentRuntime.java",
                        new String[] { "doExecuteWithHandle" }),
                new Runtime("adk",
                        "modules/adk/src/main/java/org/atmosphere/ai/adk/AdkAgentRuntime.java",
                        new String[] { "doExecuteWithHandle" }),
                new Runtime("semantic-kernel",
                        "modules/semantic-kernel/src/main/java/org/atmosphere/ai/sk/SemanticKernelAgentRuntime.java",
                        new String[] { "doExecute" }),
                new Runtime("embabel",
                        "modules/embabel/src/main/kotlin/org/atmosphere/ai/embabel/EmbabelAgentRuntime.kt",
                        new String[] { "execute" }),
                new Runtime("koog",
                        "modules/koog/src/main/kotlin/org/atmosphere/ai/koog/KoogAgentRuntime.kt",
                        new String[] { "execute", "executeWithHandle" }));

        for (var rt : runtimes) {
            var path = repoRoot.resolve(rt.path());
            assertTrue(java.nio.file.Files.exists(path),
                    "runtime source missing: " + path);
            var source = java.nio.file.Files.readString(path);
            for (var method : rt.admitMethods()) {
                assertAdmitInMethodBody(source, rt.name(), method);
            }
        }
    }

    /**
     * Extract the body of {@code methodName(} and assert
     * {@code admitThroughGateway(} appears inside. Brace-counting over
     * the sliced region keeps us inside the target method even when
     * inner lambdas contain their own braces. Comment-stripping would
     * make this test more precise; for now, a helper that merely
     * references the symbol in a javadoc block does not match because
     * we require the {@code name(} form, not just {@code name}.
     */
    private static void assertAdmitInMethodBody(String source,
                                                String runtimeName,
                                                String methodName) {
        // Find "methodName(" declaration — skip any match inside a line comment.
        int cursor = 0;
        int bodyStart = -1;
        while (cursor < source.length()) {
            var idx = source.indexOf(methodName + "(", cursor);
            if (idx < 0) break;
            // Skip matches that sit on a line-commented line (// prefix before idx).
            var lineStart = source.lastIndexOf('\n', idx);
            var line = source.substring(lineStart + 1, idx);
            if (line.contains("//")) { cursor = idx + 1; continue; }
            // Walk forward to the opening brace that starts the body.
            var brace = source.indexOf('{', idx);
            var semicolon = source.indexOf(';', idx);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                cursor = idx + 1; continue; // abstract / interface method or call site
            }
            bodyStart = brace;
            break;
        }
        assertTrue(bodyStart >= 0,
                runtimeName + " source does not declare a method body for '"
                        + methodName + "' — parity test cannot proceed");

        // Brace-count through the body to find the closing brace.
        int depth = 0, bodyEnd = -1;
        for (int i = bodyStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { bodyEnd = i; break; }
            }
        }
        assertTrue(bodyEnd > bodyStart,
                runtimeName + "::" + methodName
                        + " — body never closed (unbalanced braces in test scan)");

        var body = source.substring(bodyStart, bodyEnd + 1);
        assertTrue(body.contains("admitThroughGateway("),
                runtimeName + "::" + methodName + " must invoke admitThroughGateway("
                        + ") from its body so rate limits and credential resolution "
                        + "apply uniformly across dispatch modes (Correctness Invariant "
                        + "#3). Body: " + abbrev(body));
    }

    private static String abbrev(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

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
