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
package org.atmosphere.ai.code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.atmosphere.ai.code.SandboxCommand.Language;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the security-critical command construction, output
 * bounding, container-name sanitization, and runtime-gated factory resolution.
 * The live-container round-trip is exercised in the provisioned e2e lane, not
 * here, so these tests are deterministic on any host.
 */
class ContainerCodeSandboxTest {

    private static final CodeSandboxConfig ENABLED = new CodeSandboxConfig(
            true, "docker", "example/playwright:pinned", "none", "512m",
            1.0d, 256, Duration.ofSeconds(60), Duration.ofSeconds(300), 256 * 1024, "");

    @Test
    void runArgsCarryAllHardeningFlags() {
        var args = ContainerCommandBuilder.runArgs("docker", "atmo-sandbox-s1", ENABLED);
        assertContainsPair(args, "--network", "none");
        assertContainsPair(args, "--memory", "512m");
        assertContainsPair(args, "--cpus", "1");
        assertContainsPair(args, "--pids-limit", "256");
        assertContainsPair(args, "--cap-drop", "ALL");
        assertContainsPair(args, "--security-opt", "no-new-privileges");
        assertTrue(args.contains("--read-only"), "rootfs must be read-only");
        assertTrue(args.contains("--rm"), "container must auto-remove");
        assertTrue(args.stream().anyMatch(a -> a.startsWith(ContainerCommandBuilder.WORKSPACE + ":rw")),
                "writable workspace must be a bounded tmpfs");
        // image is the last-but-one arg, followed by the sleep TTL
        assertEquals("example/playwright:pinned", args.get(args.size() - 2));
        assertEquals("300", args.get(args.size() - 1));
    }

    @Test
    void execArgsRouteToInterpreterAndNeverCarryCodeAsArgument() {
        String code = "console.log('pwn'); $(rm -rf /)";
        var args = ContainerCommandBuilder.execArgs("docker", "atmo-sandbox-s1", Language.JAVASCRIPT);
        assertEquals(List.of("docker", "exec", "-i", "atmo-sandbox-s1", "node"), args);
        // Boundary safety: the model's code is piped to stdin, so it must never
        // appear anywhere in the argument vector.
        assertFalse(args.contains(code), "code must not be an argument");
        assertTrue(args.stream().noneMatch(a -> a.contains("rm -rf")),
                "no fragment of model code may leak into argv");
    }

    @Test
    void interpreterMappingCoversEveryLanguage() {
        assertEquals(List.of("bash"), ContainerCommandBuilder.interpreter(Language.BASH));
        assertEquals(List.of("node"), ContainerCommandBuilder.interpreter(Language.JAVASCRIPT));
        assertEquals(List.of("python3"), ContainerCommandBuilder.interpreter(Language.PYTHON));
    }

    @Test
    void containerNameSanitizesInjectionAttempts() {
        String hostile = "sess; docker rm -f $(docker ps -q) #";
        String name = ContainerCodeSandbox.containerName(hostile);
        assertTrue(name.startsWith("atmo-sandbox-"));
        assertTrue(name.matches("atmo-sandbox-[a-z0-9_.-]+"),
                "sanitized name must only contain safe characters: " + name);
        assertFalse(name.contains(" "), "no whitespace permitted in container name");
        assertEquals("atmo-sandbox-session", ContainerCodeSandbox.containerName("   "));
    }

    @Test
    void boundedOutputTruncatesAtCap() {
        var out = new BoundedOutput(5);
        out.append("abc");
        out.append("defgh");
        assertEquals("abcde", out.toString());
        assertTrue(out.truncated());
    }

    @Test
    void boundedOutputDoesNotTruncateUnderCap() {
        var out = new BoundedOutput(16);
        out.append("hello ");
        out.append("world");
        assertEquals("hello world", out.toString());
        assertFalse(out.truncated());
    }

    @Test
    void fromConfigGatesOnEnabledAndImage() {
        assertFalse(CodeSandboxFactory.fromConfig(CodeSandboxConfig.disabled()).isAvailable(),
                "disabled config => not available");

        var enabledNoImage = new CodeSandboxConfig(true, "docker", "", "none", "512m",
                1.0d, 256, Duration.ofSeconds(60), Duration.ofSeconds(300), 256 * 1024, "");
        assertFalse(CodeSandboxFactory.fromConfig(enabledNoImage).isAvailable(),
                "enabled but no image => not available (deterministic, engine-independent)");
        assertThrows(SandboxException.class,
                () -> CodeSandboxFactory.fromConfig(enabledNoImage).create("s1"));
    }

    @Test
    void parseArtifactsDecodesBase64TabLines() {
        String b64png = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        String b64txt = java.util.Base64.getEncoder().encodeToString("hi".getBytes());
        var artifacts = ContainerCodeSandbox.parseArtifacts(
                "shot.png\t" + b64png + "\nnote.txt\t" + b64txt + "\n");
        assertEquals(2, artifacts.size());
        assertEquals("shot.png", artifacts.get(0).name());
        assertEquals("image/png", artifacts.get(0).mimeType());
        assertEquals(3, artifacts.get(0).size());
        assertEquals("text/plain", artifacts.get(1).mimeType());
    }

    @Test
    void parseArtifactsSkipsMalformedLinesAndCapsCount() {
        var sb = new StringBuilder();
        sb.append("no-tab-here\n");                 // malformed: no tab
        sb.append("trailing-tab\t\n");              // malformed: empty payload
        String b64 = java.util.Base64.getEncoder().encodeToString(new byte[]{9});
        for (int i = 0; i < 20; i++) {
            sb.append("f").append(i).append(".png\t").append(b64).append('\n');
        }
        var artifacts = ContainerCodeSandbox.parseArtifacts(sb.toString());
        assertEquals(8, artifacts.size(), "collection is capped at MAX_ARTIFACTS");
        assertTrue(ContainerCodeSandbox.parseArtifacts("").isEmpty());
        assertTrue(ContainerCodeSandbox.parseArtifacts(null).isEmpty());
    }

    @Test
    void mimeForInfersFromExtension() {
        assertEquals("image/png", ContainerCodeSandbox.mimeFor("a.PNG"));
        assertEquals("image/jpeg", ContainerCodeSandbox.mimeFor("a.jpeg"));
        assertEquals("application/pdf", ContainerCodeSandbox.mimeFor("report.pdf"));
        assertEquals("application/octet-stream", ContainerCodeSandbox.mimeFor("blob.bin"));
    }

    private static void assertContainsPair(List<String> args, String flag, String value) {
        int idx = args.indexOf(flag);
        assertTrue(idx >= 0 && idx + 1 < args.size(), "missing flag " + flag);
        assertEquals(value, args.get(idx + 1), "wrong value for " + flag);
    }
}
