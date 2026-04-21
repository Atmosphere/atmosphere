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
import java.util.Map;

/**
 * Default {@link PolicyParser} — reads a YAML document into
 * {@link GovernancePolicy} instances via {@link PolicyRegistry}.
 *
 * <h2>Schema</h2>
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
 * <p>{@code version:} at the document root is advisory for now — the parser
 * records it on every {@link org.atmosphere.ai.governance.PolicyRegistry.PolicyDescriptor}
 * as a fallback when the individual policy entry omits {@code version:}.</p>
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
        var policiesRaw = root.get("policies");
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
