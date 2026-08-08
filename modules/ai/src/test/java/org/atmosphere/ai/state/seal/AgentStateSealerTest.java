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
package org.atmosphere.ai.state.seal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link AgentStateSealer} key provisioning, sidecar handling, and
 * fail-closed terminal paths. The end-to-end behavior through
 * {@code FileSystemAgentState} is covered by
 * {@code FileSystemAgentStateSealingTest}.
 */
class AgentStateSealerTest {

    // Workspace roots live one level below the @TempDir so the sibling
    // {root}.seal directory stays inside the managed temp tree.

    @Test
    void firstBootGeneratesDurableOwnerOnlyKey(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var sealer = AgentStateSealer.forWorkspace(ws, null, false);

        var keyFile = tmp.resolve("ws.seal").resolve(AgentStateSealer.KEY_FILE_NAME);
        assertTrue(Files.isRegularFile(keyFile), "key must be persisted on first boot");
        assertTrue(sealer.keyId().startsWith("ed25519:"));

        try {
            var perms = Files.getPosixFilePermissions(keyFile);
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE), perms,
                    "generated key must be owner-only (0600)");
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "non-POSIX filesystem — permission assertion skipped");
        }
    }

    @Test
    void keySurvivesRestart(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var file = Files.writeString(ws.resolve("MEMORY.md"), "fact one\n");

        var beforeRestart = AgentStateSealer.forWorkspace(ws, null, false);
        beforeRestart.sealSaved(file, "fact one\n");

        // Simulated restart: a fresh sealer instance, same managed key file.
        var afterRestart = AgentStateSealer.forWorkspace(ws, null, false);
        assertEquals(beforeRestart.keyId(), afterRestart.keyId(),
                "the persisted key, not a per-process one, must be reloaded");
        assertDoesNotThrow(() -> afterRestart.verifyLoaded(file, "fact one\n"),
                "a seal written before the restart must verify after it");
    }

    @Test
    void operatorProvisionedKeyFileIsHonored(@TempDir Path tmp) throws IOException {
        var wsA = Files.createDirectories(tmp.resolve("a"));
        var wsB = Files.createDirectories(tmp.resolve("b"));
        // Generate a key via A's managed location, then hand it to B as an
        // operator-provisioned file.
        var sealerA = AgentStateSealer.forWorkspace(wsA, null, false);
        var keyFile = tmp.resolve("a.seal").resolve(AgentStateSealer.KEY_FILE_NAME);

        var sealerB = AgentStateSealer.forWorkspace(wsB, keyFile, false);
        assertEquals(sealerA.keyId(), sealerB.keyId());
        assertFalse(Files.exists(tmp.resolve("b.seal").resolve(AgentStateSealer.KEY_FILE_NAME)),
                "no second key may be generated when the operator supplied one");
    }

    @Test
    void missingOperatorKeyFailsLoudlyNotSilently(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var ex = assertThrows(AgentStateSealException.class,
                () -> AgentStateSealer.forWorkspace(ws, tmp.resolve("nope.key"), false));
        assertTrue(ex.getMessage().contains(AgentStateSealer.KEY_FILE_PROPERTY),
                "the error must name the key-file knob, got: " + ex.getMessage());
    }

    @Test
    void mismatchedKeyHalvesFailTheLoadProbe(@TempDir Path tmp) throws Exception {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var gen = KeyPairGenerator.getInstance("Ed25519");
        var pair1 = gen.generateKeyPair();
        var pair2 = gen.generateKeyPair();
        var keyFile = tmp.resolve("broken.key");
        Files.writeString(keyFile, "algorithm=Ed25519\n"
                + "privateKey=" + Base64.getEncoder().encodeToString(
                        pair1.getPrivate().getEncoded()) + "\n"
                + "publicKey=" + Base64.getEncoder().encodeToString(
                        pair2.getPublic().getEncoded()) + "\n",
                StandardCharsets.UTF_8);

        var ex = assertThrows(AgentStateSealException.class,
                () -> AgentStateSealer.forWorkspace(ws, keyFile, false));
        assertTrue(ex.getMessage().contains("does not match"),
                "a private/public mismatch must fail the probe, got: " + ex.getMessage());
    }

    @Test
    void corruptSidecarFailsClosedAndNamesTheResealStep(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var file = Files.writeString(ws.resolve("MEMORY.md"), "fact\n");
        var sealer = AgentStateSealer.forWorkspace(ws, null, false);
        sealer.sealSaved(file, "fact\n");

        var sidecar = tmp.resolve("ws.seal").resolve("seals").resolve("MEMORY.md.seal");
        assertTrue(Files.isRegularFile(sidecar));
        Files.writeString(sidecar, "scheme=Ed25519\n", StandardCharsets.UTF_8);

        var ex = assertThrows(AgentStateSealException.class,
                () -> sealer.verifyLoaded(file, "fact\n"));
        assertTrue(ex.getMessage().contains("AgentStateReseal"),
                "fail-closed refusal must name the remediation, got: " + ex.getMessage());
    }

    @Test
    void sidecarReplayedAcrossFilesFailsVerification(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var fileA = Files.writeString(ws.resolve("A.md"), "same content\n");
        var fileB = Files.writeString(ws.resolve("B.md"), "same content\n");
        var sealer = AgentStateSealer.forWorkspace(ws, null, false);
        sealer.sealSaved(fileA, "same content\n");

        var seals = tmp.resolve("ws.seal").resolve("seals");
        Files.copy(seals.resolve("A.md.seal"), seals.resolve("B.md.seal"));

        assertDoesNotThrow(() -> sealer.verifyLoaded(fileA, "same content\n"));
        assertThrows(AgentStateSealException.class,
                () -> sealer.verifyLoaded(fileB, "same content\n"),
                "the seal binds content to its relative path — a sidecar copied to "
                        + "another file must never verify, even for identical content");
    }

    @Test
    void unsealedLegacyFileAdoptsInDefaultModeButNotInStrict(@TempDir Path tmp)
            throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var legacy = Files.writeString(ws.resolve("MEMORY.md"), "pre-seal fact\n");

        var adopting = AgentStateSealer.forWorkspace(ws, null, false);
        assertDoesNotThrow(() -> adopting.verifyLoaded(legacy, "pre-seal fact\n"),
                "default mode adopts unsealed legacy files (WARN, then sealed on next save)");

        var strict = AgentStateSealer.forWorkspace(ws, null, true);
        var ex = assertThrows(AgentStateSealException.class,
                () -> strict.verifyLoaded(legacy, "pre-seal fact\n"));
        assertTrue(ex.getMessage().contains("strict"),
                "strict refusal must say why, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("AgentStateReseal"),
                "strict refusal must name the remediation, got: " + ex.getMessage());
    }

    @Test
    void resealAllSealsEverythingAndDropsOrphanSidecars(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        Files.createDirectories(ws.resolve("memory"));
        Files.writeString(ws.resolve("MEMORY.md"), "hand-edited\n");
        Files.writeString(ws.resolve("memory/2026-08-07.md"), "note\n");
        var sealer = AgentStateSealer.forWorkspace(ws, null, false);

        var ghost = Files.writeString(ws.resolve("ghost.md"), "bye\n");
        sealer.sealSaved(ghost, "bye\n");
        Files.delete(ghost);

        assertEquals(2, sealer.resealAll());
        assertTrue(Files.isRegularFile(
                tmp.resolve("ws.seal").resolve("seals").resolve("MEMORY.md.seal")));
        assertTrue(Files.isRegularFile(
                tmp.resolve("ws.seal").resolve("seals").resolve("memory/2026-08-07.md.seal")));
        assertFalse(Files.exists(
                        tmp.resolve("ws.seal").resolve("seals").resolve("ghost.md.seal")),
                "a sidecar whose state file is gone must be dropped by the reseal");
    }

    @Test
    void deletedStateFileLeavesNoStaleSidecar(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var file = Files.writeString(ws.resolve("MEMORY.md"), "fact\n");
        var sealer = AgentStateSealer.forWorkspace(ws, null, false);
        sealer.sealSaved(file, "fact\n");

        Files.delete(file);
        sealer.stateFileDeleted(file);
        assertFalse(Files.exists(tmp.resolve("ws.seal").resolve("seals")
                .resolve("MEMORY.md.seal")));
    }

    @Test
    void fromConfigurationIsOffByDefault(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        assertTrue(AgentStateSealer.fromConfiguration(ws).isEmpty(),
                "sealing must be opt-in — no properties set means no sealer");
        assertFalse(Files.exists(tmp.resolve("ws.seal")),
                "the default path must not leave sidecar directories behind");
    }

    @Test
    void fromConfigurationEnabledBuildsASealer(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        System.setProperty(AgentStateSealer.ENABLED_PROPERTY, "true");
        try {
            var sealer = AgentStateSealer.fromConfiguration(ws);
            assertTrue(sealer.isPresent());
            assertFalse(sealer.get().strict(), "strict must not be the default");
        } finally {
            System.clearProperty(AgentStateSealer.ENABLED_PROPERTY);
        }
    }

    @Test
    void rejectsStateFilesOutsideTheWorkspaceRoot(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var sealer = AgentStateSealer.forWorkspace(ws, null, false);
        // Relativizing an out-of-root path would put ".." segments into the
        // sidecar path and write seals outside the seals directory.
        var outside = tmp.resolve("outside.md");
        var escaping = ws.resolve("../outside.md");

        for (var path : new Path[] {outside, escaping}) {
            assertThrows(AgentStateSealException.class,
                    () -> sealer.sealSaved(path, "x"),
                    "sealing must refuse a state file outside the workspace root");
            assertThrows(AgentStateSealException.class,
                    () -> sealer.verifyLoaded(path, "x"),
                    "verification must refuse a state file outside the workspace root");
            assertThrows(AgentStateSealException.class,
                    () -> sealer.stateFileDeleted(path),
                    "sidecar cleanup must refuse a state file outside the workspace root");
        }
        assertFalse(Files.exists(tmp.resolve("outside.md.seal")),
                "no sidecar may be created outside the seals directory");
    }
}
