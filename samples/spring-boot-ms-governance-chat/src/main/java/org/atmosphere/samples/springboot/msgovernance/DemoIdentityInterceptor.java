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
package org.atmosphere.samples.springboot.msgovernance;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stamps the demo identity a production deployment would get from its auth
 * layer (JWT claims, session attributes) onto every chat turn:
 * {@code tenant-id} and {@code roles} metadata.
 *
 * <p>Why this exists: the policy plane in {@link PoliciesConfig} requires a
 * tenant id ({@code require-tenant-id}) and the {@code support-chat-user}
 * role ({@code require-support-role}) on every request — the explicit shape
 * a multi-tenant SaaS support bot needs. The bundled console client carries
 * no identity of its own, so without this interceptor every out-of-box chat
 * turn died at admission with "required metadata key 'tenant-id' is missing"
 * and none of the YAML rules in the README's "Try each rule" table were ever
 * reachable (the 4.0.60 release-gate regression).</p>
 *
 * <p>Only ABSENT keys are stamped — a caller that provides real identity
 * metadata (the {@code /api/admin/governance/check} endpoint, a fronting
 * auth filter, a test) always wins. Production deployments delete this
 * interceptor and stamp both keys from their identity provider instead.</p>
 */
public final class DemoIdentityInterceptor implements AiInterceptor {

    /** Tenant every unauthenticated demo turn is attributed to. */
    public static final String DEMO_TENANT = "demo-tenant";

    /** Role satisfying the {@code require-support-role} policy. */
    public static final String DEMO_ROLES = "support-chat-user";

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        if (request == null) {
            return null;
        }
        var current = request.metadata();
        if (current != null && current.containsKey("tenant-id") && current.containsKey("roles")) {
            return request;
        }
        var metadata = new LinkedHashMap<String, Object>(current == null ? Map.of() : current);
        metadata.putIfAbsent("tenant-id", DEMO_TENANT);
        metadata.putIfAbsent("roles", DEMO_ROLES);
        return request.withMetadata(Map.copyOf(metadata));
    }
}
