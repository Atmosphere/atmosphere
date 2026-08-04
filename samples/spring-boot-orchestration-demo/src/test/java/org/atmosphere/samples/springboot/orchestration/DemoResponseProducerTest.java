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
package org.atmosphere.samples.springboot.orchestration;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoResponseProducerTest {

    // The REAL vendored skill bodies, loaded from the same classpath location
    // PromptLoader's tier-1 lookup serves at runtime — the sample vendors both
    // files under src/main/resources/META-INF/skills so demo mode never
    // depends on the disk cache or a GitHub fetch. Loading them here (instead
    // of mirroring the text as constants) means persona-selection drift
    // between a skill edit and the strategy fails this test.
    private static final String SUPPORT_SKILL_PROMPT =
            loadVendoredSkill("support-agent");
    private static final String BILLING_SKILL_PROMPT =
            loadVendoredSkill("billing-agent");

    private static String loadVendoredSkill(String name) {
        var resource = "META-INF/skills/" + name + "/SKILL.md";
        try (var in = DemoResponseProducerTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("vendored skill missing from classpath: "
                        + resource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read vendored skill " + resource, e);
        }
    }

    @AfterEach
    void resetStrategy() {
        DemoAgentRuntime.setResponseStrategy(null);
    }

    @Test
    void supportPersonaSelectedEvenThoughItsPromptMentionsTheBillingSpecialist() {
        // The support SKILL.md body also names the "billing specialist" it routes
        // to; the strategy must still classify it as the support persona.
        var response = DemoResponseProducer.generateFor(context(SUPPORT_SKILL_PROMPT));
        assertEquals(DemoResponseProducer.SUPPORT_RESPONSE, response);
    }

    @Test
    void billingPersonaSelectedFromBillingSkillPrompt() {
        var response = DemoResponseProducer.generateFor(context(BILLING_SKILL_PROMPT));
        assertEquals(DemoResponseProducer.BILLING_RESPONSE, response);
    }

    @Test
    void unknownOrMissingSystemPromptFallsBackToSupportPersona() {
        assertEquals(DemoResponseProducer.SUPPORT_RESPONSE,
                DemoResponseProducer.generateFor(context(null)));
        assertEquals(DemoResponseProducer.SUPPORT_RESPONSE,
                DemoResponseProducer.generateFor(context("You are a weather bot.")));
    }

    @Test
    void cannedCopyKeepsThePhrasesTheE2eSuiteAsserts() {
        // orchestration-primitives.spec.ts pins these user-visible substrings.
        assertTrue(DemoResponseProducer.BILLING_RESPONSE.contains("billing support"));
        assertTrue(DemoResponseProducer.BILLING_RESPONSE.contains("transferred"));
        assertTrue(DemoResponseProducer.SUPPORT_RESPONSE.contains("billing specialist"));
    }

    @Test
    void installedStrategyStreamsTheBillingPersonaThroughDemoAgentRuntime() {
        new DemoResponseProducer().installAgentAwareStrategy();

        var streamed = new StringBuilder();
        var session = (StreamingSession) Proxy.newProxyInstance(
                StreamingSession.class.getClassLoader(),
                new Class<?>[] { StreamingSession.class },
                (proxy, method, args) -> {
                    if ("send".equals(method.getName()) && args != null && args.length == 1) {
                        streamed.append(args[0]);
                    }
                    if ("toString".equals(method.getName())) {
                        return "StreamingSessionProxy";
                    }
                    return defaultValue(method.getReturnType());
                });

        new DemoAgentRuntime().execute(context(BILLING_SKILL_PROMPT), session);

        assertEquals(DemoResponseProducer.BILLING_RESPONSE, streamed.toString());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }

    private static AgentExecutionContext context(String systemPrompt) {
        return new AgentExecutionContext("I need help with my invoice", systemPrompt,
                null, null, null, null, null, List.of(), null, null,
                List.of(), null, List.of(), null, null);
    }
}
