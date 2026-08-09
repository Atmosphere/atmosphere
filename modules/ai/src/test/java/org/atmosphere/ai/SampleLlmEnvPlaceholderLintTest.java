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
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level CI gate over the LLM backend keys of every sample config, so a
 * keyless local run of a sample cannot silently dial a cloud provider.
 *
 * <h2>The invariant being protected</h2>
 * Every base-URL resolution site in the repo has the same shape: an explicit,
 * non-blank configured URL wins, and <em>only a blank one</em> falls back to the
 * mode-derived endpoint (loopback Ollama for {@code local}).
 * <ul>
 *   <li>{@code AiConfig.resolveBaseUrl} — {@code if (explicitUrl != null &&
 *       !explicitUrl.isBlank()) return explicitUrl;} before the
 *       {@code "local".equalsIgnoreCase(mode)} branch.</li>
 *   <li>{@code AtmosphereLangChain4jAutoConfiguration} — {@code baseUrl.isBlank()
 *       ? (local ? "http://localhost:11434/v1" : ...) : baseUrl}.</li>
 *   <li>{@code AtmosphereKoogAutoConfiguration} — {@code if (baseUrl.isBlank()
 *       && local) OLLAMA else baseUrl}.</li>
 * </ul>
 * So <strong>whatever a sample's {@code base-url} resolves to when nothing is
 * exported outranks {@code LLM_MODE=local}</strong>. That makes two different
 * mistakes equally fatal, and this class gates both.
 *
 * <h2>Rule 1 — no literal pins (any prefix)</h2>
 * The {@code mode} / {@code model} / {@code base-url} leaf of any config key
 * must be written as an {@code ${ENV:default}} placeholder. A literal is never
 * overridable: {@code mode: remote} makes {@code LLM_MODE} unreachable, a
 * literal {@code model} makes {@code LLM_MODEL} unreachable, a literal
 * {@code base-url} makes {@code LLM_BASE_URL} unreachable.
 *
 * <p>This rule used to apply only under the {@code llm} and {@code atmosphere.ai}
 * prefixes, which left every provider-specific block ({@code atmosphere.koog.*},
 * {@code spring.ai.*}, {@code quarkus.langchain4j.*}, {@code embabel.*})
 * ungated. The walk is now leaf-based and prefix-agnostic, so a block for a
 * runtime that does not exist yet is covered the day it lands — the same reason
 * the file walk is over the filesystem instead of a hand-maintained list.</p>
 *
 * <h2>Rule 2 — no remote default behind a placeholder, in Atmosphere's own namespace</h2>
 * {@code ${LLM_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai}}
 * <em>looks</em> overridable and passes Rule 1, but a keyless local sweep sets
 * {@code LLM_MODE=local} — it does not set {@code LLM_BASE_URL}, because the
 * whole point of {@code local} is that the endpoint is derived. The default half
 * of the placeholder therefore lands in the resolver as an explicit URL and wins,
 * exactly as a literal would. Symptom and blast radius are identical to Rule 1;
 * only the syntax is one level deeper.
 *
 * <p>So for {@code base-url} keys under a prefix Atmosphere itself resolves
 * ({@code llm.*}, {@code atmosphere.*}) the <em>effective default</em> — the
 * innermost default of the {@code ${A:${B:default}}} chain — must be blank or a
 * loopback host. Blank is not a downgrade: leaving it blank is what hands
 * resolution back to {@code AiConfig.resolveBaseUrl}, which picks Ollama for
 * {@code local} and auto-detects the provider from the model id for
 * {@code remote}. Both quickstarts keep working.</p>
 *
 * <p>Rule 2 deliberately does <strong>not</strong> extend to {@code model}.
 * A model id is not a dial target: with a blank {@code base-url} and
 * {@code LLM_MODE=local} the request goes to loopback Ollama and a cloud model
 * id fails <em>loudly</em> there ("model not found") without a byte leaving the
 * machine. {@code ${LLM_MODEL:gemini-2.5-flash}} is how eleven samples keep
 * their keyed remote quickstart working out of the box; failing it would cost
 * those quickstarts and buy no safety.</p>
 *
 * <h2>Rule 3 — third-party namespaces must be documented, not silent</h2>
 * {@code spring.ai.openai.base-url} and {@code quarkus.langchain4j.openai.base-url}
 * belong to another framework's config namespace. Blanking them does not hand
 * resolution to Atmosphere's mode-aware resolver — it hands it to that
 * framework's own hardcoded default ({@code api.openai.com}), so a sample that
 * targets Gemini has nothing to defer to and legitimately pins the endpoint.
 * Those are allowed to keep a remote default, but not silently: the placeholder's
 * override variable must be listed in the sample matrix ({@code cli/samples.json}
 * {@code envVars}) so a reader is told which variable to export to point the
 * sample somewhere else. A literal with no override variable at all is still a
 * failure.
 *
 * <h2>Exemption</h2>
 * A block that declares {@code mode: fake} is an offline demo
 * ({@link org.atmosphere.ai.llm.FakeLlmClient} makes no network call), so the
 * "silently dials a provider" failure cannot happen and its pinned demo model
 * names are the point of the profile. {@code remote} and {@code local} get no
 * such pass.
 */
class SampleLlmEnvPlaceholderLintTest {

    /** Leaf keys, already relaxed-normalized (see {@link #normalize(String)}). */
    private static final Set<String> GUARDED_KEYS = Set.of("mode", "model", "baseurl");

    /**
     * Config namespaces Atmosphere itself resolves, i.e. where a blank
     * {@code base-url} reaches a {@code mode=local -> loopback} branch that a
     * remote default would shadow. Anything outside these prefixes belongs to a
     * third-party framework and falls under Rule 3 instead.
     */
    private static final List<String> ATMOSPHERE_RESOLVED_PREFIXES = List.of("llm.", "atmosphere.");

    // ---------------------------------------------------------------- Rule 1

    @Test
    void noSamplePinsAnLlmBackendKeyToALiteral() throws IOException {
        var repoRoot = resolveRepoRoot();
        var configs = scanSampleConfigs(repoRoot);

        var offenders = new ArrayList<String>();
        for (var cfg : configs) {
            for (var entry : cfg.flat().entrySet()) {
                var key = entry.getKey();
                if (!GUARDED_KEYS.contains(normalize(leafOf(key))) || isOfflineFakeBlock(cfg.flat(), key)) {
                    continue;
                }
                var value = entry.getValue();
                if (value == null || value.isBlank() || value.trim().startsWith("${")) {
                    continue;
                }
                offenders.add(repoRoot.relativize(cfg.file()) + " — " + key + ": '" + value.trim()
                        + "' is a literal (expected a ${ENV:default} placeholder)");
            }
        }

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

    // ---------------------------------------------------------------- Rule 2

    @Test
    void noAtmosphereResolvedBaseUrlDefaultsToARemoteHost() throws IOException {
        var repoRoot = resolveRepoRoot();
        var configs = scanSampleConfigs(repoRoot);

        var offenders = new ArrayList<String>();
        for (var cfg : configs) {
            for (var entry : remoteBaseUrlDefaults(cfg)) {
                if (!isAtmosphereResolved(entry.key())) {
                    continue;
                }
                offenders.add(repoRoot.relativize(cfg.file()) + " — " + entry.key() + ": '"
                        + entry.value().trim() + "' defaults to remote host '" + entry.host()
                        + "'; a run that exports LLM_MODE=local and nothing else still dials it");
            }
        }

        if (!offenders.isEmpty()) {
            throw new AssertionError("The following sample base-url keys sit in a namespace "
                    + "Atmosphere resolves, and default to a remote provider. Every resolver "
                    + "(AiConfig.resolveBaseUrl, AtmosphereLangChain4jAutoConfiguration, "
                    + "AtmosphereKoogAutoConfiguration) returns a non-blank configured URL "
                    + "before it ever looks at mode, so this default out-ranks LLM_MODE=local "
                    + "and a keyless local run silently dials the provider. Leave the default "
                    + "blank (${LLM_BASE_URL:}) and let the resolver pick — it returns Ollama "
                    + "for local and auto-detects the provider from the model id for remote:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    // ---------------------------------------------------------------- Rule 3

    @Test
    void everyThirdPartyRemoteBaseUrlDefaultIsDocumentedInTheSampleMatrix() throws IOException {
        var repoRoot = resolveRepoRoot();
        var configs = scanSampleConfigs(repoRoot);
        var matrix = readSampleMatrix(repoRoot);

        var offenders = new ArrayList<String>();
        for (var cfg : configs) {
            for (var entry : remoteBaseUrlDefaults(cfg)) {
                if (isAtmosphereResolved(entry.key())) {
                    continue;
                }
                var sample = sampleNameOf(repoRoot, cfg.file());
                var override = outerPlaceholderVariable(entry.value());
                if (override == null) {
                    offenders.add(repoRoot.relativize(cfg.file()) + " — " + entry.key()
                            + ": remote host '" + entry.host() + "' is pinned with no "
                            + "${ENV:...} override variable at all");
                } else if (!matrix.getOrDefault(sample, Set.of()).contains(override)) {
                    offenders.add(repoRoot.relativize(cfg.file()) + " — " + entry.key()
                            + ": defaults to remote host '" + entry.host() + "'; sample '"
                            + sample + "' must document '" + override + "' in cli/samples.json "
                            + "envVars (currently documents: "
                            + matrix.getOrDefault(sample, Set.of()) + ")");
                }
            }
        }

        if (!offenders.isEmpty()) {
            throw new AssertionError("A base-url in a third-party config namespace (spring.ai.*, "
                    + "quarkus.langchain4j.*, ...) may target a specific provider — blanking it "
                    + "hands resolution to that framework's own api.openai.com default, not to "
                    + "Atmosphere's mode-aware resolver, so there is nothing to defer to. It may "
                    + "not do so silently: name the override variable in the sample matrix so a "
                    + "reader knows what to export to point the sample at a local endpoint:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    // ------------------------------------------------------------- shared walk

    /** One parsed sample config, flattened to dotted keys. */
    private record SampleConfig(Path file, Map<String, String> flat) {
    }

    /** A {@code base-url} entry whose effective default names a non-loopback host. */
    private record RemoteBaseUrl(String key, String value, String host) {
    }

    private static List<SampleConfig> scanSampleConfigs(Path repoRoot) throws IOException {
        var samples = repoRoot.resolve("samples");
        assertTrue(Files.isDirectory(samples),
                "samples/ directory must exist at repo root: " + samples);

        var configs = new ArrayList<SampleConfig>();
        var unparseable = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(samples)) {
            files.filter(Files::isRegularFile)
                    .filter(SampleLlmEnvPlaceholderLintTest::isSampleMainConfig)
                    .sorted()
                    .forEach(p -> {
                        try {
                            configs.add(new SampleConfig(p, p.getFileName().toString().endsWith(".properties")
                                    ? readProperties(p)
                                    : readYaml(p)));
                        } catch (IOException | RuntimeException e) {
                            // A config this lint cannot parse is a finding in its own right —
                            // staying silent would let a malformed sample slip the gate.
                            unparseable.add(repoRoot.relativize(p) + " — could not be parsed: " + e);
                        }
                    });
        }

        // A silent zero-file walk would make this gate vacuous — the whole point
        // is that it keeps biting as samples come and go.
        assertFalse(configs.isEmpty() && unparseable.isEmpty(),
                "lint scanned no sample config files under " + samples
                        + " — the walk or the file filter is broken");
        assertTrue(unparseable.isEmpty(),
                "sample config files could not be parsed:\n  " + String.join("\n  ", unparseable));
        return configs;
    }

    /** Every {@code base-url} entry in one config whose effective default is a remote host. */
    private static List<RemoteBaseUrl> remoteBaseUrlDefaults(SampleConfig cfg) {
        var found = new ArrayList<RemoteBaseUrl>();
        for (var entry : cfg.flat().entrySet()) {
            var key = entry.getKey();
            if (!"baseurl".equals(normalize(leafOf(key))) || isOfflineFakeBlock(cfg.flat(), key)) {
                continue;
            }
            var effective = effectiveDefault(entry.getValue());
            if (effective.isBlank()) {
                continue;
            }
            var host = hostOf(effective);
            if (!isLoopback(host)) {
                found.add(new RemoteBaseUrl(key, entry.getValue(), host));
            }
        }
        return found;
    }

    private static boolean isAtmosphereResolved(String key) {
        return ATMOSPHERE_RESOLVED_PREFIXES.stream().anyMatch(key::startsWith);
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

    /**
     * True when the key sits inside a block that declares {@code mode: fake}.
     * Checked against every ancestor prefix so a {@code mode: fake} at the block
     * root also covers nested keys such as {@code llm.chat.options.model}.
     */
    private static boolean isOfflineFakeBlock(Map<String, String> flat, String key) {
        var probe = key;
        while (true) {
            var dot = probe.lastIndexOf('.');
            var parent = dot < 0 ? "" : probe.substring(0, dot);
            var mode = flat.get(parent.isEmpty() ? "mode" : parent + ".mode");
            if (mode != null && "fake".equalsIgnoreCase(mode.trim())) {
                return true;
            }
            if (dot < 0) {
                return false;
            }
            probe = parent;
        }
    }

    // ------------------------------------------------------- placeholder logic

    /**
     * The value a run that exports nothing actually gets: the innermost default
     * of a {@code ${A:${B:default}}} chain, or the literal itself.
     *
     * <p>A {@code ${VAR}} with no default resolves to nothing without the
     * variable, so it pins no host and reads as blank here.</p>
     */
    private static String effectiveDefault(String raw) {
        var value = raw == null ? "" : raw.trim();
        while (value.startsWith("${") && closesAtEnd(value)) {
            var body = value.substring(2, value.length() - 1);
            var colon = topLevelColon(body);
            if (colon < 0) {
                return "";
            }
            value = body.substring(colon + 1).trim();
        }
        return value;
    }

    /**
     * The variable name of the outermost {@code ${NAME:...}} placeholder — the
     * knob a reader is told to export. Null when the value is a bare literal.
     */
    private static String outerPlaceholderVariable(String raw) {
        var value = raw == null ? "" : raw.trim();
        if (!value.startsWith("${") || !closesAtEnd(value)) {
            return null;
        }
        var body = value.substring(2, value.length() - 1);
        var colon = topLevelColon(body);
        var name = (colon < 0 ? body : body.substring(0, colon)).trim();
        return name.isEmpty() ? null : name;
    }

    /** True when the {@code ${} opening the value is closed by the final character. */
    private static boolean closesAtEnd(String value) {
        var depth = 0;
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == '$' && i + 1 < value.length() && value.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i == value.length() - 1;
                }
            }
        }
        return false;
    }

    /** Index of the {@code :} that separates name from default, ignoring nested placeholders. */
    private static int topLevelColon(String body) {
        var depth = 0;
        for (var i = 0; i < body.length(); i++) {
            var c = body.charAt(i);
            if (c == '$' && i + 1 < body.length() && body.charAt(i + 1) == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
            } else if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Host component of a URL-ish value; the whole value when it has no scheme or authority. */
    private static String hostOf(String url) {
        var rest = url.trim();
        var scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        if (rest.startsWith("[")) {
            var close = rest.indexOf(']');
            return close > 0 ? rest.substring(1, close) : rest.substring(1);
        }
        var end = rest.length();
        for (var i = 0; i < rest.length(); i++) {
            var c = rest.charAt(i);
            if (c == '/' || c == ':' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host)
                || "0.0.0.0".equals(host)
                || host.startsWith("127.")
                || "::1".equals(host)
                || "0:0:0:0:0:0:0:1".equals(host);
    }

    // ------------------------------------------------------------ sample matrix

    /** {@code cli/samples.json} sample name -> documented {@code envVars} keys. */
    private static Map<String, Set<String>> readSampleMatrix(Path repoRoot) throws IOException {
        var matrixFile = repoRoot.resolve("cli").resolve("samples.json");
        assertTrue(Files.isRegularFile(matrixFile),
                "sample matrix must exist: " + matrixFile);

        var root = JsonMapper.builder().build().readTree(Files.readString(matrixFile));
        var out = new LinkedHashMap<String, Set<String>>();
        for (var sample : root.path("samples")) {
            var name = sample.path("name").asString("");
            out.put(name, new LinkedHashSet<>(sample.path("envVars").propertyNames()));
        }
        assertFalse(out.isEmpty(), "sample matrix listed no samples: " + matrixFile);
        return out;
    }

    /** The {@code samples/<name>/...} directory a config belongs to. */
    private static String sampleNameOf(Path repoRoot, Path config) {
        return repoRoot.resolve("samples").relativize(config).getName(0).toString();
    }

    // -------------------------------------------------------------- config I/O

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

    /** Trailing segment of a dotted key. */
    private static String leafOf(String key) {
        var dot = key.lastIndexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
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
