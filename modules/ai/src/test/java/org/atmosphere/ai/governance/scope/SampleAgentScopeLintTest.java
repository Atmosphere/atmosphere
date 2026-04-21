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
package org.atmosphere.ai.governance.scope;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level CI gate that refuses to build if a sample {@code @AiEndpoint}
 * is missing {@code @AgentScope}. Modeled on {@code RuntimeGatewayAdmissionParityTest}
 * — walks {@code samples/}, parses each Java file's class-level annotations,
 * and fails the build with a list of offenders.
 *
 * <h2>Why source-level, not annotation-processor</h2>
 * The lint needs to catch a missing annotation, not validate a present one.
 * Annotation processors only see annotations that exist; a missing
 * {@code @AgentScope} is invisible to them. Scanning source text is the only
 * reliable way to enforce "you MUST declare one."
 *
 * <h2>Opt-out</h2>
 * A class may declare {@code @AgentScope(unrestricted = true, justification = "...")}
 * to accept arbitrary input (LLM playgrounds, generic assistants). The lint
 * accepts the opt-out iff {@code justification} is a non-blank string
 * literal. Unjustified {@code unrestricted = true} fails the lint.
 */
class SampleAgentScopeLintTest {

    private static final Pattern AI_ENDPOINT = Pattern.compile("(?m)^@AiEndpoint\\b");
    private static final Pattern AGENT_SCOPE = Pattern.compile("(?m)^@AgentScope\\b");
    private static final Pattern UNRESTRICTED_OK = Pattern.compile(
            "(?s)@AgentScope\\s*\\([^)]*?unrestricted\\s*=\\s*true[^)]*?"
                    + "justification\\s*=\\s*\"[^\"]+\"");

    @Test
    void everySampleAiEndpointDeclaresAgentScope() throws IOException {
        var repoRoot = resolveRepoRoot();
        var samples = repoRoot.resolve("samples");
        assertTrue(Files.isDirectory(samples),
                "samples/ directory must exist at repo root: " + samples);

        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(samples)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(p -> checkOne(repoRoot, p, offenders));
        }

        if (!offenders.isEmpty()) {
            var message = "The following @AiEndpoint classes in samples/ are missing "
                    + "@AgentScope (or a properly-justified unrestricted opt-out). "
                    + "See docs/governance-policy-plane.md for the rationale.\n  "
                    + String.join("\n  ", offenders);
            throw new AssertionError(message);
        }
    }

    private static void checkOne(Path repoRoot, Path source, List<String> offenders) {
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException e) {
            return;
        }
        if (!AI_ENDPOINT.matcher(text).find()) {
            return;
        }
        var hasScope = AGENT_SCOPE.matcher(text).find();
        if (!hasScope) {
            offenders.add(repoRoot.relativize(source).toString() + " — add @AgentScope");
            return;
        }
        if (containsUnrestrictedTrue(text) && !UNRESTRICTED_OK.matcher(text).find()) {
            offenders.add(repoRoot.relativize(source).toString()
                    + " — @AgentScope(unrestricted = true) requires a non-blank justification");
        }
    }

    private static boolean containsUnrestrictedTrue(String text) {
        var idx = text.indexOf("@AgentScope");
        if (idx < 0) return false;
        var sub = text.substring(idx);
        return Pattern.compile("(?s)@AgentScope\\s*\\([^)]*?unrestricted\\s*=\\s*true").matcher(sub).find();
    }

    private static Path resolveRepoRoot() {
        var cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        var probe = cwd;
        while (probe != null && !Files.isDirectory(probe.resolve("samples"))) {
            probe = probe.getParent();
        }
        if (probe == null) {
            throw new IllegalStateException("could not locate repo root from cwd=" + cwd);
        }
        return probe;
    }
}
