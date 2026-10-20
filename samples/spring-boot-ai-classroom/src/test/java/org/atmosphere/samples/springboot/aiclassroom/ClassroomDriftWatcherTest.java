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
package org.atmosphere.samples.springboot.aiclassroom;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.governance.DryRunPolicy;
import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyContext;
import org.atmosphere.ai.governance.PolicyDecision;
import org.atmosphere.ai.governance.YamlPolicyParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classroom's response-length drift watcher must never cut a normal long
 * tutoring answer short.
 *
 * <p>A streamed reply is checked every 200 characters and once complete, and
 * the length z-score guardrail records each of those checks as a sample, so
 * its baseline is a mean of partial lengths and a long answer crosses
 * 3 sigma part-way through. The re-test of 2026-10-19 saw exactly that:
 * "Output length 1632 chars is 3.04 sigma from rolling mean 531.4" ended a
 * Science answer to "What is photosynthesis?" mid-stream.</p>
 */
class ClassroomDriftWatcherTest {

    private static final String POLICY_FILE = "atmosphere-policies.yaml";
    private static final int CHECK_INTERVAL = 200;

    /** Ordinary answers, then the two long ones the re-test saw blocked. */
    private static final int[] ANSWER_LENGTHS = {
            420, 650, 380, 900, 520, 760, 610, 480, 830, 560, 1632, 2632,
    };

    @Test
    void enforcedAsParsedTheWatcherBlocksAnOrdinaryLongAnswer() throws IOException {
        // The defect the sample works around: enforced, the watcher denies.
        List<GovernancePolicy> parsed;
        try (var in = policyYaml()) {
            parsed = new YamlPolicyParser().parse("classpath:" + POLICY_FILE, in);
        }
        assertTrue(denials(parsed) > 0,
                "the enforced length z-score watcher should deny one of these answers");
    }

    @Test
    void theSampleNeverBlocksALongAnswerButStillFlagsIt() throws IOException {
        List<GovernancePolicy> policies;
        try (var in = policyYaml()) {
            policies = PoliciesConfig.loadPolicies("classpath:" + POLICY_FILE, in);
        }
        assertEquals(0, denials(policies), "a long tutoring answer was blocked");

        var watcher = policies.stream()
                .filter(p -> p.name().equals(DryRunPolicy.NAME_PREFIX + "classroom-drift-watcher"))
                .findFirst();
        assertTrue(watcher.isPresent(), "the drift watcher is installed, in dry-run");
        assertTrue(((DryRunPolicy) watcher.get()).shadowDenies() > 0,
                "the drift watcher still flags the outlier it no longer blocks");
        assertFalse(policies.stream().anyMatch(p -> p.name().equals("classroom-drift-watcher")),
                "the drift watcher is not also installed enforced");
    }

    /** Streams every answer through the policies at the session's cadence. */
    private static int denials(List<GovernancePolicy> policies) {
        var denials = 0;
        for (var length : ANSWER_LENGTHS) {
            var answer = "x".repeat(length);
            var checks = new ArrayList<String>();
            for (var at = CHECK_INTERVAL; at < length; at += CHECK_INTERVAL) {
                checks.add(answer.substring(0, at));
            }
            checks.add(answer);
            turn:
            for (var accumulated : checks) {
                var context = PolicyContext.postResponse(new AiRequest("question"), accumulated);
                for (var policy : policies) {
                    if (policy.evaluate(context) instanceof PolicyDecision.Deny) {
                        denials++;
                        break turn;
                    }
                }
            }
        }
        return denials;
    }

    private static InputStream policyYaml() {
        var in = ClassroomDriftWatcherTest.class.getClassLoader().getResourceAsStream(POLICY_FILE);
        if (in == null) {
            throw new IllegalStateException(POLICY_FILE + " is not on the test classpath");
        }
        return in;
    }
}
