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
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README quotes the policy file and the admin endpoints' answers; both are what a
 * reader checks the running sample against. They drifted: the README quoted an SSN deny
 * message the YAML no longer carried, and a {@code /policies} answer with one policy where
 * the sample installs seven. This test reads the README and fails when a quoted rule,
 * deny message or policy count stops matching what ships.
 */
class ReadmePolicyDriftTest {

    private static final String POLICY_FILE = "atmosphere-policies.yaml";
    private static final String YAML_POLICY_NAME = "ms-customer-service-demo";

    private static String readme() throws IOException {
        var readme = Path.of("README.md");
        assertTrue(Files.isRegularFile(readme),
                "README.md must be readable from the module directory (surefire's working dir)");
        return Files.readString(readme);
    }

    private static Map<String, MsAgentOsPolicy.Rule> shippedRules() throws IOException {
        List<GovernancePolicy> policies;
        try (InputStream in = ReadmePolicyDriftTest.class.getClassLoader()
                .getResourceAsStream(POLICY_FILE)) {
            assertNotNull(in, POLICY_FILE + " must be on the test classpath");
            policies = new YamlPolicyParser().parse("classpath:" + POLICY_FILE, in);
        }
        var rules = new HashMap<String, MsAgentOsPolicy.Rule>();
        for (var policy : policies) {
            if (policy instanceof MsAgentOsPolicy ms) {
                ms.rules().forEach(r -> rules.put(r.name(), r));
            }
        }
        assertTrue(!rules.isEmpty(), "no MS-schema rules parsed — every check below would be vacuous");
        return rules;
    }

    @Test
    void everyRuleQuotedInTheReadmeMatchesTheShippedYaml() throws IOException {
        var rules = shippedRules();
        var quoted = Pattern.compile(
                "- name: (\\S+)\\n(?:[^\\n]*\\n)*?\\s+priority: (\\d+)\\n\\s+message: \"([^\"]*)\"")
                .matcher(readme());
        var checked = 0;
        while (quoted.find()) {
            var name = quoted.group(1);
            var rule = rules.get(name);
            assertNotNull(rule, "README quotes rule '" + name + "', which " + POLICY_FILE + " does not ship");
            assertEquals(rule.priority(), Integer.parseInt(quoted.group(2)), "priority of " + name);
            assertEquals(rule.message(), quoted.group(3), "message of " + name);
            checked++;
        }
        assertTrue(checked > 0, "the README's YAML excerpt quoted no rule — the check is vacuous");
    }

    @Test
    void everyDenyMessageTheReadmePromisesIsAShippedRuleMessage() throws IOException {
        var messages = shippedRules().values().stream().map(MsAgentOsPolicy.Rule::message).toList();
        var promised = Pattern.compile("(?:Denied by policy '" + YAML_POLICY_NAME
                + "': |\"reason\": \")([^*\"]+)").matcher(readme());
        var checked = 0;
        while (promised.find()) {
            var message = promised.group(1).strip();
            if (message.isEmpty()) {
                continue;
            }
            assertTrue(messages.contains(message),
                    "README promises the deny message \"" + message + "\", which no rule in "
                            + POLICY_FILE + " carries");
            checked++;
        }
        assertTrue(checked > 0, "the README promised no deny message — the check is vacuous");
    }

    @Test
    void thePolicyCountTheReadmeShowsIsTheNumberThePlanePublishes() throws IOException {
        var config = new PoliciesConfig();
        var killSwitch = config.killSwitch();
        var published = config.msAgentOsPolicyLoader(new AtmosphereFramework(),
                config.countedKillSwitch(killSwitch), config.rateLimit(), config.concurrencyLimit(),
                config.businessHours(false), config.requireTenantId(), config.requireSupportRole())
                .policies().size();

        var count = Pattern.compile("\"policyCount\": (\\d+)").matcher(readme());
        assertTrue(count.find(), "README shows no /summary policyCount");
        assertEquals(published, Integer.parseInt(count.group(1)),
                "README's /summary example must report the policies PoliciesConfig publishes");
    }
}
