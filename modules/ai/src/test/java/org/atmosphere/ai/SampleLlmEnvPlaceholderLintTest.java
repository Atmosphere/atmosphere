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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level CI gate that refuses to build if a sample's LLM backend keys are
 * pinned to a literal instead of an {@code ${ENV:default}} placeholder.
 *
 * <h2>Why this gate exists</h2>
 * The Spring binding reads the configured property <em>first</em> and only falls
 * back to the environment ({@code AtmosphereAiAutoConfiguration#resolveMode} and
 * friends), so a literal {@code mode: remote} in a sample's YAML makes
 * {@code LLM_MODE=local} unreachable — the env var is never consulted. Two
 * samples shipped that way and a keyless local sweep found them resolving to
 * {@code mode=remote endpoint=https://generativelanguage.googleapis.com/...}:
 * a documented keyless quickstart silently dialling a remote provider, needing
 * a key nobody was told to set. The same trap applies to {@code model} and
 * {@code base-url} — pinning either sends a local Ollama run at a cloud model
 * name, or a cloud host.
 *
 * <h2>Rule</h2>
 * For every config resource under {@code samples/&#42;/src/main/resources}, the
 * {@code mode} / {@code model} / {@code base-url} keys directly under the
 * {@code llm} or {@code atmosphere.ai} prefix must be written as
 * {@code ${ENV_VAR:default}}. A literal fails the lint.
 *
 * <p>One exemption: a block that declares {@code mode: fake} is an offline demo
 * ({@link org.atmosphere.ai.llm.FakeLlmClient} makes no network call), so the
 * "silently dials a provider" failure it guards against cannot happen and its
 * pinned demo model names are the point of the profile. {@code remote} and
 * {@code local} get no such pass.</p>
 *
 * <p>The walk is over the filesystem, not a hand-maintained list, so a new
 * sample is covered the moment it lands.</p>
 */
class SampleLlmEnvPlaceholderLintTest {

    /**
     * Config prefixes that feed the LLM backend. {@code llm.*} is read by the
     * per-sample {@code LlmConfig} beans and the LangChain4j / Spring AI
     * auto-configurations; {@code atmosphere.ai.*} is read by
     * {@code AtmosphereProperties.AiProperties}. Anything else (for example
     * {@code atmosphere.koog.model} or {@code spring.ai.openai.*}) is a
     * provider-specific block outside this gate.
     */
    private static final List<String> GUARDED_PREFIXES = List.of("llm", "atmosphere.ai");

    /** Leaf keys, already relaxed-normalized (see {@link #normalize(String)}). */
    private static final Set<String> GUARDED_KEYS = Set.of("mode", "model", "baseurl");

    @Test
    void noSamplePinsAnLlmBackendKeyToALiteral() throws IOException {
        var repoRoot = resolveRepoRoot();
        var samples = repoRoot.resolve("samples");
        assertTrue(Files.isDirectory(samples),
                "samples/ directory must exist at repo root: " + samples);

        var scanned = new ArrayList<Path>();
        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(samples)) {
            files.filter(Files::isRegularFile)
                    .filter(SampleLlmEnvPlaceholderLintTest::isSampleMainConfig)
                    .forEach(p -> {
                        scanned.add(p);
                        checkOne(repoRoot, p, offenders);
                    });
        }

        // A silent zero-file walk would make this gate vacuous — the whole point
        // is that it keeps biting as samples come and go.
        assertFalse(scanned.isEmpty(),
                "lint scanned no sample config files under " + samples
                        + " — the walk or the file filter is broken");

        if (!offenders.isEmpty()) {
            throw new AssertionError("The following sample LLM config keys pin a literal value "
                    + "instead of an ${ENV:default} placeholder. The configured property "
                    + "out-ranks the environment, so a pinned value makes LLM_MODE / LLM_MODEL / "
                    + "LLM_BASE_URL unreachable and a keyless local run silently dials a remote "
                    + "provider. Write them as ${LLM_MODE:remote} / ${LLM_MODEL:<default>} / "
                    + "${LLM_BASE_URL:<default>}:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    /** Config resources a sample actually boots from. */
    private static boolean isSampleMainConfig(Path p) {
        var path = p.toString().replace('\\', '/');
        if (path.contains("/target/") || path.contains("/node_modules/")) {
            return false;
        }
        if (!path.contains("/src/main/resources/")) {
            return false;
        }
        var name = p.getFileName().toString();
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties");
    }

    private static void checkOne(Path repoRoot, Path config, List<String> offenders) {
        Map<String, String> flat;
        try {
            flat = config.getFileName().toString().endsWith(".properties")
                    ? readProperties(config)
                    : readYaml(config);
        } catch (IOException | RuntimeException e) {
            // A config this lint cannot parse is a finding in its own right —
            // staying silent would let a malformed sample slip the gate.
            offenders.add(repoRoot.relativize(config) + " — could not be parsed: " + e);
            return;
        }

        for (var prefix : GUARDED_PREFIXES) {
            if (isOfflineFakeBlock(flat, prefix)) {
                continue;
            }
            for (var entry : flat.entrySet()) {
                var key = entry.getKey();
                if (!key.startsWith(prefix + ".")) {
                    continue;
                }
                var leaf = normalize(key.substring(prefix.length() + 1));
                if (!GUARDED_KEYS.contains(leaf)) {
                    continue;
                }
                var value = entry.getValue();
                if (value == null || value.isBlank() || value.trim().startsWith("${")) {
                    continue;
                }
                offenders.add(repoRoot.relativize(config) + " — " + key + ": '" + value.trim()
                        + "' is a literal (expected a ${ENV:default} placeholder)");
            }
        }
    }

    private static boolean isOfflineFakeBlock(Map<String, String> flat, String prefix) {
        var mode = flat.get(prefix + ".mode");
        return mode != null && "fake".equalsIgnoreCase(mode.trim());
    }

    /**
     * Flatten every YAML document in the file to dotted paths. Spring Boot
     * accepts both nested maps and pre-dotted keys, and a single resource may
     * hold several profile documents, so all forms have to normalize to the
     * same view before the keys can be matched.
     */
    private static Map<String, String> readYaml(Path config) throws IOException {
        var options = new LoaderOptions();
        var yaml = new Yaml(new SafeConstructor(options));
        var flat = new LinkedHashMap<String, String>();
        try (Reader reader = Files.newBufferedReader(config)) {
            for (Object document : yaml.loadAll(reader)) {
                flatten("", document, flat);
            }
        }
        return flat;
    }

    private static Map<String, String> readProperties(Path config) throws IOException {
        var props = new Properties();
        try (Reader reader = Files.newBufferedReader(config)) {
            props.load(reader);
        }
        var flat = new LinkedHashMap<String, String>();
        for (var name : props.stringPropertyNames()) {
            flat.put(name, props.getProperty(name));
        }
        return flat;
    }

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                var key = String.valueOf(e.getKey());
                flatten(prefix.isEmpty() ? key : prefix + "." + key, e.getValue(), out);
            }
        } else if (node != null && !prefix.isEmpty() && !(node instanceof Iterable<?>)) {
            // Sequences (routing candidate pools, guardrail lists) are skipped: their
            // own 'model' keys are demo data describing a pool, not a dial target.
            out.put(prefix, String.valueOf(node));
        }
    }

    /** Spring relaxed binding: {@code base-url}, {@code baseUrl} and {@code base_url} are one key. */
    private static String normalize(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
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
