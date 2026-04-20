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
 * file in the repo MUST reach {@code admitThroughGateway(...)} on every
 * LLM-dispatching method, either directly in the body or through a
 * pinned delegation to another method that does.
 *
 * <p>Source-level check, not exec-level — the full transitive dep tree
 * of every runtime is not on this module's classpath. The BuiltIn
 * runtime's exec-path parity is covered by
 * {@link org.atmosphere.ai.llm.BuiltInExecuteWithHandleGatewayTest}.</p>
 */
class RuntimeGatewayAdmissionParityTest {

    /**
     * Contract for one runtime. {@code admitMethods} names the method
     * bodies that MUST call {@code admitThroughGateway(} directly.
     * {@code delegationMap} pins delegation shape: an entry
     * {@code A→B} asserts {@code A}'s body calls {@code B(} — this
     * catches the refactor where a runtime that previously delegated
     * to the handle path gets rewritten to dispatch directly from
     * {@code doExecute} without admitting. The invariant tested is not
     * "each runtime calls admit" but "every dispatch path reaches
     * admit", including indirectly via delegation.
     *
     * <p>A dead helper referencing the symbol at file scope no longer
     * passes the admit check (body-scanned), and the delegation assert
     * keeps the test honest when runtimes change shape.</p>
     */
    private record Runtime(String name, String path, String[] admitMethods,
                           String[][] delegationMap) { }

    @Test
    void everyRuntimeCallsAdmitOrDelegatesToAMethodThatDoes() throws Exception {
        var repoRoot = resolveRepoRoot();
        var runtimes = java.util.List.of(
                new Runtime("built-in",
                        "modules/ai/src/main/java/org/atmosphere/ai/llm/BuiltInAgentRuntime.java",
                        new String[] { "doExecute", "doExecuteWithHandle" },
                        new String[0][]),
                new Runtime("spring-ai",
                        "modules/spring-ai/src/main/java/org/atmosphere/ai/spring/SpringAiAgentRuntime.java",
                        new String[] { "doExecuteWithHandle" },
                        new String[][] { { "doExecute", "doExecuteWithHandle" } }),
                new Runtime("langchain4j",
                        "modules/langchain4j/src/main/java/org/atmosphere/ai/langchain4j/LangChain4jAgentRuntime.java",
                        new String[] { "doExecuteWithHandle" },
                        new String[][] { { "doExecute", "doExecuteWithHandle" } }),
                new Runtime("adk",
                        "modules/adk/src/main/java/org/atmosphere/ai/adk/AdkAgentRuntime.java",
                        new String[] { "doExecuteWithHandle" },
                        new String[][] { { "doExecute", "doExecuteWithHandle" } }),
                new Runtime("semantic-kernel",
                        "modules/semantic-kernel/src/main/java/org/atmosphere/ai/sk/SemanticKernelAgentRuntime.java",
                        new String[] { "doExecute" },
                        new String[0][]),
                new Runtime("embabel",
                        "modules/embabel/src/main/kotlin/org/atmosphere/ai/embabel/EmbabelAgentRuntime.kt",
                        new String[] { "execute" },
                        new String[0][]),
                new Runtime("koog",
                        "modules/koog/src/main/kotlin/org/atmosphere/ai/koog/KoogAgentRuntime.kt",
                        new String[] { "execute", "executeWithHandle" },
                        new String[0][]));

        for (var rt : runtimes) {
            var path = repoRoot.resolve(rt.path());
            assertTrue(java.nio.file.Files.exists(path),
                    "runtime source missing: " + path);
            var source = java.nio.file.Files.readString(path);
            for (var method : rt.admitMethods()) {
                assertAdmitInMethodBody(source, rt.name(), method);
            }
            for (var pair : rt.delegationMap()) {
                assertMethodBodyCalls(source, rt.name(), pair[0], pair[1]);
            }
        }
    }

    /**
     * Assert {@code callerMethod}'s body invokes {@code calleeMethod(}.
     * Keeps the delegation shape declarative: if someone refactors
     * {@code doExecute} to dispatch independently (and forgets to
     * admit), this assertion fails even when the handle-path admit is
     * still present.
     */
    private static void assertMethodBodyCalls(String source,
                                              String runtimeName,
                                              String callerMethod,
                                              String calleeMethod) {
        var body = extractMethodBody(source, callerMethod);
        assertTrue(body != null,
                runtimeName + " source does not declare method '"
                        + callerMethod + "' — delegation pin cannot proceed");
        assertTrue(body.contains(calleeMethod + "("),
                runtimeName + "::" + callerMethod + " must delegate to "
                        + calleeMethod + "(...) so gateway admission still fires. "
                        + "If a refactor dropped the delegation, admit needs to be "
                        + "restored in " + callerMethod + "'s own body.");
    }

    /**
     * Extract the body of {@code methodName(} and assert
     * {@code admitThroughGateway(} appears inside.
     */
    private static void assertAdmitInMethodBody(String source,
                                                String runtimeName,
                                                String methodName) {
        var body = extractMethodBody(source, methodName);
        assertTrue(body != null,
                runtimeName + " source does not declare a method body for '"
                        + methodName + "' — parity test cannot proceed");
        assertTrue(body.contains("admitThroughGateway("),
                runtimeName + "::" + methodName + " must invoke admitThroughGateway("
                        + ") from its body so rate limits and credential resolution "
                        + "apply uniformly across dispatch modes (Correctness Invariant "
                        + "#3). Body: " + abbrev(body));
    }

    /**
     * Slice out the brace-balanced body of the first non-abstract
     * declaration of {@code methodName}. Line-commented occurrences
     * and interface / abstract method signatures (terminated by
     * {@code ;} before the opening {@code {}) are skipped. Returns
     * {@code null} when no body is found.
     */
    private static String extractMethodBody(String source, String methodName) {
        int cursor = 0;
        int bodyStart = -1;
        while (cursor < source.length()) {
            var idx = source.indexOf(methodName + "(", cursor);
            if (idx < 0) break;
            var lineStart = source.lastIndexOf('\n', idx);
            var line = source.substring(lineStart + 1, idx);
            if (line.contains("//")) { cursor = idx + 1; continue; }
            var brace = source.indexOf('{', idx);
            var semicolon = source.indexOf(';', idx);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                cursor = idx + 1; continue;
            }
            bodyStart = brace;
            break;
        }
        if (bodyStart < 0) return null;
        int depth = 0, bodyEnd = -1;
        for (int i = bodyStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) { bodyEnd = i; break; }
            }
        }
        return bodyEnd > bodyStart ? source.substring(bodyStart, bodyEnd + 1) : null;
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
