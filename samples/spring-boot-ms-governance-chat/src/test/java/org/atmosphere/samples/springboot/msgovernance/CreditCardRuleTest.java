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

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.TimeWindowPolicy;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The credit-card rule denies a card number, not every 16-digit integer. The
 * 2026-10-20 sweep had {@code Is it right that 2^50 = 1125899906842624?} denied
 * as a card by a card-shaped regex; 1125899906842624 fails the Luhn check. The
 * rule is evaluated through the published policy plane, as a chat turn and
 * {@code POST /api/admin/governance/check} evaluate it.
 */
class CreditCardRuleTest {

    private static final String CARD_DENIAL =
            "That looks like a credit-card number — please contact billing via a secure channel.";

    @AfterEach
    void resetDecisionLog() {
        GovernanceDecisionLog.reset();
    }

    private static GovernancePolicy publishedYamlPolicy() throws IOException {
        var framework = new AtmosphereFramework();
        var cfg = new PoliciesConfig();
        var businessHours = new TimeWindowPolicy(
                "support-business-hours", "code:" + PoliciesConfig.class.getName(), "1",
                LocalTime.of(8, 0), LocalTime.of(20, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
                ZoneId.of("America/New_York"),
                Clock.fixed(Instant.parse("2026-06-30T16:00:00Z"), ZoneOffset.UTC));
        cfg.msAgentOsPolicyLoader(framework, cfg.countedKillSwitch(cfg.killSwitch()), cfg.rateLimit(),
                cfg.concurrencyLimit(), businessHours, cfg.requireTenantId(), cfg.requireSupportRole());
        var plane = (List<?>) framework.getAtmosphereConfig().properties()
                .get(GovernancePolicy.POLICIES_PROPERTY);
        return plane.stream()
                .map(GovernancePolicy.class::cast)
                .filter(p -> "ms-customer-service-demo".equals(p.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the YAML policy is not on the published plane"));
    }

    private static PolicyDecision evaluate(String message, Map<String, Object> metadata) throws IOException {
        var request = new AiRequest(message).withMetadata(metadata);
        return publishedYamlPolicy().evaluate(PolicyContext.preAdmission(request));
    }

    private static PolicyDecision evaluate(String message) throws IOException {
        return evaluate(message, Map.of("tenant-id", "demo-tenant", "roles", "support-chat-user"));
    }

    @Test
    void aLargeIntegerThatFailsLuhnIsNotACard() throws IOException {
        assertInstanceOf(PolicyDecision.Admit.class,
                evaluate("Is it right that 2^50 = 1125899906842624?"));
        assertInstanceOf(PolicyDecision.Admit.class, evaluate("order 1234567890123456 is late"));
    }

    @Test
    void aGroupedTestCardIsDenied() throws IOException {
        var denied = assertInstanceOf(PolicyDecision.Deny.class,
                evaluate("my card is 4111 1111 1111 1111"));
        assertEquals(CARD_DENIAL, denied.reason());
    }

    @Test
    void anUngroupedOrHyphenatedTestCardIsDenied() throws IOException {
        assertEquals(CARD_DENIAL, assertInstanceOf(PolicyDecision.Deny.class,
                evaluate("charge 4111111111111111 please")).reason());
        assertEquals(CARD_DENIAL, assertInstanceOf(PolicyDecision.Deny.class,
                evaluate("card 4111-1111-1111-1111")).reason());
    }

    @Test
    void aTestCardSplitByTabsOrLinesIsDenied() throws IOException {
        // The rule's regex used to match any whitespace between the groups; a
        // card pasted across lines is still a card.
        assertEquals(CARD_DENIAL, assertInstanceOf(PolicyDecision.Deny.class,
                evaluate("my card is 4111\t1111\t1111\t1111")).reason());
        assertEquals(CARD_DENIAL, assertInstanceOf(PolicyDecision.Deny.class,
                evaluate("my card is\n4111\n1111\n1111\n1111")).reason());
    }

    @Test
    void aCallerCannotClaimTheMessageHoldsNoCard() throws IOException {
        var denied = assertInstanceOf(PolicyDecision.Deny.class,
                evaluate("my card is 4111 1111 1111 1111", Map.of(
                        "tenant-id", "demo-tenant", "roles", "support-chat-user",
                        "message_has_card_number", false)));
        assertEquals(CARD_DENIAL, denied.reason());
    }
}
