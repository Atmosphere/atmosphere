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
package org.atmosphere.ai.policy.rego;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link GovernancePolicy} backed by a Rego module. Evaluation delegates
 * to a {@link RegoEvaluator}; default is {@link OpaSubprocessEvaluator}.
 *
 * <p>The Atmosphere request is flattened into the Rego {@code input}
 * document — top-level keys map to {@link org.atmosphere.ai.AiRequest}
 * fields so operators can write policies like:</p>
 * <pre>{@code
 * package atmosphere.governance
 * default allow = false
 * allow {
 *     input.agent_id == "billing-agent"
 *     input.message != ""
 * }
 * }</pre>
 *
 * <p>A {@code {"allow": false, "reason": "<msg>"}} or
 * {@code "reason": "<msg>"} rule emits a {@link PolicyDecision.Deny} with
 * that reason; a boolean {@code true/false} emits Admit/Deny without a
 * reason. Fail-closed on evaluator error.</p>
 */
public final class RegoPolicy implements GovernancePolicy {

    private final String name;
    private final String source;
    private final String version;
    private final String regoSource;
    private final String query;
    private final RegoEvaluator evaluator;

    public RegoPolicy(String name,
                      String source,
                      String version,
                      String regoSource,
                      String query,
                      RegoEvaluator evaluator) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (regoSource == null || regoSource.isBlank()) {
            throw new IllegalArgumentException("regoSource must not be blank");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (evaluator == null) {
            throw new IllegalArgumentException("evaluator must not be null");
        }
        this.name = name;
        this.source = source == null ? "" : source;
        this.version = version == null ? "1.0" : version;
        this.regoSource = regoSource;
        this.query = query;
        this.evaluator = evaluator;
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var input = flatten(context);
        RegoEvaluator.Result result;
        try {
            result = evaluator.evaluate(regoSource, query, input);
        } catch (RuntimeException e) {
            return PolicyDecision.deny("rego evaluator threw: " + e.getMessage());
        }
        if (result.allowed()) {
            return PolicyDecision.admit();
        }
        var reason = result.reason().isEmpty()
                ? "rego policy denied"
                : result.reason();
        return PolicyDecision.deny(reason);
    }

    private static Map<String, Object> flatten(PolicyContext context) {
        var map = new LinkedHashMap<String, Object>();
        map.put("phase", context.phase() == PolicyContext.Phase.PRE_ADMISSION
                ? "pre_admission" : "post_response");
        var request = context.request();
        if (request != null) {
            put(map, "message", request.message());
            put(map, "model", request.model());
            put(map, "user_id", request.userId());
            put(map, "session_id", request.sessionId());
            put(map, "agent_id", request.agentId());
            put(map, "conversation_id", request.conversationId());
            if (request.metadata() != null) {
                for (var entry : request.metadata().entrySet()) {
                    if (entry.getKey() == null) continue;
                    map.putIfAbsent(entry.getKey(), coerce(entry.getValue()));
                }
            }
        }
        if (!context.accumulatedResponse().isEmpty()) {
            map.put("response", context.accumulatedResponse());
        }
        return map;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
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
