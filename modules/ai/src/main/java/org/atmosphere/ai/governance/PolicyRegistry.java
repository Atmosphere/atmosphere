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
        register("deny-list", PolicyRegistry::buildDenyList);
        register("allow-list", PolicyRegistry::buildAllowList);
        register("message-length", PolicyRegistry::buildMessageLength);
        register("rate-limit", PolicyRegistry::buildRateLimit);
        register("concurrency-limit", PolicyRegistry::buildConcurrencyLimit);
        register("time-window", PolicyRegistry::buildTimeWindow);
        register("metadata-presence", PolicyRegistry::buildMetadataPresence);
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

    private static GovernancePolicy buildDenyList(PolicyDescriptor d) {
        var phrases = asStringList(d.config().get("phrases"));
        var regex = asStringList(d.config().get("regex"));
        if (phrases.isEmpty() && regex.isEmpty()) {
            throw new IllegalArgumentException(
                    "deny-list requires 'phrases' or 'regex' under config");
        }
        var patterns = new java.util.ArrayList<java.util.regex.Pattern>();
        for (var p : phrases) {
            patterns.add(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(p),
                    java.util.regex.Pattern.CASE_INSENSITIVE));
        }
        for (var r : regex) {
            patterns.add(java.util.regex.Pattern.compile(r,
                    java.util.regex.Pattern.CASE_INSENSITIVE));
        }
        return new DenyListPolicy(d.name(), d.source(), d.version(), patterns);
    }

    private static GovernancePolicy buildAllowList(PolicyDescriptor d) {
        var phrases = asStringList(d.config().get("phrases"));
        var regex = asStringList(d.config().get("regex"));
        if (phrases.isEmpty() && regex.isEmpty()) {
            throw new IllegalArgumentException(
                    "allow-list requires 'phrases' or 'regex' under config");
        }
        var patterns = new java.util.ArrayList<java.util.regex.Pattern>();
        for (var p : phrases) {
            patterns.add(java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(p),
                    java.util.regex.Pattern.CASE_INSENSITIVE));
        }
        for (var r : regex) {
            patterns.add(java.util.regex.Pattern.compile(r,
                    java.util.regex.Pattern.CASE_INSENSITIVE));
        }
        return new AllowListPolicy(d.name(), d.source(), d.version(), patterns);
    }

    private static GovernancePolicy buildMessageLength(PolicyDescriptor d) {
        var maxChars = asInt(d.config().get("max-chars"), 0);
        if (maxChars <= 0) {
            throw new IllegalArgumentException(
                    "message-length requires a positive 'max-chars' under config");
        }
        return new MessageLengthPolicy(d.name(), d.source(), d.version(), maxChars);
    }

    private static GovernancePolicy buildRateLimit(PolicyDescriptor d) {
        var limit = asInt(d.config().get("limit"), 0);
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "rate-limit requires a positive 'limit' under config");
        }
        var windowSeconds = asInt(d.config().get("window-seconds"), 0);
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException(
                    "rate-limit requires a positive 'window-seconds' under config");
        }
        return new RateLimitPolicy(d.name(), d.source(), d.version(),
                limit, java.time.Duration.ofSeconds(windowSeconds),
                java.time.Clock.systemUTC(),
                RateLimitPolicyDefaults.SUBJECT);
    }

    private static GovernancePolicy buildConcurrencyLimit(PolicyDescriptor d) {
        var max = asInt(d.config().get("max-concurrent"), 0);
        if (max <= 0) {
            throw new IllegalArgumentException(
                    "concurrency-limit requires a positive 'max-concurrent' under config");
        }
        return new ConcurrencyLimitPolicy(d.name(), d.source(), d.version(),
                max, RateLimitPolicyDefaults.SUBJECT);
    }

    private static GovernancePolicy buildTimeWindow(PolicyDescriptor d) {
        var config = d.config();
        var start = java.time.LocalTime.parse(asString(config.get("start"), "09:00"));
        var end = java.time.LocalTime.parse(asString(config.get("end"), "17:00"));
        var zone = java.time.ZoneId.of(asString(config.get("zone"), "UTC"));
        var days = parseDays(config.get("days"));
        return new TimeWindowPolicy(d.name(), d.source(), d.version(),
                start, end, days, zone, java.time.Clock.systemUTC());
    }

    private static java.util.Set<java.time.DayOfWeek> parseDays(Object value) {
        if (value == null) {
            // Default to Monday–Friday if unspecified.
            return java.util.EnumSet.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                    java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY,
                    java.time.DayOfWeek.FRIDAY);
        }
        if (!(value instanceof java.util.List<?> list)) {
            throw new IllegalArgumentException("time-window 'days' must be a YAML list");
        }
        var days = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
        for (var item : list) {
            if (item == null) continue;
            try {
                days.add(java.time.DayOfWeek.valueOf(item.toString().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown day-of-week: " + item, e);
            }
        }
        if (days.isEmpty()) {
            throw new IllegalArgumentException("time-window 'days' must be non-empty");
        }
        return days;
    }

    private static GovernancePolicy buildMetadataPresence(PolicyDescriptor d) {
        var keys = asStringList(d.config().get("required-keys"));
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "metadata-presence requires at least one entry under 'required-keys'");
        }
        return new MetadataPresencePolicy(d.name(), d.source(), d.version(), keys);
    }

    /**
     * Common default subject extractor for rate-limit / concurrency-limit —
     * matches the behavior documented on {@link RateLimitPolicy} /
     * {@link ConcurrencyLimitPolicy}. Shared from a helper so the two
     * factories stay in lockstep.
     */
    private static final class RateLimitPolicyDefaults {
        static final java.util.function.Function<org.atmosphere.ai.AiRequest, String> SUBJECT = req -> {
            if (req == null) return "anonymous";
            if (req.userId() != null && !req.userId().isBlank()) return "user:" + req.userId();
            if (req.sessionId() != null && !req.sessionId().isBlank()) {
                return "session:" + req.sessionId();
            }
            return "anonymous";
        };
    }

    private static java.util.List<String> asStringList(Object value) {
        if (value == null) return java.util.List.of();
        if (!(value instanceof java.util.List<?> list)) {
            throw new IllegalArgumentException("expected a YAML list, got: " + value);
        }
        var out = new java.util.ArrayList<String>(list.size());
        for (var item : list) {
            if (item == null) continue;
            var s = item.toString();
            if (!s.isBlank()) out.add(s);
        }
        return out;
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
