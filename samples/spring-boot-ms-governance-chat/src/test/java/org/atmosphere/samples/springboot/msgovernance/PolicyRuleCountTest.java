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

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.MsAgentOsPolicy;
import org.atmosphere.ai.governance.TimedPolicy;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule count this sample reports must come from the rules it loaded.
 *
 * <p>It was a string constant reading "9" while {@code atmosphere-policies.yaml}
 * had grown to eleven rules, so the sample understated its own enforcement by
 * two — in a sample whose entire premise is that the YAML is the source of
 * truth and that editing it changes governance with no code edit.</p>
 */
class PolicyRuleCountTest {

    private static final String POLICY_FILE = "atmosphere-policies.yaml";

    private static List<GovernancePolicy> loadYamlPolicies() throws IOException {
        try (InputStream in = PolicyRuleCountTest.class.getClassLoader()
                .getResourceAsStream(POLICY_FILE)) {
            assertNotNull(in, POLICY_FILE + " must be on the test classpath");
            return new YamlPolicyParser().parse("classpath:" + POLICY_FILE, in);
        }
    }

    /** Rules as the YAML declares them, counted independently of the sample. */
    private static int declaredRules(List<GovernancePolicy> policies) {
        var total = 0;
        for (var policy : policies) {
            if (policy instanceof MsAgentOsPolicy ms) {
                total += ms.rules().size();
            }
        }
        return total;
    }

    @Test
    void theReportedCountMatchesTheRulesActuallyLoaded() throws IOException {
        var yamlPolicies = loadYamlPolicies();
        var declared = declaredRules(yamlPolicies);
        assertTrue(declared > 0,
                "the parser found no MS-schema rules, which would make every "
                        + "assertion here vacuous — fix the parse, do not delete the test");

        // Wrapped exactly as PoliciesConfig publishes them, so the unwrap is
        // exercised rather than assumed.
        var published = new ArrayList<GovernancePolicy>();
        yamlPolicies.forEach(p -> published.add(TimedPolicy.of(p)));

        assertEquals(declared, MsGovernanceChat.msSchemaRuleCount(published),
                "the sample reports a rule count to every visitor; it has to be the "
                        + "number of rules it is enforcing, not a constant that drifts "
                        + "the next time someone edits the YAML");
    }

    @Test
    void wrappedPoliciesAreUnwrappedBeforeCounting() throws IOException {
        // TimedPolicy is not an MsAgentOsPolicy, so a version that pattern-matched
        // the wrapper would count zero and silently report "0 MS-schema rules".
        var wrapped = new ArrayList<GovernancePolicy>();
        loadYamlPolicies().forEach(p -> wrapped.add(TimedPolicy.of(p)));

        assertEquals(MsGovernanceChat.msSchemaRuleCount(loadYamlPolicies()),
                MsGovernanceChat.msSchemaRuleCount(wrapped),
                "wrapping for latency metrics must not change the count");
    }

    @Test
    void anAbsentPolicyPlaneCountsZeroRatherThanThrowing() {
        // The property is unset until PoliciesConfig publishes. A prompt arriving
        // in that window must still answer.
        assertEquals(0, MsGovernanceChat.msSchemaRuleCount(null));
        assertEquals(0, MsGovernanceChat.msSchemaRuleCount("not a policy list"));
    }
}
