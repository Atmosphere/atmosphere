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

import org.atmosphere.ai.filter.PiiPatterns;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;

import java.util.Map;

/**
 * Gives the MS-schema YAML a field it cannot compute itself: whether the
 * message holds a credit-card number that passes the Luhn check.
 *
 * <p>MS's rule language has regex ({@code matches}) but no checksum, and a
 * card-shaped regex alone denies every 16-digit integer: {@code 2^50 =
 * 1125899906842624} was refused as a card. This wrapper runs the framework's
 * Luhn-checked matcher ({@link PiiPatterns#find} with
 * {@link PiiPatterns#CREDIT_CARD}, the one the PII redaction guardrail uses)
 * on the request message and hands the delegate the result as the
 * {@value #FIELD} context field, which {@code block-credit-card-number} in
 * {@code atmosphere-policies.yaml} tests with {@code eq: true}. The rule keeps
 * its priority among the YAML rules, and every path that evaluates the policy
 * plane (a chat turn, {@code POST /api/admin/governance/check}) sees the same
 * field. The value is always computed here, so a caller cannot send it.</p>
 *
 * <p>The YAML on its own, loaded by another MS-schema engine, never sets the
 * field, so that rule never fires there.</p>
 */
public final class CardNumberFieldPolicy implements GovernancePolicy {

    /** Context field: {@code true} when the message holds a Luhn-valid card number. */
    public static final String FIELD = "message_has_card_number";

    private final GovernancePolicy delegate;

    public CardNumberFieldPolicy(GovernancePolicy delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    /** The wrapped policy. */
    public GovernancePolicy delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public String source() {
        return delegate.source();
    }

    @Override
    public String version() {
        return delegate.version();
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context) {
        var request = context.request();
        if (request == null || request.message() == null) {
            return delegate.evaluate(context);
        }
        // withMetadata merges over the request's own entries, so a caller-sent
        // value for the field is replaced by the computed one.
        // The framework matcher takes one space or hyphen between digit groups;
        // a card pasted across lines or tabs is still a card, so whitespace runs
        // are read as one space (the Luhn check keeps integers out either way).
        var hasCard = PiiPatterns.find(PiiPatterns.CREDIT_CARD, request.message())
                || PiiPatterns.find(PiiPatterns.CREDIT_CARD, request.message().replaceAll("\\s+", " "));
        return delegate.evaluate(new PolicyContext(context.phase(),
                request.withMetadata(Map.of(FIELD, hasCard)),
                context.accumulatedResponse()));
    }
}
