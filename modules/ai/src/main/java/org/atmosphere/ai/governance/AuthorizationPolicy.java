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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Role-based admission policy. Denies any pre-admission request whose
 * subject doesn't hold ALL of the configured {@code requiredRoles}.
 * Pluggable role resolver — the default reads a {@code roles} metadata
 * key from {@link AiRequest#metadata()} (comma-separated or list);
 * applications that already populate Spring Security / JWT / OAuth
 * context inject their own {@link Function}.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@code requiredRoles = {"admin"}} — subject must have admin</li>
 *   <li>{@code requiredRoles = {"admin", "billing"}} — subject must have
 *       both (conjunction). Use composition with {@link PolicyRing} for
 *       disjunction.</li>
 *   <li>Subject missing roles / null metadata → deny (fail-closed,
 *       Correctness Invariant #6)</li>
 * </ul>
 *
 * <p>Post-response phase always admits — authorization is an admission
 * concern. A request that was admitted can't retroactively lose
 * authorization between admit and response completion within the same
 * turn.</p>
 */
public final class AuthorizationPolicy implements GovernancePolicy {

    /** Default metadata key carrying the subject's roles. */
    public static final String DEFAULT_ROLES_KEY = "roles";

    private final String name;
    private final String source;
    private final String version;
    private final Set<String> requiredRoles;
    private final Function<AiRequest, Set<String>> roleResolver;

    public AuthorizationPolicy(String name, Collection<String> requiredRoles) {
        this(name, "code:" + AuthorizationPolicy.class.getName(), "1",
                requiredRoles, AuthorizationPolicy::defaultRoleResolver);
    }

    public AuthorizationPolicy(String name, String source, String version,
                               Collection<String> requiredRoles,
                               Function<AiRequest, Set<String>> roleResolver) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (requiredRoles == null || requiredRoles.isEmpty()) {
            throw new IllegalArgumentException("requiredRoles must be non-empty");
        }
        var copy = new LinkedHashSet<String>();
        for (var role : requiredRoles) {
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("requiredRoles must not contain blank entries");
            }
            copy.add(role);
        }
        this.name = name;
        this.source = source;
        this.version = version;
        this.requiredRoles = Collections.unmodifiableSet(copy);
        this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver");
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    public Set<String> requiredRoles() { return requiredRoles; }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        if (context.phase() == PolicyContext.Phase.POST_RESPONSE) {
            return PolicyDecision.admit();
        }
        var heldRoles = roleResolver.apply(context.request());
        if (heldRoles == null || heldRoles.isEmpty()) {
            return PolicyDecision.deny("no roles claimed by subject; required: " + requiredRoles);
        }
        for (var required : requiredRoles) {
            if (!heldRoles.contains(required)) {
                return PolicyDecision.deny(
                        "missing required role '" + required + "' (subject holds: " + heldRoles + ")");
            }
        }
        return PolicyDecision.admit();
    }

    /**
     * Default role resolver — reads {@link AiRequest#metadata()} for the
     * {@link #DEFAULT_ROLES_KEY}. Accepts {@code String} (comma-separated),
     * {@code Collection<String>}, or {@code null}.
     */
    public static Set<String> defaultRoleResolver(AiRequest request) {
        if (request == null || request.metadata() == null) {
            return Set.of();
        }
        var raw = request.metadata().get(DEFAULT_ROLES_KEY);
        if (raw == null) return Set.of();
        if (raw instanceof Collection<?> collection) {
            var out = new LinkedHashSet<String>();
            for (var item : collection) {
                if (item == null) continue;
                var s = item.toString().trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        var text = raw.toString();
        if (text.isBlank()) return Set.of();
        var out = new LinkedHashSet<String>();
        for (var token : text.split(",")) {
            var trimmed = token.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }
}
