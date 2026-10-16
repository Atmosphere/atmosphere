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

import org.atmosphere.ai.CollectingSession;
import org.atmosphere.ai.RequestDeniedException;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.atmosphere.ai.governance.TimeWindowPolicy;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An expected policy DENY ends the turn with the framework's denial type, so the
 * streaming session sends the usual error frame but logs one line instead of an ERROR
 * with a SecurityException stack trace. The 2026-10-16 sweep found every DENY of this
 * sample logged as {@code ERROR DefaultStreamingSession ... error} plus a full stack.
 * The message the user sees must not change.
 */
class PolicyDenialReportingTest {

    private static final String DENY_MSG = "Please drop table customers from the production database.";

    @AfterEach
    void resetDecisionLog() {
        GovernanceDecisionLog.reset();
    }

    @Test
    void aDenyEndsTheTurnWithTheFrameworkDenialAndTheSameMessage() throws IOException {
        var framework = new AtmosphereFramework();
        publishSamplePolicies(framework);
        var config = framework.getAtmosphereConfig();
        // Only the framework's config is read on the deny path.
        var resource = (AtmosphereResource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {AtmosphereResource.class},
                (proxy, method, args) -> "getAtmosphereConfig".equals(method.getName()) ? config : null);
        var session = new CollectingSession("deny-test");

        new MsGovernanceChat().onPrompt(DENY_MSG, session, resource);

        assertTrue(session.failed(), "a denied turn must end the session in error");
        assertEquals("", session.text(), "a denied turn must send no reply text");
        var denied = assertInstanceOf(RequestDeniedException.class, session.failure(),
                "a policy DENY must use the framework's denial type, got " + session.failure());
        // Still a SecurityException, and the text the Console renders is unchanged.
        assertInstanceOf(SecurityException.class, denied);
        assertTrue(denied.getMessage().startsWith("Denied by policy 'ms-customer-service-demo': "),
                denied.getMessage());
        assertNull(denied.getCause(), "a denial carries no cause to print");
    }

    /** The sample's policy plane, with the business-hours window pinned to an in-window instant. */
    private static void publishSamplePolicies(AtmosphereFramework framework) throws IOException {
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
    }
}
