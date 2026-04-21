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

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.guardrails.CostCeilingGuardrail;
import org.atmosphere.ai.guardrails.OutputLengthZScoreGuardrail;
import org.atmosphere.ai.guardrails.PiiRedactionGuardrail;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps YAML policy {@code type:} names to factory functions that build the
 * corresponding {@link GovernancePolicy}. The registry is populated at
 * construction with Atmosphere's built-in guardrail types; applications extend
 * it with custom types via {@link #register(String, PolicyFactory)}.
 *
 * <p>Built-in type names (match the gist's Phase A deliverables):</p>
 * <ul>
 *   <li>{@code pii-redaction} → {@link PiiRedactionGuardrail}</li>
 *   <li>{@code cost-ceiling} → {@link CostCeilingGuardrail}</li>
 *   <li>{@code output-length-zscore} → {@link OutputLengthZScoreGuardrail}</li>
 * </ul>
 *
 * <p>Each factory receives a {@link PolicyDescriptor} — the parsed YAML entry's
 * {@code name}, {@code version}, and {@code config} block — and returns a
 * {@link GovernancePolicy} ready to install on the pipeline.</p>
 */
public final class PolicyRegistry {

    /**
     * Factory signature. {@link PolicyDescriptor#name()} and
     * {@link PolicyDescriptor#version()} become the returned policy's identity;
     * {@link PolicyDescriptor#config()} carries type-specific configuration.
     */
    @FunctionalInterface
    public interface PolicyFactory {
        GovernancePolicy create(PolicyDescriptor descriptor);
    }

    /**
     * Parsed YAML entry: the shared identity plus a type-specific
     * {@code config} block. {@link #config()} is never {@code null}.
     */
    public record PolicyDescriptor(String name, String type, String version,
                                    String source, Map<String, Object> config) {
        public PolicyDescriptor {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("policy 'name' must not be blank");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("policy 'type' must not be blank");
            }
            if (version == null || version.isBlank()) {
                version = "embedded";
            }
            if (source == null || source.isBlank()) {
                source = "yaml:unknown";
            }
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    private final ConcurrentHashMap<String, PolicyFactory> factories = new ConcurrentHashMap<>();

    public PolicyRegistry() {
        register("pii-redaction", PolicyRegistry::buildPiiRedaction);
        register("cost-ceiling", PolicyRegistry::buildCostCeiling);
        register("output-length-zscore", PolicyRegistry::buildOutputLengthZScore);
    }

    /** Register a custom factory, replacing any previous entry for this type. */
    public void register(String type, PolicyFactory factory) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        factories.put(type.toLowerCase(java.util.Locale.ROOT), factory);
    }

    /** True when this type has a registered factory. */
    public boolean has(String type) {
        return type != null && factories.containsKey(type.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Build a policy for the given descriptor. Throws
     * {@link IllegalArgumentException} when the type is unknown — fail-closed
     * at parse time rather than silently dropping the entry.
     */
    public GovernancePolicy build(PolicyDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null");
        }
        var factory = factories.get(descriptor.type().toLowerCase(java.util.Locale.ROOT));
        if (factory == null) {
            throw new IllegalArgumentException("unknown policy type '" + descriptor.type()
                    + "' (known: " + factories.keySet() + ")");
        }
        return factory.create(descriptor);
    }

    // --- built-in factories -------------------------------------------------

    private static GovernancePolicy buildPiiRedaction(PolicyDescriptor d) {
        var config = d.config();
        var mode = asString(config.get("mode"), "redact");
        var guardrail = "block".equalsIgnoreCase(mode)
                ? new PiiRedactionGuardrail().blocking()
                : new PiiRedactionGuardrail();
        return wrap(guardrail, d);
    }

    private static GovernancePolicy buildCostCeiling(PolicyDescriptor d) {
        var config = d.config();
        var budget = asDouble(config.get("budget-usd"), 0.0);
        return wrap(new CostCeilingGuardrail(budget), d);
    }

    private static GovernancePolicy buildOutputLengthZScore(PolicyDescriptor d) {
        var config = d.config();
        var windowSize = asInt(config.get("window-size"), 50);
        var zThreshold = asDouble(config.get("z-threshold"), 3.0);
        var minSamples = asInt(config.get("min-samples"), 10);
        return wrap(new OutputLengthZScoreGuardrail(windowSize, zThreshold, minSamples), d);
    }

    private static GovernancePolicy wrap(AiGuardrail guardrail, PolicyDescriptor d) {
        return new GuardrailAsPolicy(guardrail, d.name(), d.source(), d.version());
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
    }

    private static double asDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected numeric, got: " + value, e);
        }
    }

    private static int asInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected integer, got: " + value, e);
        }
    }
}
