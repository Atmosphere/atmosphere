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
package org.atmosphere.ai.policy.cedar;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link GovernancePolicy} backed by a Cedar policy module. Evaluation
 * delegates to a {@link CedarAuthorizer}; default is
 * {@link CedarCliAuthorizer}.
 *
 * <h2>Request mapping</h2>
 * <table>
 *   <tr><th>Atmosphere</th><th>Cedar</th></tr>
 *   <tr><td>{@code userId}</td>    <td>{@code principal = User::"<userId>"}</td></tr>
 *   <tr><td>(fixed)</td>           <td>{@code action = Action::"invoke"}</td></tr>
 *   <tr><td>{@code agentId}</td>   <td>{@code resource = Agent::"<agentId>"}</td></tr>
 *   <tr><td>metadata</td>          <td>context record</td></tr>
 * </table>
 *
 * <p>Operators with different entity schemas subclass {@link CedarPolicy}
 * and override {@link #buildContext}, {@link #principalOf}, etc.</p>
 */
public class CedarPolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final String cedarSource;
    private final CedarAuthorizer authorizer;

    public CedarPolicy(String name,
                       String source,
                       String version,
                       String cedarSource,
                       CedarAuthorizer authorizer) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (cedarSource == null || cedarSource.isBlank()) {
            throw new IllegalArgumentException("cedarSource must not be blank");
        }
        if (authorizer == null) {
            throw new IllegalArgumentException("authorizer must not be null");
        }
        this.name = name;
        this.source = source == null ? "" : source;
        this.version = version == null ? "1.0" : version;
        this.cedarSource = cedarSource;
        this.authorizer = authorizer;
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var principal = principalOf(context);
        var action = actionOf(context);
        var resource = resourceOf(context);
        var ctx = buildContext(context);
        CedarAuthorizer.Result result;
        try {
            result = authorizer.authorize(cedarSource, principal, action, resource, ctx);
        } catch (RuntimeException e) {
            return PolicyDecision.deny("cedar authorizer threw: " + e.getMessage());
        }
        if (result.allowed()) {
            return PolicyDecision.admit();
        }
        var reason = result.reason().isEmpty() ? "cedar policy denied" : result.reason();
        return PolicyDecision.deny(reason);
    }

    /** Entity reference for Cedar's {@code principal}. Default {@code User::"<userId>"}. */
    protected String principalOf(PolicyContext context) {
        var userId = context.request() != null ? context.request().userId() : null;
        return "User::\"" + (userId == null ? "anonymous" : userId) + "\"";
    }

    /** Entity reference for Cedar's {@code action}. Default {@code Action::"invoke"}. */
    protected String actionOf(PolicyContext context) {
        return "Action::\"invoke\"";
    }

    /** Entity reference for Cedar's {@code resource}. Default {@code Agent::"<agentId>"}. */
    protected String resourceOf(PolicyContext context) {
        var agentId = context.request() != null ? context.request().agentId() : null;
        return "Agent::\"" + (agentId == null ? "unknown" : agentId) + "\"";
    }

    /** Cedar context record — flattened request metadata by default. */
    protected Map<String, Object> buildContext(PolicyContext context) {
        var map = new LinkedHashMap<String, Object>();
        map.put("phase", context.phase() == PolicyContext.Phase.PRE_ADMISSION
                ? "pre_admission" : "post_response");
        var request = context.request();
        if (request != null) {
            putIfNotNull(map, "message", request.message());
            putIfNotNull(map, "model", request.model());
            putIfNotNull(map, "session_id", request.sessionId());
            putIfNotNull(map, "conversation_id", request.conversationId());
            if (request.metadata() != null) {
                for (var entry : request.metadata().entrySet()) {
                    if (entry.getKey() == null) continue;
                    map.putIfAbsent(entry.getKey(), coerce(entry.getValue()));
                }
            }
        }
        return map;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static Object coerce(Object value) {
        if (value == null) return "";
        if (value instanceof String || value instanceof Boolean || value instanceof Number) {
            return value;
        }
        return value.toString();
    }
}
