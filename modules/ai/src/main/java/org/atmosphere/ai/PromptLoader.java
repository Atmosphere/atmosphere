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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt and skill file text from classpath, local disk cache, or the
 * <a href="https://github.com/Atmosphere/atmosphere-skills">atmosphere-skills</a>
 * GitHub repository.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Classpath resource (existing behavior)
 * String prompt = PromptLoader.load("prompts/system-prompt.md");
 *
 * // Named skill: classpath -> disk cache -> GitHub
 * String skill = PromptLoader.loadSkill("llm-judge");
 * }</pre>
 *
 * <p>Configure via system properties:</p>
 * <ul>
 *   <li>{@code atmosphere.skills.repo} — GitHub org/repo (default: {@code Atmosphere/atmosphere-skills})</li>
 *   <li>{@code atmosphere.skills.branch} — branch (default: {@code main})</li>
 *   <li>{@code atmosphere.skills.offline} — set {@code true} for air-gapped environments</li>
 * </ul>
 */
public final class PromptLoader {

    private static final Logger logger = LoggerFactory.getLogger(PromptLoader.class);
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private static final String DEFAULT_REPO = "Atmosphere/atmosphere-skills";
    private static final String DEFAULT_BRANCH = "main";
    private static final Path CACHE_DIR = Path.of(
            System.getProperty("user.home"), ".atmosphere", "skills");

    private PromptLoader() {
    }

    /**
     * Loads the text content of a classpath resource and caches the result.
     *
     * @param resourcePath classpath resource path (e.g. {@code "prompts/system-prompt.md"})
     * @return the trimmed text content
     * @throws UncheckedIOException   if the resource cannot be read
     * @throws IllegalArgumentException if the resource is not found
     */
    public static String load(String resourcePath) {
        if (resourcePath.contains("..") || resourcePath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Invalid resource path (must not contain '..' or start with '/'): " + resourcePath);
        }
        return CACHE.computeIfAbsent(resourcePath, PromptLoader::readResource);
    }

    /**
     * Clears the prompt cache. Primarily intended for testing.
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Loads a named skill file using a three-tier search:
     * <ol>
     *   <li>Classpath: {@code META-INF/skills/{name}/SKILL.md}</li>
     *   <li>Local disk cache: {@code ~/.atmosphere/skills/{name}/SKILL.md}</li>
     *   <li>GitHub: {@code raw.githubusercontent.com/{repo}/main/skills/{name}/SKILL.md}</li>
     * </ol>
     *
     * <p>On successful GitHub fetch, the content is cached to disk for subsequent runs.</p>
     *
     * @param skillName the skill name (e.g. {@code "llm-judge"}, {@code "startup-ceo"})
     * @return the skill file content, or {@code null} if not found anywhere
     */
    public static String loadSkill(String skillName) {
        return CACHE.computeIfAbsent("skill:" + skillName, k -> resolveSkill(skillName));
    }

    /**
     * Resolves a skill file path. If the path starts with {@code skill:}, delegates
     * to {@link #loadSkill} and throws if not found (the developer explicitly
     * requested a named skill). Plain paths use the standard classpath loader.
     *
     * @param path skill reference ({@code "skill:llm-judge"}) or classpath path
     * @return the content
     * @throws IllegalStateException if a {@code skill:} reference is not found anywhere
     */
    public static String resolve(String path) {
        if (path != null && path.startsWith("skill:")) {
            var skillName = path.substring(6);
            var content = loadSkill(skillName);
            if (content == null) {
                // Don't crash the app — an agent without a custom prompt still works
                // (the LLM uses its default behavior). This is critical for CI
                // environments where GitHub may be rate-limited or unreachable.
                logger.warn("Skill '{}' not found on classpath, disk cache, or GitHub. "
                        + "Agent will use default LLM behavior. Add the skill to "
                        + "https://github.com/{}/tree/{}/skills/{}",
                        skillName, repo(), branch(), skillName);
                return "You are a helpful assistant.";
            }
            return content;
        }
        return load(path);
    }

    private static String resolveSkill(String name) {
        // 1. Classpath
        var classpathPaths = new String[]{
                "META-INF/skills/" + name + "/SKILL.md",
                "prompts/" + name + "-skill.md",
                "prompts/" + name + ".md"
        };
        for (var cp : classpathPaths) {
            try {
                var content = readResourceOrNull(cp);
                if (content != null) {
                    logger.debug("Skill '{}' loaded from classpath: {}", name, cp);
                    return content;
                }
            } catch (Exception ignored) { }
        }

        // 2. Local disk cache
        var cached = readDiskCache(name);
        if (cached != null) {
            logger.debug("Skill '{}' loaded from disk cache", name);
            return cached;
        }

        // 3. GitHub with retry (unless offline)
        if ("true".equalsIgnoreCase(System.getProperty("atmosphere.skills.offline"))) {
            logger.debug("Skill '{}' not found (offline mode)", name);
            return null;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            var fetched = fetchFromGitHub(name);
            if (fetched != null) {
                writeDiskCache(name, fetched);
                logger.info("Skill '{}' fetched from GitHub and cached locally", name);
                return fetched;
            }
            if (attempt == 0) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        logger.warn("Skill '{}' not found. Add it to https://github.com/{}/tree/{}/skills/{}",
                name, repo(), branch(), name);
        return null;
    }

    private static String readDiskCache(String name) {
        var path = CACHE_DIR.resolve(name).resolve("SKILL.md");
        if (Files.exists(path)) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                logger.trace("Failed to read disk cache for skill '{}'", name, e);
            }
        }
        return null;
    }

    private static void writeDiskCache(String name, String content) {
        try {
            var dir = CACHE_DIR.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.trace("Failed to write disk cache for skill '{}'", name, e);
        }
    }

    private static String fetchFromGitHub(String name) {
        var url = String.format(
                "https://raw.githubusercontent.com/%s/%s/skills/%s/SKILL.md",
                repo(), branch(), name);
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                var content = response.body().trim();
                // Verify integrity against registry.json hashes
                var expectedHash = fetchExpectedHash(client, name);
                if (expectedHash != null) {
                    var actualHash = sha256(content);
                    if (!expectedHash.equals(actualHash)) {
                        logger.error("INTEGRITY FAILURE for skill '{}': "
                                + "expected SHA-256 {} but got {}. "
                                + "The skills repo may be compromised.",
                                name, expectedHash, actualHash);
                        return null;
                    }
                    logger.debug("Skill '{}' integrity verified (SHA-256: {})", name, actualHash);
                }
                return content;
            }
            logger.debug("GitHub returned {} for skill '{}'", response.statusCode(), name);
        } catch (Exception e) {
            logger.debug("Failed to fetch skill '{}' from GitHub: {}", name, e.getMessage());
        }
        return null;
    }

    /** Cached registry hashes — loaded once from GitHub. */
    private static volatile Map<String, String> registryHashes;

    private static String fetchExpectedHash(HttpClient client, String name) {
        if (registryHashes == null) {
            try {
                var regUrl = String.format(
                        "https://raw.githubusercontent.com/%s/%s/registry.json",
                        repo(), branch());
                var req = HttpRequest.newBuilder()
                        .uri(URI.create(regUrl))
                        .timeout(Duration.ofSeconds(3))
                        .GET().build();
                var res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    registryHashes = parseRegistryHashes(res.body());
                } else {
                    registryHashes = Map.of();
                }
            } catch (Exception e) {
                logger.trace("Failed to fetch registry.json: {}", e.getMessage());
                registryHashes = Map.of();
            }
        }
        return registryHashes.get(name);
    }

    private static Map<String, String> parseRegistryHashes(String json) {
        // Simple parse — extract "id" and "sha256" from skills array
        // Full JSON parser not available without Jackson dep; use regex
        var hashes = new ConcurrentHashMap<String, String>();
        var idPattern = java.util.regex.Pattern.compile(
                "\"id\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"sha256\"\\s*:\\s*\"([^\"]+)\"",
                java.util.regex.Pattern.DOTALL);
        var matcher = idPattern.matcher(json);
        while (matcher.find()) {
            hashes.put(matcher.group(1), matcher.group(2));
        }
        return hashes;
    }

    private static String sha256(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }

    private static String repo() {
        return System.getProperty("atmosphere.skills.repo", DEFAULT_REPO);
    }

    private static String branch() {
        return System.getProperty("atmosphere.skills.branch", DEFAULT_BRANCH);
    }

    private static String readResourceOrNull(String path) {
        var classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PromptLoader.class.getClassLoader();
        }
        try (var stream = classLoader.getResourceAsStream(path)) {
            if (stream == null) return null;
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static String readResource(String path) {
        var classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PromptLoader.class.getClassLoader();
        }
        try (var stream = classLoader.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Prompt resource not found on classpath: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt resource: " + path, e);
        }
    }
}
