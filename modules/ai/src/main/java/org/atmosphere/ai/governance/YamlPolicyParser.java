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
package org.atmosphere.ai.governance;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Default {@link PolicyParser} — reads a YAML document into
 * {@link GovernancePolicy} instances via {@link PolicyRegistry}.
 *
 * <h2>Atmosphere-native schema (type-dispatch)</h2>
 * <pre>{@code
 * version: "1.0"
 * policies:
 *   - name: my-pii
 *     type: pii-redaction
 *     version: "1.0"
 *     config:
 *       mode: redact           # redact | block
 *   - name: my-budget
 *     type: cost-ceiling
 *     config:
 *       budget-usd: 100.0
 *   - name: my-drift
 *     type: output-length-zscore
 *     config:
 *       window-size: 50
 *       z-threshold: 3.0
 *       min-samples: 10
 * }</pre>
 *
 * <h2>Microsoft Agent OS schema (rules-over-context)</h2>
 * The parser <b>auto-detects</b> the Microsoft Agent Governance Toolkit YAML
 * shape ({@code rules:} sequence at the document root) and produces a single
 * {@link MsAgentOsPolicy} that preserves MS's first-match-by-priority semantic.
 * Operators: {@code eq}, {@code ne}, {@code gt}, {@code lt}, {@code gte},
 * {@code lte}, {@code in}, {@code contains}, {@code matches} (regex). Actions:
 * {@code allow}, {@code deny}, {@code audit}, {@code block}.
 * <pre>{@code
 * version: "1.0"
 * name: production-policy
 * description: Company-wide policy
 * rules:
 *   - name: block-delete-database
 *     condition:
 *       field: message
 *       operator: contains
 *       value: DROP TABLE
 *     action: deny
 *     priority: 100
 *     message: "SQL drop statements are never allowed"
 * defaults:
 *   action: allow
 * }</pre>
 *
 * <p>{@code version:} at the document root is advisory for the Atmosphere schema —
 * the parser records it on every {@link org.atmosphere.ai.governance.PolicyRegistry.PolicyDescriptor}
 * as a fallback when the individual policy entry omits {@code version:}. For MS
 * documents the top-level {@code version:} becomes the synthetic policy's
 * {@link GovernancePolicy#version()}.</p>
 *
 * <p>Uses {@link SafeConstructor} — no arbitrary class instantiation, no URL
 * loading. Input is treated as untrusted config data.</p>
 */
public final class YamlPolicyParser implements PolicyParser {

    private final PolicyRegistry registry;

    public YamlPolicyParser() {
        this(new PolicyRegistry());
    }

    public YamlPolicyParser(PolicyRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
    }

    public PolicyRegistry registry() {
        return registry;
    }

    @Override
    public String format() {
        return "yaml";
    }

    @Override
    public List<GovernancePolicy> parse(String source, InputStream in) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("input stream must not be null");
        }
        var effectiveSource = (source == null || source.isBlank()) ? "yaml:unknown" : source;

        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(options));

        Object document;
        try {
            document = yaml.load(in);
        } catch (RuntimeException e) {
            throw new IOException("failed to parse YAML from " + effectiveSource, e);
        }
        if (document == null) {
            return List.of();
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw new IOException("expected a mapping at YAML document root in "
                    + effectiveSource + ", got: " + document.getClass().getSimpleName());
        }

        var defaultVersion = asString(root.get("version"), "embedded");

        // Auto-detect MS Agent Governance Toolkit schema — root has `rules:`
        // instead of `policies:`. The two schemas are mutually exclusive by
        // design (MS uses rules-over-context, Atmosphere uses type-dispatch);
        // documents that carry both raise an error.
        var rulesRaw = root.get("rules");
        var policiesRaw = root.get("policies");
        if (rulesRaw != null && policiesRaw != null) {
            throw new IOException("YAML document at " + effectiveSource
                    + " has both 'rules' and 'policies' keys — pick one schema");
        }
        if (rulesRaw != null) {
            return List.of(parseMsAgentOsDocument(effectiveSource, root, rulesRaw));
        }
        if (policiesRaw == null) {
            return List.of();
        }
        if (!(policiesRaw instanceof List<?> list)) {
            throw new IOException("expected 'policies' to be a sequence in " + effectiveSource);
        }

        var result = new ArrayList<GovernancePolicy>(list.size());
        for (int i = 0; i < list.size(); i++) {
            var entry = list.get(i);
            if (!(entry instanceof Map<?, ?> entryMap)) {
                throw new IOException("expected mapping in policies[" + i
                        + "] of " + effectiveSource + ", got: "
                        + (entry == null ? "null" : entry.getClass().getSimpleName()));
            }
            var descriptor = new PolicyRegistry.PolicyDescriptor(
                    asString(entryMap.get("name"), ""),
                    asString(entryMap.get("type"), ""),
                    asString(entryMap.get("version"), defaultVersion),
                    effectiveSource,
                    asStringKeyedMap(entryMap.get("config")));
            try {
                result.add(registry.build(descriptor));
            } catch (IllegalArgumentException e) {
                throw new IOException("failed to build policies[" + i + "] ('"
                        + descriptor.name() + "') in " + effectiveSource + ": " + e.getMessage(), e);
            }
        }
        return List.copyOf(result);
    }

    // --- Microsoft Agent Governance Toolkit schema ---------------------------

    private GovernancePolicy parseMsAgentOsDocument(String source,
                                                     Map<?, ?> root,
                                                     Object rulesRaw) throws IOException {
        if (!(rulesRaw instanceof List<?> ruleList)) {
            throw new IOException("expected 'rules' to be a sequence in " + source);
        }
        var docName = asString(root.get("name"), "unnamed");
        var docVersion = asString(root.get("version"), "1.0");

        var rules = new ArrayList<MsAgentOsPolicy.Rule>(ruleList.size());
        for (int i = 0; i < ruleList.size(); i++) {
            var entry = ruleList.get(i);
            if (!(entry instanceof Map<?, ?> ruleMap)) {
                throw new IOException("expected mapping in rules[" + i + "] of " + source
                        + ", got: " + (entry == null ? "null" : entry.getClass().getSimpleName()));
            }
            rules.add(parseMsRule(source, i, ruleMap));
        }

        var defaultAction = parseMsAction(
                asString(asStringKeyedMap(root.get("defaults")).get("action"), "allow"),
                source + ".defaults");

        return new MsAgentOsPolicy(docName, source, docVersion, rules, defaultAction);
    }

    private static MsAgentOsPolicy.Rule parseMsRule(String source, int index, Map<?, ?> ruleMap)
            throws IOException {
        var name = asString(ruleMap.get("name"), "");
        if (name.isBlank()) {
            throw new IOException("rules[" + index + "] missing 'name' in " + source);
        }
        var conditionRaw = ruleMap.get("condition");
        if (!(conditionRaw instanceof Map<?, ?> conditionMap)) {
            throw new IOException("rules[" + index + "] ('" + name
                    + "') missing mapping 'condition' in " + source);
        }
        var field = asString(conditionMap.get("field"), "");
        if (field.isBlank()) {
            throw new IOException("rules[" + index + "].condition missing 'field' in " + source);
        }
        var operator = parseMsOperator(
                asString(conditionMap.get("operator"), ""),
                source + ".rules[" + index + "].condition");
        var value = conditionMap.get("value");
        var action = parseMsAction(asString(ruleMap.get("action"), ""),
                source + ".rules[" + index + "] ('" + name + "')");
        var priority = toInt(ruleMap.get("priority"), 0);
        var message = asString(ruleMap.get("message"), "");

        Pattern compiled = null;
        if (operator == MsAgentOsPolicy.Operator.MATCHES) {
            try {
                compiled = Pattern.compile(value == null ? "" : value.toString());
            } catch (PatternSyntaxException e) {
                throw new IOException("rules[" + index + "] ('" + name + "') has invalid regex '"
                        + value + "' in " + source + ": " + e.getMessage(), e);
            }
        }
        return new MsAgentOsPolicy.Rule(name, field, operator, value,
                priority, message, action, compiled);
    }

    private static MsAgentOsPolicy.Operator parseMsOperator(String raw, String path)
            throws IOException {
        var normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "eq" -> MsAgentOsPolicy.Operator.EQ;
            case "ne" -> MsAgentOsPolicy.Operator.NE;
            case "gt" -> MsAgentOsPolicy.Operator.GT;
            case "lt" -> MsAgentOsPolicy.Operator.LT;
            case "gte" -> MsAgentOsPolicy.Operator.GTE;
            case "lte" -> MsAgentOsPolicy.Operator.LTE;
            case "in" -> MsAgentOsPolicy.Operator.IN;
            case "contains" -> MsAgentOsPolicy.Operator.CONTAINS;
            case "matches" -> MsAgentOsPolicy.Operator.MATCHES;
            default -> throw new IOException("unknown operator '" + raw + "' at " + path
                    + " (supported: eq, ne, gt, lt, gte, lte, in, contains, matches)");
        };
    }

    private static MsAgentOsPolicy.Action parseMsAction(String raw, String path) throws IOException {
        var normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "allow", "" -> MsAgentOsPolicy.Action.ALLOW;
            case "deny" -> MsAgentOsPolicy.Action.DENY;
            case "audit" -> MsAgentOsPolicy.Action.AUDIT;
            case "block" -> MsAgentOsPolicy.Action.BLOCK;
            default -> throw new IOException("unknown action '" + raw + "' at " + path
                    + " (supported: allow, deny, audit, block)");
        };
    }

    private static int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ---------------------------------------------------------------------

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }

    private static Map<String, Object> asStringKeyedMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "expected 'config' to be a mapping, got: " + value.getClass().getSimpleName());
        }
        var copy = new java.util.HashMap<String, Object>(raw.size());
        for (var entry : raw.entrySet()) {
            var key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException("'config' mapping contains null key");
            }
            copy.put(key.toString(), entry.getValue());
        }
        return Map.copyOf(copy);
    }
}
