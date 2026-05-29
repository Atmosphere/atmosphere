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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.atmosphere.ai.code.SandboxCommand.Language;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the code-as-action SPI: default-deny gating,
 * hardened defaults, record value-semantics, and the disabled factory.
 */
class CodeSandboxSpiTest {

    @Test
    void disabledConfigIsDefaultDeny() {
        var config = CodeSandboxConfig.disabled();
        assertFalse(config.enabled(), "code execution must default to disabled");
        assertEquals("none", config.network(), "default network must be 'none'");
    }

    @Test
    void fromSystemPropertiesDefaultsToDisabledWithHardenedBounds() {
        // No org.atmosphere.ai.code.* properties set in the test JVM.
        var config = CodeSandboxConfig.fromSystemProperties();
        assertFalse(config.enabled(), "absent config must mean disabled (default deny)");
        assertEquals("auto", config.engine());
        assertEquals("none", config.network());
        assertEquals("512m", config.memory());
        assertEquals(1.0d, config.cpus());
        assertEquals(256, config.pidsLimit());
        assertEquals(Duration.ofSeconds(60), config.execTimeout());
        assertEquals(Duration.ofSeconds(300), config.sandboxTtl());
        assertEquals(256 * 1024, config.maxOutputBytes());
    }

    @Test
    void configCanonicalizesNonPositiveBoundsToDefaults() {
        var config = new CodeSandboxConfig(true, "", "img:latest", "  ",
                "", -2.0d, 0, Duration.ZERO, Duration.ofSeconds(-5), -1, "  npm i  ");
        assertEquals("npm i", config.setup(), "setup is trimmed");
        assertEquals("auto", config.engine(), "blank engine canonicalizes to auto");
        assertEquals("none", config.network(), "blank network canonicalizes to none");
        assertEquals("512m", config.memory(), "blank memory canonicalizes to default");
        assertEquals(1.0d, config.cpus(), "non-positive cpus canonicalizes to default");
        assertEquals(256, config.pidsLimit(), "non-positive pidsLimit canonicalizes to default");
        assertEquals(Duration.ofSeconds(60), config.execTimeout());
        assertEquals(Duration.ofSeconds(300), config.sandboxTtl());
        assertEquals(256 * 1024, config.maxOutputBytes());
        assertEquals("img:latest", config.image());
    }

    @Test
    void disabledFactoryNeverAvailableAndRefusesCreation() {
        var factory = CodeSandboxFactory.disabled();
        assertFalse(factory.isAvailable());
        var ex = assertThrows(SandboxException.class, () -> factory.create("session-1"));
        assertTrue(ex.getMessage().contains(CodeSandboxConfig.ENABLED),
                "refusal message should point operators at the enable flag");
    }

    @Test
    void sandboxCommandRejectsBlankCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxCommand(Language.BASH, "  ", null));
        assertThrows(IllegalArgumentException.class,
                () -> SandboxCommand.of(null, "echo hi"));
        var cmd = SandboxCommand.of(Language.JAVASCRIPT, "console.log('hi')");
        assertEquals(Language.JAVASCRIPT, cmd.language());
        assertEquals(null, cmd.timeout(), "of() leaves timeout unset for the sandbox default");
    }

    @Test
    void sandboxArtifactDefensivelyCopiesAndHasValueSemantics() {
        byte[] bytes = {1, 2, 3};
        var artifact = new SandboxArtifact("shot.png", "image/png", bytes);
        bytes[0] = 99;
        assertEquals(1, artifact.data()[0], "constructor must copy the input array");
        assertNotSame(artifact.data(), artifact.data(), "accessor must copy on read");
        assertEquals(3, artifact.size());

        var same = new SandboxArtifact("shot.png", "image/png", new byte[]{1, 2, 3});
        assertEquals(artifact, same, "equal bytes + metadata must compare equal");
        assertEquals(artifact.hashCode(), same.hashCode());
    }

    @Test
    void sandboxResultNormalizesNullsAndReportsOk() {
        var ok = new SandboxResult(0, null, null, false, false, null, null);
        assertEquals("", ok.stdout());
        assertEquals("", ok.stderr());
        assertEquals(Duration.ZERO, ok.duration());
        assertTrue(ok.artifacts().isEmpty());
        assertTrue(ok.ok());

        var failed = new SandboxResult(1, "out", "boom", true, false,
                Duration.ofMillis(5), List.of());
        assertFalse(failed.ok());
        assertTrue(failed.truncated());
    }
}
