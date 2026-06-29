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
package org.atmosphere.ai.workspace;

import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyRegistry;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges a loaded {@link AgentDefinition} into running Atmosphere behavior.
 *
 * <p>The {@link OpenClawWorkspaceAdapter} reads the OpenClaw-canonical files and
 * the Atmosphere extension files ({@code RUNTIME.md}, {@code PERMISSIONS.md},
 * {@code SKILLS.md}, {@code CHANNELS.md}, {@code MCP.md}) into
 * {@link AgentDefinition#atmosphereExtensions()}. This helper is the consumer
 * that turns that parsed-but-inert content into effect:</p>
 *
 * <ul>
 *   <li>{@code RUNTIME.md} → {@link AiConfig#configure} (model / mode / base URL
 *       / API key) plus the generation knobs ({@code temperature},
 *       {@code maxTokens}, {@code topP}, {@code stop}). <strong>Global, not
 *       per-agent</strong>: {@link AiConfig} is a process-wide singleton, so the
 *       last workspace loaded wins — see {@link #applyRuntime}.</li>
 *   <li>{@code PERMISSIONS.md} → {@link GovernancePolicy} instances installed
 *       into the framework's {@link GovernancePolicy#POLICIES_PROPERTY} bag, the
 *       single source of truth every dispatch path admits against.</li>
 *   <li>{@code SKILLS.md} and the discovered {@code SKILL.md} files →
 *       appended to the agent's system prompt (skills-as-prompts).</li>
 * </ul>
 *
 * <p>{@code CHANNELS.md} and {@code MCP.md} are parsed by the per-agent
 * processors (which own the channel / MCP-client dependencies) via
 * {@link #channelNames} and {@link #mcpServerUris}; this module deliberately
 * does not depend on {@code atmosphere-channels} / {@code atmosphere-mcp-client}.</p>
 *
 * <p>All parsing treats the workspace files as <em>untrusted</em> input
 * (Correctness Invariant #4): filesystem paths are normalized, governance
 * parsing is fail-closed via {@link PolicyRegistry}, and a non-blank
 * {@code PERMISSIONS.md} that yields zero policies is logged loudly rather than
 * silently disabling governance.</p>
 */
public final class WorkspaceExtensions {

    /** System property / framework init-param naming the workspace directory. */
    public static final String WORKSPACE_PROPERTY = "atmosphere.workspace";
    /** Environment-variable equivalent of {@link #WORKSPACE_PROPERTY}. */
    public static final String WORKSPACE_ENV = "ATMOSPHERE_WORKSPACE";

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceExtensions.class);

    private static final String RUNTIME_MD = "RUNTIME.md";
    private static final String PERMISSIONS_MD = "PERMISSIONS.md";
    private static final String SKILLS_MD = "SKILLS.md";
    private static final String CHANNELS_MD = "CHANNELS.md";
    private static final String MCP_MD = "MCP.md";

    private static final String CLASSPATH_PREFIX = "classpath:";

    private WorkspaceExtensions() {
    }

    /**
     * Resolve the configured workspace location from (in precedence order) the
     * {@link #WORKSPACE_PROPERTY} system property, the {@link #WORKSPACE_ENV}
     * environment variable, or the framework init-parameter of the same name.
     *
     * @param framework the running framework (may be {@code null})
     * @return the configured location, or {@code null} when none is set
     */
    public static String resolveLocation(AtmosphereFramework framework) {
        var location = System.getProperty(WORKSPACE_PROPERTY);
        if (location == null || location.isBlank()) {
            location = System.getenv(WORKSPACE_ENV);
        }
        if ((location == null || location.isBlank()) && framework != null
                && framework.getAtmosphereConfig() != null) {
            location = framework.getAtmosphereConfig().getInitParameter(WORKSPACE_PROPERTY);
        }
        return (location == null || location.isBlank()) ? null : location.trim();
    }

    /**
     * Load the agent workspace at the configured location through the
     * {@link AgentWorkspaceLoader} SPI. Accepts a filesystem path or a
     * {@code classpath:} resource that resolves to a real directory on disk.
     * Returns {@link Optional#empty()} (with a warning) when the location is
     * unset, missing, not a directory, or cannot be loaded — loading never
     * aborts agent startup.
     *
     * @param location filesystem path or {@code classpath:} location, may be {@code null}
     * @return the loaded definition, or empty
     */
    public static Optional<AgentDefinition> load(String location) {
        if (location == null || location.isBlank()) {
            return Optional.empty();
        }
        var resolved = resolveRoot(location.trim());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var root = resolved.get();
        if (!Files.isDirectory(root)) {
            logger.warn("Configured agent workspace '{}' is not a directory ({}) — skipping", location, root);
            return Optional.empty();
        }
        try {
            return Optional.of(new AgentWorkspaceLoader().load(root));
        } catch (RuntimeException e) {
            logger.warn("Failed to load agent workspace at {}: {}", root, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<Path> resolveRoot(String location) {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            var name = location.substring(CLASSPATH_PREFIX.length());
            var resourceName = name.startsWith("/") ? name.substring(1) : name;
            var cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = WorkspaceExtensions.class.getClassLoader();
            }
            var url = cl.getResource(resourceName);
            if (url == null) {
                logger.warn("classpath agent workspace resource '{}' not found on the classpath", resourceName);
                return Optional.empty();
            }
            if (!"file".equals(url.getProtocol())) {
                logger.warn("classpath agent workspace '{}' resolves into a non-filesystem location ({}); "
                        + "extract it to a directory and point {} at the filesystem path",
                        location, url, WORKSPACE_PROPERTY);
                return Optional.empty();
            }
            try {
                return Optional.of(Path.of(url.toURI()).toAbsolutePath().normalize());
            } catch (URISyntaxException e) {
                logger.warn("Invalid classpath agent workspace URL {}: {}", url, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.of(Path.of(location).toAbsolutePath().normalize());
    }

    // -- SKILLS.md + skill files → system prompt ------------------------------

    /**
     * Append the workspace's composed prompt, its {@code SKILLS.md}, and any
     * discovered {@code SKILL.md} files to the agent's base system prompt
     * (skills-as-prompts). The base prompt leads; the workspace content is
     * appended as labelled sections so the agent's host class stays in control.
     *
     * @param base the base system prompt (from the {@code @Agent} skill file), may be {@code null}
     * @param def  the loaded workspace definition, may be {@code null}
     * @return the augmented prompt (never {@code null})
     */
    public static String augmentSystemPrompt(String base, AgentDefinition def) {
        if (def == null) {
            return base == null ? "" : base;
        }
        var sb = new StringBuilder();
        if (base != null && !base.isBlank()) {
            sb.append(base.strip());
        }
        appendSection(sb, null, def.systemPrompt());
        appendSection(sb, "Skills", def.atmosphereExtensions().get(SKILLS_MD));
        for (var skillPath : def.skillPaths()) {
            appendSection(sb, "Skill: " + skillName(skillPath), readFile(skillPath));
        }
        return sb.toString().strip();
    }

    private static void appendSection(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        if (title != null && !title.isBlank()) {
            sb.append("## ").append(title).append("\n\n");
        }
        sb.append(body.strip());
    }

    private static String skillName(Path skillFile) {
        var dir = skillFile.getParent();
        var name = dir != null ? dir.getFileName() : skillFile.getFileName();
        return name != null ? name.toString() : "skill";
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to read skill file {}: {}", path, e.getMessage());
            return "";
        }
    }

    // -- RUNTIME.md → AiConfig -------------------------------------------------

    /**
     * Parse {@code RUNTIME.md} and apply it to the process-wide {@link AiConfig}
     * singleton. Recognized keys: {@code model}, {@code mode}, {@code api-key}
     * (or {@code apiKey}), {@code base-url} (or {@code baseUrl}),
     * {@code temperature}, {@code max-tokens}, {@code top-p}, {@code stop}.
     * Unspecified keys inherit the current settings (or framework defaults).
     *
     * <p><strong>Process-global, last-write-wins.</strong> {@link AiConfig} is a
     * single framework-wide singleton — there is no per-agent settings override
     * today — so when several agents each ship a {@code RUNTIME.md}, the last
     * one loaded determines the model/mode for the whole process. This is
     * documented as a known limitation rather than advertised as per-agent
     * runtime config (Correctness Invariant #5, Runtime Truth).</p>
     *
     * @param def the loaded workspace definition, may be {@code null}
     * @return {@code true} when {@code RUNTIME.md} was present and applied
     */
    public static boolean applyRuntime(AgentDefinition def) {
        if (def == null) {
            return false;
        }
        var md = def.atmosphereExtensions().get(RUNTIME_MD);
        if (md == null || md.isBlank()) {
            return false;
        }
        var kv = parseKeyValues(md);
        if (kv.isEmpty()) {
            logger.warn("RUNTIME.md in workspace '{}' produced no recognized 'key: value' settings — "
                    + "runtime config NOT changed", def.name());
            return false;
        }
        var current = AiConfig.get();
        var mode = firstNonBlank(kv.get("mode"), current != null ? current.mode() : null, AiConfig.DEFAULT_MODE);
        var model = firstNonBlank(kv.get("model"), current != null ? current.model() : null, AiConfig.DEFAULT_MODEL);
        var apiKey = firstNonBlank(kv.get("api-key"), kv.get("apikey"),
                current != null ? current.apiKey() : null);
        var baseUrl = firstNonBlank(kv.get("base-url"), kv.get("baseurl"),
                current != null ? current.baseUrl() : null);

        // Generation knobs ride the sysprops AiConfig.configure() already
        // resolves, reusing its tested parsing + GenerationParams clamping
        // (boundary validation lives in GenerationParams, not here).
        applyGenerationSysprop(AiConfig.TEMPERATURE_PROPERTY, kv.get("temperature"));
        applyGenerationSysprop(AiConfig.MAX_TOKENS_PROPERTY, firstNonBlank(kv.get("max-tokens"), kv.get("maxtokens")));
        applyGenerationSysprop(AiConfig.TOP_P_PROPERTY, firstNonBlank(kv.get("top-p"), kv.get("topp")));
        applyGenerationSysprop(AiConfig.STOP_PROPERTY, kv.get("stop"));

        AiConfig.configure(mode, model, apiKey, baseUrl);
        logger.info("Applied RUNTIME.md from workspace '{}': mode={}, model={} (process-global)",
                def.name(), mode, model);
        return true;
    }

    private static void applyGenerationSysprop(String property, String value) {
        if (value != null && !value.isBlank()) {
            System.setProperty(property, value.trim());
        }
    }

    // -- PERMISSIONS.md → governance policies ---------------------------------

    /**
     * Parse {@code PERMISSIONS.md} into governance policies. Recognized
     * directives (bare or bulleted, one per line):
     * <ul>
     *   <li>{@code deny: <phrase>} / {@code deny-regex: <pattern>} →
     *       a {@code deny-list} policy</li>
     *   <li>{@code allow: <phrase>} / {@code allow-regex: <pattern>} →
     *       an {@code allow-list} policy</li>
     *   <li>{@code require-role: <role>} (or {@code role:}) →
     *       an {@code authorization} policy</li>
     * </ul>
     * Other lines are treated as prose and ignored. A non-blank file that
     * yields no policies is logged at WARN — it must not silently disable
     * governance (Correctness Invariant #4).
     *
     * @param def the loaded workspace definition, may be {@code null}
     * @return the parsed policies (never {@code null}; empty when none)
     */
    public static List<GovernancePolicy> permissionPolicies(AgentDefinition def) {
        if (def == null) {
            return List.of();
        }
        var md = def.atmosphereExtensions().get(PERMISSIONS_MD);
        if (md == null || md.isBlank()) {
            return List.of();
        }
        var denyPhrases = new ArrayList<String>();
        var denyRegex = new ArrayList<String>();
        var allowPhrases = new ArrayList<String>();
        var allowRegex = new ArrayList<String>();
        var roles = new ArrayList<String>();
        for (var raw : md.lines().toList()) {
            var line = stripBullet(raw).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            var idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            var key = line.substring(0, idx).strip().toLowerCase(Locale.ROOT);
            var value = line.substring(idx + 1).strip();
            if (value.isEmpty()) {
                continue;
            }
            switch (key) {
                case "deny" -> denyPhrases.add(value);
                case "deny-regex" -> denyRegex.add(value);
                case "allow" -> allowPhrases.add(value);
                case "allow-regex" -> allowRegex.add(value);
                case "require-role", "role" -> roles.add(value);
                default -> {
                    // prose / unrelated key — ignored
                }
            }
        }
        var source = "permissions:" + def.name();
        var registry = new PolicyRegistry();
        var policies = new ArrayList<GovernancePolicy>();
        if (!denyPhrases.isEmpty() || !denyRegex.isEmpty()) {
            policies.add(registry.build(new PolicyRegistry.PolicyDescriptor(
                    "workspace-deny-" + def.name(), "deny-list", "embedded", source,
                    Map.of("phrases", List.copyOf(denyPhrases), "regex", List.copyOf(denyRegex)))));
        }
        if (!allowPhrases.isEmpty() || !allowRegex.isEmpty()) {
            policies.add(registry.build(new PolicyRegistry.PolicyDescriptor(
                    "workspace-allow-" + def.name(), "allow-list", "embedded", source,
                    Map.of("phrases", List.copyOf(allowPhrases), "regex", List.copyOf(allowRegex)))));
        }
        if (!roles.isEmpty()) {
            policies.add(registry.build(new PolicyRegistry.PolicyDescriptor(
                    "workspace-authz-" + def.name(), "authorization", "embedded", source,
                    Map.of("required-roles", List.copyOf(roles)))));
        }
        if (policies.isEmpty()) {
            logger.warn("PERMISSIONS.md in workspace '{}' produced no policies — expected 'allow:', "
                    + "'deny:', 'deny-regex:', 'allow-regex:', or 'require-role:' directives. "
                    + "Governance NOT changed for this agent.", def.name());
        }
        return List.copyOf(policies);
    }

    /**
     * Merge the given policies into the framework's
     * {@link GovernancePolicy#POLICIES_PROPERTY} bag, deduplicated by
     * {@link GovernancePolicy#name()} so a repeat load cannot double-install.
     * {@link org.atmosphere.ai.governance.GovernancePolicies#installed} then
     * surfaces them to every dispatch path (Mode Parity #7).
     *
     * @param framework the running framework (may be {@code null})
     * @param policies  the policies to install (may be empty)
     */
    public static void installPolicies(AtmosphereFramework framework, List<GovernancePolicy> policies) {
        if (framework == null || policies == null || policies.isEmpty()) {
            return;
        }
        var cfg = framework.getAtmosphereConfig();
        if (cfg == null) {
            return;
        }
        var props = cfg.properties();
        var merged = new ArrayList<GovernancePolicy>();
        if (props.get(GovernancePolicy.POLICIES_PROPERTY) instanceof List<?> existing) {
            for (var p : existing) {
                if (p instanceof GovernancePolicy gp) {
                    merged.add(gp);
                }
            }
        }
        var names = new HashSet<String>();
        for (var p : merged) {
            names.add(p.name());
        }
        for (var p : policies) {
            if (names.add(p.name())) {
                merged.add(p);
            }
        }
        props.put(GovernancePolicy.POLICIES_PROPERTY, List.copyOf(merged));
        logger.info("Installed {} workspace governance policy(ies): {}",
                policies.size(), policies.stream().map(GovernancePolicy::name).toList());
    }

    // -- CHANNELS.md / MCP.md parsers (side-effect-free; consumed by processors) --

    /**
     * Parse {@code CHANNELS.md} into the set of channel-type names the agent
     * should answer on (e.g. {@code web}, {@code slack}, {@code telegram}).
     * Only the channel <em>names</em> are carried here — credentials always come
     * from the channel module's own configuration, never from the workspace file
     * (Correctness Invariant #6: a parsed file must not stand up an
     * unauthenticated channel).
     *
     * @param def the loaded workspace definition, may be {@code null}
     * @return the channel names in first-seen order (never {@code null})
     */
    public static List<String> channelNames(AgentDefinition def) {
        if (def == null) {
            return List.of();
        }
        var md = def.atmosphereExtensions().get(CHANNELS_MD);
        if (md == null || md.isBlank()) {
            return List.of();
        }
        var seen = new java.util.LinkedHashSet<String>();
        for (var raw : md.lines().toList()) {
            var line = stripBullet(raw).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // Accept "slack" or "slack: ..." — the token before an optional colon.
            var idx = line.indexOf(':');
            var token = (idx > 0 ? line.substring(0, idx) : line).strip().toLowerCase(Locale.ROOT);
            if (!token.isEmpty() && token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')) {
                seen.add(token);
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Parse {@code MCP.md} into the list of outbound MCP server URIs the agent
     * should connect to for remote tools. Entries are {@code name: <url>} lines;
     * entries without an {@code http}/{@code https} URL (e.g. the documentary
     * {@code github: credential-store-backed}) are skipped with an info log,
     * since the convenience connect path needs a real endpoint. URIs are
     * validated at the boundary (Correctness Invariant #4).
     *
     * @param def the loaded workspace definition, may be {@code null}
     * @return the parsed (name, uri) pairs in first-seen order (never {@code null})
     */
    public static List<McpServerRef> mcpServerUris(AgentDefinition def) {
        if (def == null) {
            return List.of();
        }
        var md = def.atmosphereExtensions().get(MCP_MD);
        if (md == null || md.isBlank()) {
            return List.of();
        }
        var refs = new ArrayList<McpServerRef>();
        var names = new HashSet<String>();
        for (var raw : md.lines().toList()) {
            var line = stripBullet(raw).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            var idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            var name = line.substring(0, idx).strip();
            var value = line.substring(idx + 1).strip();
            if (name.isEmpty() || value.isEmpty()) {
                continue;
            }
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                logger.info("MCP.md entry '{}' in workspace '{}' has no http(s) endpoint ('{}') — skipping",
                        name, def.name(), value);
                continue;
            }
            try {
                var uri = java.net.URI.create(value);
                if (names.add(name)) {
                    refs.add(new McpServerRef(name, uri));
                }
            } catch (IllegalArgumentException e) {
                logger.warn("MCP.md entry '{}' in workspace '{}' has an invalid URL '{}' — skipping: {}",
                        name, def.name(), value, e.getMessage());
            }
        }
        return List.copyOf(refs);
    }

    /** A named outbound MCP server reference parsed from {@code MCP.md}. */
    public record McpServerRef(String name, java.net.URI uri) {
    }

    // -- shared parsing helpers -----------------------------------------------

    private static Map<String, String> parseKeyValues(String md) {
        var map = new LinkedHashMap<String, String>();
        for (var raw : md.lines().toList()) {
            var line = stripBullet(raw).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            var idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            var key = line.substring(0, idx).strip().toLowerCase(Locale.ROOT);
            var value = line.substring(idx + 1).strip();
            if (!value.isEmpty()) {
                map.putIfAbsent(key, value);
            }
        }
        return map;
    }

    private static String stripBullet(String line) {
        var s = line.strip();
        if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("+ ")) {
            return s.substring(2);
        }
        return line;
    }

    private static String firstNonBlank(String... values) {
        for (var v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
