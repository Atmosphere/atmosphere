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
package org.atmosphere.ai.prompt;

import org.atmosphere.ai.PromptLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The shipped {@link PromptRegistry}: versioned prompts as files, one file per
 * version, in a fixed {@code prompts/<name>/<version>.md} layout.
 *
 * <p>Two tiers, composed from {@link PromptLoader} rather than forked:</p>
 * <ol>
 *   <li><b>Disk override</b> (optional): {@code atmosphere.ai.prompt.dir}
 *       system property names a directory containing
 *       {@code <name>/<version>.md} files. Read fresh on every resolve so
 *       operators can stage new versions without a rebuild. Wins over the
 *       classpath.</li>
 *   <li><b>Classpath</b>: {@code prompts/<name>/<version>.md} resources loaded
 *       via {@link PromptLoader#loadOptional(String)} (shared cache + path
 *       validation). Because the classpath cannot be listed portably, versions
 *       are discovered by probing {@code v1, v2, ...} upward — classpath
 *       versions must therefore be contiguous starting at {@code v1} (the disk
 *       tier has no such restriction).</li>
 * </ol>
 *
 * <p><b>Integrity</b>: an optional sidecar {@code <version>.md.sha256}
 * (lowercase hex SHA-256 of the trimmed content) is verified when present in
 * the tier the content came from; a mismatch fails closed with
 * {@link IllegalStateException} — mirroring {@link PromptLoader}'s registry
 * hash verification for GitHub-fetched skills.</p>
 *
 * <p>Names and versions are validated against strict patterns and the resolved
 * disk path is normalized and containment-checked, so registry keys can never
 * escape the prompt directory (Correctness Invariant #4).</p>
 */
public final class FilePromptRegistry implements PromptRegistry {

    /** System property naming the optional disk-override prompt directory. */
    public static final String PROMPT_DIR_PROPERTY = "atmosphere.ai.prompt.dir";

    private static final Logger logger = LoggerFactory.getLogger(FilePromptRegistry.class);

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern VERSION = Pattern.compile("v\\d{1,9}");
    private static final Pattern VERSION_FILE = Pattern.compile("(v\\d{1,9})\\.md");

    /** Upper bound on the contiguous classpath version probe. */
    private static final int MAX_CLASSPATH_PROBE = 200;

    @Override
    public Optional<String> content(String name, String version) {
        validateName(name);
        validateVersion(name, version);

        var disk = diskContent(name, version);
        if (disk.isPresent()) {
            return disk;
        }
        var resource = "prompts/" + name + "/" + version + ".md";
        var classpath = PromptLoader.loadOptional(resource);
        classpath.ifPresent(text -> verifyIntegrity(name, version, text,
                PromptLoader.loadOptional(resource + ".sha256").orElse(null)));
        return classpath;
    }

    @Override
    public List<String> versions(String name) {
        validateName(name);
        var found = new TreeSet<String>(FilePromptRegistry::compareVersions);

        // Classpath tier: contiguous probe from v1 (jars cannot be listed portably).
        for (var i = 1; i <= MAX_CLASSPATH_PROBE; i++) {
            if (PromptLoader.loadOptional("prompts/" + name + "/v" + i + ".md").isEmpty()) {
                break;
            }
            found.add("v" + i);
        }

        // Disk tier: real directory listing, may be sparse.
        promptDir().ifPresent(base -> {
            var dir = base.resolve(name).normalize();
            if (!dir.startsWith(base) || !Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.forEach(file -> {
                    var matcher = VERSION_FILE.matcher(file.getFileName().toString());
                    if (matcher.matches()) {
                        found.add(matcher.group(1));
                    }
                });
            } catch (IOException e) {
                logger.trace("Failed to list prompt versions under {}", dir, e);
            }
        });
        return List.copyOf(found);
    }

    private Optional<String> diskContent(String name, String version) {
        var base = promptDir().orElse(null);
        if (base == null) {
            return Optional.empty();
        }
        var file = base.resolve(name).resolve(version + ".md").normalize();
        if (!file.startsWith(base)) {
            // Unreachable with validated name/version; defense in depth.
            throw new IllegalArgumentException(
                    "Prompt path escapes the configured prompt directory: " + file);
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            var text = Files.readString(file, StandardCharsets.UTF_8).trim();
            var sidecar = file.resolveSibling(version + ".md.sha256");
            String expected = null;
            if (Files.isRegularFile(sidecar)) {
                expected = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
            }
            verifyIntegrity(name, version, text, expected);
            return Optional.of(text);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to read prompt file for '" + name + "@" + version + "': " + file, e);
        }
    }

    private static void verifyIntegrity(String name, String version, String content, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            return;
        }
        var actual = sha256(content);
        if (!actual.equals(expectedSha256.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "INTEGRITY FAILURE for prompt '" + name + "@" + version + "': expected SHA-256 "
                            + expectedSha256 + " but content hashes to " + actual
                            + ". Refusing to serve a tampered prompt.");
        }
        logger.debug("Prompt '{}@{}' integrity verified (SHA-256: {})", name, version, actual);
    }

    private static String sha256(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA spec on every conforming JRE.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Optional<Path> promptDir() {
        var dir = System.getProperty(PROMPT_DIR_PROPERTY);
        if (dir == null || dir.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(dir).toAbsolutePath().normalize());
    }

    private static void validateName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid prompt name '" + name + "' (allowed: [A-Za-z0-9][A-Za-z0-9._-]*)");
        }
    }

    private static void validateVersion(String name, String version) {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException(
                    "Invalid version '" + version + "' for prompt '" + name
                            + "' (allowed: v<digits>)");
        }
    }

    private static int compareVersions(String left, String right) {
        return Integer.compare(Integer.parseInt(left.substring(1)),
                Integer.parseInt(right.substring(1)));
    }
}
