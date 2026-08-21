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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Opt-in tamper-evidence for the file-backed agent state: seals every file
 * {@link org.atmosphere.ai.state.FileSystemAgentState} writes with
 * {@link AgentStateIntegrity} (Ed25519) and verifies every file it reads,
 * failing closed on mismatch. This is the production consumer of the
 * {@code AgentStateIntegrity} primitive.
 *
 * <h2>Posture — same shape as the opt-in checkpoint cipher</h2>
 * <ul>
 *   <li><b>OFF by default.</b> The state files stay plain, hand-editable
 *       Markdown/JSONL; with sealing disabled, behavior is exactly as
 *       before this class existed.</li>
 *   <li><b>Opt-in:</b> {@code -Datmosphere.ai.state.seal.enabled=true}
 *       (or {@code ATMOSPHERE_AI_STATE_SEAL_ENABLED=true}).</li>
 *   <li><b>Durable key:</b> operator-provisioned via
 *       {@code -Datmosphere.ai.state.seal.key-file=<path>} /
 *       {@code ATMOSPHERE_AI_STATE_SEAL_KEY_FILE}, or generated on first
 *       boot and persisted with owner-only permissions. A per-process
 *       ephemeral key is never used — old seals must verify after a
 *       restart.</li>
 *   <li><b>Fail-closed:</b> a file that fails verification refuses to load
 *       with an {@link AgentStateSealException} naming the remediation.</li>
 *   <li><b>Legacy adoption:</b> a file with no seal loads with a one-time
 *       WARN and is sealed on its next save — enabling sealing never bricks
 *       an existing workspace. The strict sub-mode
 *       ({@code -Datmosphere.ai.state.seal.strict=true}, non-default)
 *       refuses unsealed files too; without strict, an adversary who can
 *       delete the sidecar seal downgrades a file back to "legacy", so
 *       strict is the enforcing posture against seal-aware tampering.</li>
 *   <li><b>Reseal step:</b> deliberate hand-edits are blessed by
 *       {@link AgentStateReseal} (CLI) or a one-shot restart with
 *       {@code -Datmosphere.ai.state.seal.reseal=true}.</li>
 * </ul>
 *
 * <h2>On-disk layout</h2>
 *
 * Seals and the generated key live in a sibling directory of the workspace
 * root — <i>next to</i>, not inside, the state tree — so they never appear in
 * the hand-editable workspace and are out of reach of the workspace-scoped
 * virtual filesystem the agent itself can write through:
 *
 * <pre>
 * {workspaceRoot}/                     ← sealed state tree
 * {workspaceRoot}.seal/
 *   state-seal.key                     ← Ed25519 keypair (0600), unless the
 *                                        operator supplied a key file
 *   seals/{relative/path}.seal         ← one sidecar per sealed state file
 * </pre>
 *
 * <p>The seal binds content to its workspace-relative path (the
 * {@code AgentStateIntegrity} slot key), so a sidecar copied from one file
 * to another never verifies.</p>
 *
 * <p>Scope: the files read and written through {@code FileSystemAgentState}
 * — conversation transcripts, {@code MEMORY.md} facts, daily notes, and the
 * four rule files. Sealing detects modification, not deletion: a state file
 * removed from disk reads as cleared state, exactly as before.</p>
 */
public final class AgentStateSealer {

    /** System property: master opt-in switch. Default {@code false}. */
    public static final String ENABLED_PROPERTY = "atmosphere.ai.state.seal.enabled";
    /** Environment fallback for {@link #ENABLED_PROPERTY}. */
    public static final String ENABLED_ENV = "ATMOSPHERE_AI_STATE_SEAL_ENABLED";
    /** System property: operator-provisioned key file path. */
    public static final String KEY_FILE_PROPERTY = "atmosphere.ai.state.seal.key-file";
    /** Environment fallback for {@link #KEY_FILE_PROPERTY}. */
    public static final String KEY_FILE_ENV = "ATMOSPHERE_AI_STATE_SEAL_KEY_FILE";
    /** System property: refuse unsealed legacy files. Default {@code false}. */
    public static final String STRICT_PROPERTY = "atmosphere.ai.state.seal.strict";
    /** Environment fallback for {@link #STRICT_PROPERTY}. */
    public static final String STRICT_ENV = "ATMOSPHERE_AI_STATE_SEAL_STRICT";
    /**
     * System property (deliberately no environment fallback — an env var
     * lingers across restarts and would bless every future modification):
     * reseal the whole workspace on the next start, then remove the flag.
     */
    public static final String RESEAL_PROPERTY = "atmosphere.ai.state.seal.reseal";

    /** File name of the generated key inside the sidecar directory. */
    public static final String KEY_FILE_NAME = "state-seal.key";

    private static final Logger logger = LoggerFactory.getLogger(AgentStateSealer.class);
    private static final String SEAL_DIR_SUFFIX = ".seal";
    private static final String SEALS_SUBDIR = "seals";
    private static final String SEAL_FILE_SUFFIX = ".seal";
    private static final String PROBE = "atmosphere-state-seal-probe";

    private final Path workspaceRoot;
    private final Path sealDir;
    private final AgentStateIntegrity integrity;
    private final boolean strict;
    private final Set<Path> warnedUnsealed = ConcurrentHashMap.newKeySet();

    private AgentStateSealer(Path workspaceRoot, Path sealDir,
                             AgentStateIntegrity integrity, boolean strict) {
        this.workspaceRoot = workspaceRoot;
        this.sealDir = sealDir;
        this.integrity = integrity;
        this.strict = strict;
    }

    /**
     * Resolve the sealing configuration for a workspace root. Returns
     * {@link Optional#empty()} when sealing is disabled (the default); when
     * enabled, key loading or generation happens eagerly here so a
     * misconfigured key fails loudly at construction time, never as a silent
     * no-op ({@code enabled=true} with an unusable key must not run
     * unprotected).
     *
     * <p>When {@code -Datmosphere.ai.state.seal.reseal=true} is set, every
     * state file under the root is resealed as-is before the sealer is
     * returned — the documented one-shot way to bless deliberate hand-edits
     * at startup.</p>
     *
     * @throws AgentStateSealException when sealing is enabled but the key
     *         cannot be provisioned
     */
    public static Optional<AgentStateSealer> fromConfiguration(Path workspaceRoot) {
        if (!flag(ENABLED_PROPERTY, ENABLED_ENV)) {
            return Optional.empty();
        }
        var keyFile = value(KEY_FILE_PROPERTY, KEY_FILE_ENV);
        var sealer = forWorkspace(workspaceRoot,
                keyFile == null ? null : Path.of(keyFile),
                flag(STRICT_PROPERTY, STRICT_ENV));
        if (Boolean.getBoolean(RESEAL_PROPERTY)) {
            var count = sealer.resealAll();
            logger.warn("Resealed {} agent state file(s) under {} because -D{}=true — remove "
                    + "that flag now: left set, it blesses ANY on-disk modification on every "
                    + "restart, tampering included.", count, workspaceRoot, RESEAL_PROPERTY);
        }
        return Optional.of(sealer);
    }

    /**
     * Build a sealer for {@code workspaceRoot} regardless of the opt-in
     * property — the programmatic equivalent of enabling sealing. Loads the
     * key from {@code keyFile} when given; otherwise loads (or, on first
     * boot, generates and persists) the key at
     * {@code {workspaceRoot}.seal/state-seal.key}.
     *
     * @param workspaceRoot the state tree to seal
     * @param keyFile       operator-provisioned key file, or {@code null}
     *                      for the managed default location
     * @param strict        refuse unsealed legacy files when {@code true}
     * @throws AgentStateSealException when the key cannot be loaded,
     *         generated, or persisted
     */
    public static AgentStateSealer forWorkspace(Path workspaceRoot, Path keyFile, boolean strict) {
        var root = workspaceRoot.toAbsolutePath().normalize();
        var sealDir = sealDirFor(root);
        AgentStateIntegrity integrity;
        if (keyFile != null) {
            integrity = loadKey(keyFile.toAbsolutePath().normalize());
        } else {
            integrity = loadOrGenerateKey(sealDir.resolve(KEY_FILE_NAME));
        }
        return new AgentStateSealer(root, sealDir, integrity, strict);
    }

    /**
     * Verify a state file's raw content against its sidecar seal before the
     * caller interprets it. Terminal paths:
     * <ul>
     *   <li>seal present and valid — returns normally;</li>
     *   <li>seal present and invalid, or unreadable/corrupt sidecar — throws
     *       {@link AgentStateSealException} naming the reseal remediation
     *       (fail-closed, Correctness Invariant #6);</li>
     *   <li>no seal, default mode — one-time WARN, returns normally (legacy
     *       adoption; the file is sealed on its next save);</li>
     *   <li>no seal, strict mode — throws.</li>
     * </ul>
     *
     * @param stateFile the file the content was read from
     * @param content   the exact raw content that was read
     */
    public void verifyLoaded(Path stateFile, String content) {
        var file = insideWorkspace(stateFile);
        var slot = slotKey(file);
        var sealFile = sealFileFor(file);
        if (!Files.exists(sealFile)) {
            if (strict) {
                throw new AgentStateSealException("Agent state file '" + file + "' has no "
                        + "integrity seal and strict sealing is on (-D" + STRICT_PROPERTY
                        + "=true) — refusing to load it. " + remediation());
            }
            if (warnedUnsealed.add(file)) {
                logger.warn("Agent state file '{}' predates sealing (no seal at {}) — loading "
                        + "it unverified this time; it will be sealed on its next save. Strict "
                        + "mode (-D{}=true) refuses such files instead.",
                        file, sealFile, STRICT_PROPERTY);
            }
            return;
        }
        var seal = readSeal(sealFile, file);
        if (!integrity.verify(slot, content, seal)) {
            throw new AgentStateSealException("Agent state file '" + file + "' failed integrity "
                    + "verification against its seal (key " + integrity.keyId() + ") — refusing "
                    + "to load it. If the file was NOT deliberately edited, treat it as tampered. "
                    + remediation());
        }
    }

    /**
     * Seal a state file's exact saved content. Called after every successful
     * write; a sidecar that cannot be written throws (a silently missing or
     * stale seal would turn the next read into a false tamper report).
     *
     * @param stateFile the file that was written
     * @param content   the exact raw content now on disk
     */
    public void sealSaved(Path stateFile, String content) {
        var file = insideWorkspace(stateFile);
        var seal = integrity.seal(slotKey(file), content);
        if (!seal.isPresent()) {
            throw new AgentStateSealException(
                    "Failed to compute integrity seal for '" + file + "'");
        }
        var sealFile = sealFileFor(file);
        var props = new Properties();
        props.setProperty("scheme", seal.scheme());
        props.setProperty("keyId", seal.keyId());
        props.setProperty("signature", seal.signature());
        props.setProperty("createdAt", seal.createdAt().toString());
        props.setProperty("path", slotKey(file));
        try {
            Files.createDirectories(sealFile.getParent());
            var out = new StringWriter();
            props.store(out, "Atmosphere agent state seal");
            Files.writeString(sealFile, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentStateSealException(
                    "Failed to write integrity seal " + sealFile + " for '" + file + "'", e);
        }
    }

    /**
     * Remove the sidecar seal of a deleted state file so no stale seal
     * lingers (terminal-path completeness — a later recreation of the file
     * starts from the legacy-adoption path, not from a false mismatch).
     *
     * @param stateFile the state file that was deleted
     */
    public void stateFileDeleted(Path stateFile) {
        var sealFile = sealFileFor(insideWorkspace(stateFile));
        try {
            Files.deleteIfExists(sealFile);
        } catch (IOException e) {
            throw new AgentStateSealException("Failed to remove integrity seal " + sealFile
                    + " for deleted state file '" + stateFile + "'", e);
        }
    }

    /**
     * Seal every regular file under the workspace root as it currently is —
     * the explicit operator step that blesses deliberate hand-edits. Also
     * drops sidecars whose state file no longer exists.
     *
     * @return the number of files sealed
     */
    public int resealAll() {
        var sealed = 0;
        try {
            if (Files.isDirectory(workspaceRoot)) {
                try (Stream<Path> files = Files.walk(workspaceRoot)) {
                    for (var file : files.filter(Files::isRegularFile).toList()) {
                        sealSaved(file, Files.readString(file, StandardCharsets.UTF_8));
                        sealed++;
                    }
                }
            }
            var sealsRoot = sealDir.resolve(SEALS_SUBDIR);
            if (Files.isDirectory(sealsRoot)) {
                try (Stream<Path> sidecars = Files.walk(sealsRoot)) {
                    for (var sidecar : sidecars.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(SEAL_FILE_SUFFIX))
                            .toList()) {
                        if (!Files.exists(stateFileFor(sidecar))) {
                            Files.deleteIfExists(sidecar);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new AgentStateSealException(
                    "Reseal of workspace '" + workspaceRoot + "' failed", e);
        }
        return sealed;
    }

    /** The fingerprint id of the active signing key. */
    public String keyId() {
        return integrity.keyId();
    }

    /** Whether unsealed legacy files are refused instead of adopted. */
    public boolean strict() {
        return strict;
    }

    /** The workspace root this sealer protects. */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** The sidecar directory ({@code {workspaceRoot}.seal}). */
    public Path sealDir() {
        return sealDir;
    }

    // ---------- key provisioning ----------

    private static AgentStateIntegrity loadKey(Path keyFile) {
        if (!Files.isRegularFile(keyFile)) {
            throw new AgentStateSealException("Agent state seal key file '" + keyFile
                    + "' (from -D" + KEY_FILE_PROPERTY + " / " + KEY_FILE_ENV + ") does not "
                    + "exist — refusing to start with sealing enabled but no key.");
        }
        // Pre-existing key files get the same owner-only guarantee as freshly
        // generated ones: tighten, then verify. A group/world-accessible
        // private key voids the trust the seals assert, so this fails loud
        // (same posture as the pairing probe below) rather than running with
        // a leaked key.
        try {
            restrictToOwner(keyFile, false);
            var perms = readPosixPermissions(keyFile);
            if (perms != null) {
                var leaked = perms.stream()
                        .filter(p -> !p.name().startsWith("OWNER_"))
                        .toList();
                if (!leaked.isEmpty()) {
                    throw new AgentStateSealException("Agent state seal key file '" + keyFile
                            + "' remains accessible beyond its owner (" + leaked + ") after "
                            + "tightening — fix the file ownership/permissions before "
                            + "enabling sealing.");
                }
            }
        } catch (IOException e) {
            throw new AgentStateSealException("Failed to restrict permissions of agent state "
                    + "seal key '" + keyFile + "' to the owner", e);
        }
        try {
            var props = new Properties();
            props.load(new StringReader(Files.readString(keyFile, StandardCharsets.UTF_8)));
            var algorithm = props.getProperty("algorithm", "");
            if (!"Ed25519".equals(algorithm)) {
                throw new AgentStateSealException("Agent state seal key file '" + keyFile
                        + "' declares algorithm '" + algorithm + "'; expected Ed25519");
            }
            var privateB64 = props.getProperty("privateKey");
            var publicB64 = props.getProperty("publicKey");
            if (privateB64 == null || publicB64 == null) {
                throw new AgentStateSealException("Agent state seal key file '" + keyFile
                        + "' must contain privateKey and publicKey entries");
            }
            var factory = KeyFactory.getInstance("Ed25519");
            var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(privateB64.trim())));
            var publicKey = factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(publicB64.trim())));
            return probed(privateKey, publicKey, keyFile);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException
                 | IllegalArgumentException e) {
            throw new AgentStateSealException(
                    "Failed to load agent state seal key from '" + keyFile + "'", e);
        }
    }

    private static AgentStateIntegrity loadOrGenerateKey(Path keyFile) {
        if (Files.isRegularFile(keyFile)) {
            return loadKey(keyFile);
        }
        // First boot with sealing enabled: mint a keypair and persist it
        // BEFORE sealing anything — if persistence fails we throw rather
        // than continue on a key that would die with the process (the
        // rejected ephemeral-key design). AgentStateIntegrity.generate() is
        // not used here because it does not expose the private half for
        // export.
        try {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var keyId = AgentStateIntegrity.fingerprint(pair.getPublic());
            var props = new Properties();
            props.setProperty("algorithm", "Ed25519");
            props.setProperty("keyId", keyId);
            props.setProperty("privateKey",
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            props.setProperty("publicKey",
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            Files.createDirectories(keyFile.getParent());
            // Owner-only on the directory BEFORE the key lands in it, so the
            // file is never readable by others even before its own chmod.
            restrictToOwner(keyFile.getParent(), true);
            var out = new StringWriter();
            props.store(out, "Atmosphere agent state seal key — operator-durable, do not delete");
            Files.writeString(keyFile, out.toString(), StandardCharsets.UTF_8);
            restrictToOwner(keyFile, false);
            logger.info("Generated agent state seal key {} at {} (owner-only permissions); "
                    + "back it up — losing it means resealing the workspace.", keyId, keyFile);
            return new AgentStateIntegrity(pair, keyId);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new AgentStateSealException("Failed to generate and persist agent state seal "
                    + "key at '" + keyFile + "' — refusing to fall back to an ephemeral key "
                    + "(seals would not survive a restart)", e);
        }
    }

    /**
     * Confirm the loaded private and public halves actually pair (runtime
     * truth, not file trust): sign a fixed probe and verify it before the
     * key is used on real state.
     */
    private static AgentStateIntegrity probed(PrivateKey privateKey, PublicKey publicKey,
                                              Path keyFile) {
        var candidate = new AgentStateIntegrity(privateKey, publicKey,
                AgentStateIntegrity.fingerprint(publicKey));
        var probeSeal = candidate.seal(PROBE, PROBE);
        if (!candidate.verify(PROBE, PROBE, probeSeal)) {
            throw new AgentStateSealException("Agent state seal key file '" + keyFile
                    + "' holds a private key that does not match its public key — a probe "
                    + "signature failed to verify. Fix the key file before enabling sealing.");
        }
        return candidate;
    }

    /**
     * The file's POSIX permission set, or {@code null} on filesystems with
     * no POSIX view (Windows) — where enforcement stays the best-effort
     * {@code File}-API path in {@link #restrictToOwner} and cannot be
     * re-verified.
     */
    private static java.util.Set<java.nio.file.attribute.PosixFilePermission>
            readPosixPermissions(Path path) throws IOException {
        try {
            return Files.getPosixFilePermissions(path);
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    private static void restrictToOwner(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (Windows): best-effort owner-only via File.
            var file = path.toFile();
            var ok = file.setReadable(false, false) & file.setWritable(false, false)
                    & file.setReadable(true, true) & file.setWritable(true, true);
            if (directory) {
                ok &= file.setExecutable(false, false) & file.setExecutable(true, true);
            }
            if (!ok) {
                logger.warn("Could not restrict permissions of {} to the owner on this "
                        + "filesystem — verify the seal key is not world-readable.", path);
            }
        }
    }

    // ---------- sidecar plumbing ----------

    /**
     * Normalize and require containment in the workspace root before any
     * relativize: a path outside the root would put {@code ..} segments into
     * the sidecar path and place seals outside the seals directory.
     */
    private Path insideWorkspace(Path stateFile) {
        var file = stateFile.toAbsolutePath().normalize();
        if (!file.startsWith(workspaceRoot)) {
            throw new AgentStateSealException("Agent state file '" + file + "' is outside the "
                    + "sealed workspace root '" + workspaceRoot + "' — refusing to derive a "
                    + "seal path for it.");
        }
        return file;
    }

    private static Path sealDirFor(Path root) {
        var name = root.getFileName();
        if (root.getParent() == null || name == null) {
            // Filesystem-root workspace (never the case in practice) — keep
            // the sidecars inside the root rather than failing to start.
            return root.resolve(SEAL_DIR_SUFFIX);
        }
        return root.getParent().resolve(name + SEAL_DIR_SUFFIX);
    }

    private Path sealFileFor(Path stateFile) {
        var relative = workspaceRoot.relativize(stateFile);
        return sealDir.resolve(SEALS_SUBDIR).resolve(relative.toString() + SEAL_FILE_SUFFIX);
    }

    private Path stateFileFor(Path sidecar) {
        var relative = sealDir.resolve(SEALS_SUBDIR).relativize(sidecar).toString();
        return workspaceRoot.resolve(
                relative.substring(0, relative.length() - SEAL_FILE_SUFFIX.length()));
    }

    /**
     * The {@link AgentStateIntegrity} slot key: the workspace-relative path
     * with forward slashes, so seals are portable across operating systems
     * and never replayable across files.
     */
    private String slotKey(Path stateFile) {
        return workspaceRoot.relativize(stateFile).toString().replace('\\', '/');
    }

    private AgentStateIntegrity.Seal readSeal(Path sealFile, Path stateFile) {
        try {
            var props = new Properties();
            props.load(new StringReader(Files.readString(sealFile, StandardCharsets.UTF_8)));
            var scheme = props.getProperty("scheme");
            var keyId = props.getProperty("keyId");
            var signature = props.getProperty("signature");
            var createdAt = props.getProperty("createdAt");
            if (scheme == null || signature == null) {
                throw new AgentStateSealException("Integrity seal " + sealFile + " for '"
                        + stateFile + "' is malformed (missing scheme/signature) — refusing to "
                        + "load the file. " + remediation());
            }
            return new AgentStateIntegrity.Seal(scheme, keyId, signature,
                    createdAt == null ? Instant.EPOCH : Instant.parse(createdAt));
        } catch (IOException | java.time.format.DateTimeParseException e) {
            throw new AgentStateSealException("Integrity seal " + sealFile + " for '" + stateFile
                    + "' cannot be read — refusing to load the file. " + remediation(), e);
        }
    }

    private String remediation() {
        return "If this was a deliberate operator edit, bless it by resealing the workspace: "
                + "run java -cp <atmosphere-ai jar> " + AgentStateReseal.class.getName() + " "
                + workspaceRoot + ", or restart once with -D" + RESEAL_PROPERTY + "=true.";
    }

    // ---------- config resolution ----------

    private static boolean flag(String property, String env) {
        return Boolean.parseBoolean(value(property, env));
    }

    private static String value(String property, String env) {
        var fromProperty = System.getProperty(property);
        if (fromProperty != null) {
            return fromProperty;
        }
        return System.getenv(env);
    }
}
