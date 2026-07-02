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
    private static final Pattern COORDINATOR = Pattern.compile("(?m)^@Coordinator\\b");
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
            var message = "The following @AiEndpoint / @Coordinator classes in samples/ "
                    + "are missing @AgentScope (or a properly-justified unrestricted "
                    + "opt-out). Multi-agent coordinators need scope enforcement at the "
                    + "@Prompt entry just like single endpoints — user input lands here "
                    + "and is dispatched to specialists. See "
                    + "docs/governance-policy-plane.md for the rationale.\n  "
                    + String.join("\n  ", offenders);
            throw new AssertionError(message);
        }
    }

    private static final Pattern AGENT = Pattern.compile("(?m)^@Agent\\b");
    private static final Pattern SKILL_FILE_ATTR =
            Pattern.compile("skillFile\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern AGENT_NAME_ATTR =
            Pattern.compile("(?s)@Agent\\s*\\([^)]*?name\\s*=\\s*\"([^\"]+)\"");

    private static void checkOne(Path repoRoot, Path source, List<String> offenders) {
        String text;
        try {
            text = Files.readString(source);
        } catch (IOException e) {
            return;
        }
        var isAiEndpoint = AI_ENDPOINT.matcher(text).find();
        var isCoordinator = COORDINATOR.matcher(text).find();
        var isAgent = AGENT.matcher(text).find();
        if (!isAiEndpoint && !isCoordinator && !isAgent) {
            return;
        }
        var hasScope = AGENT_SCOPE.matcher(text).find();
        if (hasScope && containsUnrestrictedTrue(text) && !UNRESTRICTED_OK.matcher(text).find()) {
            offenders.add(repoRoot.relativize(source).toString()
                    + " — @AgentScope(unrestricted = true) requires a non-blank justification");
            return;
        }
        if (hasScope) {
            return;
        }
        // @Agent classes may declare their boundary in the skill file instead —
        // a ## Guardrails section is the blog-native form the processor enforces;
        // @AgentScope is the honored fallback (AgentProcessor.resolveScopePolicy).
        if (isAgent && !isAiEndpoint && !isCoordinator) {
            if (skillDeclaresGuardrails(repoRoot, source, text)) {
                return;
            }
            offenders.add(repoRoot.relativize(source).toString()
                    + " — @Agent whose skill has no ## Guardrails section and no @AgentScope");
            return;
        }
        var kind = isAiEndpoint ? "@AiEndpoint" : "@Coordinator";
        offenders.add(repoRoot.relativize(source).toString()
                + " — " + kind + " without @AgentScope");
    }

    /**
     * Mirrors {@code AgentProcessor.parseSkillFile}'s resolution order (sample-local
     * resources first — Boot puts {@code BOOT-INF/classes} ahead of dependency jars —
     * then the bundled {@code atmosphere-skills} module) and reports whether the
     * first skill file found declares a {@code ## Guardrails} section.
     */
    private static boolean skillDeclaresGuardrails(Path repoRoot, Path source, String text) {
        // Walk up to the sample's module root. Compare against the actual
        // samples/ directory path — matching by the NAME "samples" stops at the
        // org/atmosphere/samples/... package directory instead.
        var samplesDir = repoRoot.resolve("samples");
        var sampleRoot = source;
        while (sampleRoot != null && sampleRoot.getParent() != null
                && !samplesDir.equals(sampleRoot.getParent())) {
            sampleRoot = sampleRoot.getParent();
        }
        if (sampleRoot == null || sampleRoot.getParent() == null) {
            return false;
        }
        var res = sampleRoot.resolve("src/main/resources");
        var skillRef = firstGroup(SKILL_FILE_ATTR, text);
        var agentName = firstGroup(AGENT_NAME_ATTR, text);
        var candidates = new ArrayList<Path>();
        if (skillRef != null && skillRef.startsWith("skill:")) {
            var n = skillRef.substring(6);
            candidates.add(res.resolve("META-INF/skills/" + n + "/SKILL.md"));
            candidates.add(res.resolve("prompts/" + n + "-skill.md"));
            candidates.add(res.resolve("prompts/" + n + ".md"));
            candidates.add(repoRoot.resolve(
                    "modules/skills/src/main/resources/META-INF/skills/" + n + "/SKILL.md"));
        } else if (skillRef != null) {
            candidates.add(res.resolve(skillRef));
        } else if (agentName != null) {
            candidates.add(res.resolve("META-INF/skills/" + agentName + "/SKILL.md"));
            candidates.add(res.resolve("prompts/" + agentName + ".md"));
            candidates.add(res.resolve("prompts/" + agentName + "-skill.md"));
            candidates.add(res.resolve("prompts/skill.md"));
        }
        for (var candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                return Files.readAllLines(candidate).stream()
                        .anyMatch(l -> l.strip().startsWith("## Guardrails"));
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private static String firstGroup(Pattern pattern, String text) {
        var m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
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
