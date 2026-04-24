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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationPolicyTest {

    private static PolicyContext reqWith(Map<String, Object> metadata) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("msg", null, null, null, null, null, null, metadata, null),
                "");
    }

    @Test
    void admitsWhenAllRequiredRolesPresent() {
        var policy = new AuthorizationPolicy("admin-only", List.of("admin"));
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(reqWith(Map.of("roles", "admin"))));
    }

    @Test
    void deniesWhenSingleRoleMissing() {
        var policy = new AuthorizationPolicy("admin-only", List.of("admin"));
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("roles", "user"))));
        assertTrue(deny.reason().contains("admin"));
    }

    @Test
    void allRolesMustBePresentConjunctive() {
        var policy = new AuthorizationPolicy("dual", List.of("admin", "billing"));
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(reqWith(Map.of("roles", "admin,billing"))));
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("roles", "admin"))),
                "conjunction: admin alone is insufficient when billing is also required");
    }

    @Test
    void acceptsCollectionShapedRoles() {
        var policy = new AuthorizationPolicy("p", List.of("admin"));
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(reqWith(Map.of("roles", List.of("admin", "user")))));
    }

    @Test
    void deniesWhenMetadataMissing() {
        var policy = new AuthorizationPolicy("p", List.of("admin"));
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(reqWith(null)));
    }

    @Test
    void deniesWhenRolesKeyAbsent() {
        var policy = new AuthorizationPolicy("p", List.of("admin"));
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("tenant-id", "acme"))));
    }

    @Test
    void deniesWhenRolesValueIsBlank() {
        var policy = new AuthorizationPolicy("p", List.of("admin"));
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("roles", ""))));
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("roles", "   "))));
    }

    @Test
    void postResponseAlwaysAdmits() {
        var policy = new AuthorizationPolicy("p", List.of("admin"));
        var post = new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                new AiRequest("m", null, null, null, null, null, null, null, null),
                "response");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(post));
    }

    @Test
    void customRoleResolverIsHonored() {
        var policy = new AuthorizationPolicy("p", "code:test", "1",
                List.of("admin"),
                req -> Set.of("admin", "user"));

        // No metadata needed — the resolver returns a constant set.
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(reqWith(null)));
    }

    @Test
    void rolesTrimmedAndDeduplicatedInCommaSeparatedForm() {
        var policy = new AuthorizationPolicy("p", List.of("admin"));
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(reqWith(Map.of("roles", " admin , user ,"))),
                "leading/trailing spaces and empty segments must be tolerated");
    }

    @Test
    void rejectsEmptyRequiredRoles() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationPolicy("p", List.of()));
    }

    @Test
    void rejectsBlankRoleInRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationPolicy("p", List.of("admin", "")));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationPolicy("p", java.util.Arrays.asList("admin", (String) null)));
    }

    @Test
    void defaultRoleResolverHandlesNullRequest() {
        assertTrue(AuthorizationPolicy.defaultRoleResolver(null).isEmpty());
    }

    @Test
    void defaultRoleResolverHandlesNullValuesInCollection() {
        var metadata = new HashMap<String, Object>();
        var roles = new java.util.ArrayList<String>();
        roles.add("admin");
        roles.add(null);
        roles.add("user");
        metadata.put("roles", roles);
        var req = new AiRequest("m", null, null, null, null, null, null, metadata, null);

        var resolved = AuthorizationPolicy.defaultRoleResolver(req);
        assertTrue(resolved.contains("admin"));
        assertTrue(resolved.contains("user"));
    }
}
