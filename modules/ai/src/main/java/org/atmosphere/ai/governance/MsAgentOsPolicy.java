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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link GovernancePolicy} that implements Microsoft Agent Governance Toolkit
 * policy semantics — a priority-sorted rule matcher over a context map, with
 * the first matching rule determining the decision. Faithful port of the
 * algorithm in {@code agent_os/policies/evaluator.py::_match_condition} and
 * {@code PolicyEvaluator.evaluate}, verified against the April 2026 source.
 *
 * <h2>Why one policy per YAML document (not per rule)</h2>
 * MS rules are evaluated as a <em>set</em> — sorted by priority, first match
 * wins, falls through to {@code defaults.action} when nothing matches. Splitting
 * rules into separate {@link GovernancePolicy} instances would break that
 * semantic (our pipeline iterates policies in declaration order, each policy
 * decides in isolation). So each MS {@code PolicyDocument} lands as one
 * {@code MsAgentOsPolicy} that preserves the priority-sort + first-match
 * behaviour inside its {@link #evaluate} method.
 *
 * <h2>Context mapping</h2>
 * MS rules reference arbitrary context keys ({@code tool_name}, {@code token_count},
 * {@code user_id}, ...). Atmosphere's {@link PolicyContext} exposes the
 * following keys into the context map built for each evaluation:
 * <ul>
 *   <li>{@code phase} — {@code pre_admission} or {@code post_response}</li>
 *   <li>{@code message} — the user prompt</li>
 *   <li>{@code system_prompt}, {@code model}, {@code user_id}, {@code session_id},
 *       {@code agent_id}, {@code conversation_id}</li>
 *   <li>{@code response} — accumulated response text (post-response only)</li>
 *   <li>Every {@link AiRequest#metadata()} entry keyed by its exact string name</li>
 * </ul>
 *
 * <p>Rules that reference unknown context keys simply do not match — same
 * semantic as MS (missing field → no match).</p>
 */
public final class MsAgentOsPolicy implements GovernancePolicy {

    private static final Logger logger = LoggerFactory.getLogger(MsAgentOsPolicy.class);

    public enum Operator { EQ, NE, GT, LT, GTE, LTE, IN, MATCHES, CONTAINS }

    public enum Action { ALLOW, DENY, AUDIT, BLOCK }

    /** Single rule. {@link #compiledRegex} is set iff {@link #operator} is {@code MATCHES}. */
    public record Rule(String name, String field, Operator operator, Object value,
                       int priority, String message, Action action,
                       Pattern compiledRegex) {
        public Rule {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("rule name must not be blank");
            }
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("rule field must not be blank");
            }
            if (operator == null) {
                throw new IllegalArgumentException("operator must not be null");
            }
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            message = message == null ? "" : message;
        }
    }

    private final String name;
    private final String source;
    private final String version;
    /** Rules pre-sorted by priority descending (MS semantic). */
    private final List<Rule> rules;
    /** Fallback when no rule matches. */
    private final Action defaultAction;

    public MsAgentOsPolicy(String name, String source, String version,
                           List<Rule> rules, Action defaultAction) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("policy name must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("policy source must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("policy version must not be blank");
        }
        if (defaultAction == null) {
            throw new IllegalArgumentException("defaultAction must not be null");
        }
        var copy = new java.util.ArrayList<>(rules == null ? List.<Rule>of() : rules);
        copy.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        this.name = name;
        this.source = source;
        this.version = version;
        this.rules = List.copyOf(copy);
        this.defaultAction = defaultAction;
    }

    @Override public String name() { return name; }
    @Override public String source() { return source; }
    @Override public String version() { return version; }

    /** Pre-sorted rule list (priority descending) — exposed for introspection/testing. */
    public List<Rule> rules() {
        return rules;
    }

    public Action defaultAction() {
        return defaultAction;
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var ctx = buildContext(context);
        for (var rule : rules) {
            if (matches(rule, ctx)) {
                return decisionFor(rule);
            }
        }
        // No rule matched — fall back to defaults.action (same as MS).
        return switch (defaultAction) {
            case DENY, BLOCK -> PolicyDecision.deny(
                    "no rule matched; default action is " + defaultAction.name().toLowerCase(Locale.ROOT));
            case AUDIT -> {
                logger.info("Policy {} AUDIT (default): no rule matched", name);
                yield PolicyDecision.admit();
            }
            case ALLOW -> PolicyDecision.admit();
        };
    }

    private PolicyDecision decisionFor(Rule rule) {
        return switch (rule.action()) {
            case DENY, BLOCK -> PolicyDecision.deny(
                    rule.message().isEmpty()
                            ? "matched rule '" + rule.name() + "'"
                            : rule.message());
            case AUDIT -> {
                logger.info("Policy {} AUDIT: rule='{}' field='{}' op={} — {}",
                        name, rule.name(), rule.field(), rule.operator(),
                        rule.message().isEmpty() ? "matched" : rule.message());
                yield PolicyDecision.admit();
            }
            case ALLOW -> PolicyDecision.admit();
        };
    }

    /**
     * Build the evaluation context map from {@link PolicyContext}. Exposes the
     * request fields plus every metadata entry by its exact key.
     */
    private static Map<String, Object> buildContext(PolicyContext context) {
        var ctx = new java.util.HashMap<String, Object>();
        ctx.put("phase", context.phase() == PolicyContext.Phase.PRE_ADMISSION
                ? "pre_admission" : "post_response");
        var request = context.request();
        if (request != null) {
            putIfNotNull(ctx, "message", request.message());
            putIfNotNull(ctx, "system_prompt", request.systemPrompt());
            putIfNotNull(ctx, "model", request.model());
            putIfNotNull(ctx, "user_id", request.userId());
            putIfNotNull(ctx, "session_id", request.sessionId());
            putIfNotNull(ctx, "agent_id", request.agentId());
            putIfNotNull(ctx, "conversation_id", request.conversationId());
            if (request.metadata() != null) {
                for (var entry : request.metadata().entrySet()) {
                    ctx.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }
        if (!context.accumulatedResponse().isEmpty()) {
            ctx.put("response", context.accumulatedResponse());
        }
        return ctx;
    }

    private static void putIfNotNull(Map<String, Object> ctx, String key, Object value) {
        if (value != null) {
            ctx.put(key, value);
        }
    }

    /** Faithful port of {@code _match_condition} from agent-os policies/evaluator.py. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean matches(Rule rule, Map<String, Object> context) {
        var ctxValue = context.get(rule.field());
        if (ctxValue == null) {
            return false;
        }
        var target = rule.value();
        return switch (rule.operator()) {
            case EQ -> equalsLoose(ctxValue, target);
            case NE -> !equalsLoose(ctxValue, target);
            case GT -> compare(ctxValue, target) > 0;
            case LT -> compare(ctxValue, target) < 0;
            case GTE -> compare(ctxValue, target) >= 0;
            case LTE -> compare(ctxValue, target) <= 0;
            case IN -> target instanceof List<?> list && list.contains(ctxValue);
            case CONTAINS -> {
                // Python semantic: `target in ctx_value` — substring for strings,
                // element-membership for collections.
                if (ctxValue instanceof String s && target != null) {
                    yield s.contains(target.toString());
                }
                if (ctxValue instanceof java.util.Collection<?> c) {
                    yield c.contains(target);
                }
                yield false;
            }
            case MATCHES -> {
                var regex = rule.compiledRegex();
                if (regex == null) {
                    yield false;
                }
                yield regex.matcher(String.valueOf(ctxValue)).find();
            }
        };
    }

    private static boolean equalsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a.equals(b)) return true;
        // Cross-type numeric comparison — MS's Python equality treats 1 == 1.0 as true.
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Object ctxValue, Object target) {
        if (ctxValue instanceof Number ncv && target instanceof Number nt) {
            return Double.compare(ncv.doubleValue(), nt.doubleValue());
        }
        if (ctxValue instanceof Comparable ccv && target != null && target.getClass().isInstance(ccv)) {
            return ccv.compareTo(target);
        }
        if (target instanceof Comparable ct && ctxValue != null && ctxValue.getClass().isInstance(ct)) {
            return -ct.compareTo(ctxValue);
        }
        // Incomparable values — treat as "not greater than / not less than"
        // by returning 0, matching MS's fail-to-no-match semantic as closely
        // as a strongly-typed language can.
        return 0;
    }
}
