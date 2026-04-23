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

import org.atmosphere.ai.AiRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Deny requests missing one or more required metadata keys on
 * {@link AiRequest#metadata()}. Enforces the "every request is attributable"
 * invariant operators need in multi-tenant deployments — no request should
 * ever reach the LLM without a tenant-id, trace-id, or whatever tag the
 * compliance story depends on.
 *
 * <p>Evaluates only at {@link PolicyContext.Phase#PRE_ADMISSION} — by the
 * time the response is streaming, the request's metadata is already
 * baked into the audit trail.</p>
 *
 * <p>A key is considered "present" when it maps to a non-null value whose
 * string form is non-blank. Null, empty string, and whitespace all count
 * as missing — this is the shape operators actually expect.</p>
 */
public final class MetadataPresencePolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final List<String> requiredKeys;

    public MetadataPresencePolicy(String name, String... requiredKeys) {
        this(name, "code:" + MetadataPresencePolicy.class.getName(), "1",
                Arrays.asList(requiredKeys));
    }

    public MetadataPresencePolicy(String name, String source, String version,
                                  List<String> requiredKeys) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (requiredKeys == null || requiredKeys.isEmpty()) {
            throw new IllegalArgumentException("requiredKeys must be non-empty");
        }
        for (var key : requiredKeys) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("requiredKeys must not contain blank entries");
            }
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.requiredKeys = List.copyOf(requiredKeys);
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    public List<String> requiredKeys() { return requiredKeys; }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return PolicyDecision.admit();
        }
        var metadata = context.request() == null ? null : context.request().metadata();
        var missing = firstMissing(metadata);
        if (missing == null) {
            return PolicyDecision.admit();
        }
        return PolicyDecision.deny("required metadata key '" + missing + "' is missing");
    }

    /** First required key that's missing (or blank) from the metadata map, or null when all present. */
    String firstMissing(Map<String, Object> metadata) {
        for (var key : requiredKeys) {
            if (metadata == null) {
                return key;
            }
            var value = metadata.get(key);
            if (value == null) {
                return key;
            }
            if (value.toString().isBlank()) {
                return key;
            }
        }
        return null;
    }
}
