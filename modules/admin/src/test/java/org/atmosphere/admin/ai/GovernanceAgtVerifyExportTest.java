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
package org.atmosphere.admin.ai;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract test for the {@code agt verify}-shaped JSON export — locks
 * down the shape so external compliance tooling that already consumes
 * MS's Agent Compliance package format can round-trip the output.
 */
class GovernanceAgtVerifyExportTest {

    private GovernanceController controller;

    @BeforeEach
    void setUp() {
        var framework = mock(AtmosphereFramework.class);
        var config = mock(AtmosphereConfig.class);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.properties()).thenReturn(new HashMap<>());
        controller = new GovernanceController(framework);
    }

    @Test
    void topLevelCarriesSchemaVersionAndTimestamp() {
        var out = controller.agtVerifyExport();
        assertEquals("agt-verify/1", out.get("schemaVersion"));
        assertNotNull(out.get("generatedAt"));
    }

    @Test
    void findingsArrayIsNonEmpty() {
        var out = controller.agtVerifyExport();
        var findings = (List<?>) out.get("findings");
        assertFalse(findings.isEmpty(), "at least one OWASP + compliance row must emit a finding");
    }

    @Test
    void everyFindingCarriesRequiredFields() {
        var out = controller.agtVerifyExport();
        var findings = (List<?>) out.get("findings");
        for (var entry : findings) {
            var finding = (Map<?, ?>) entry;
            assertNotNull(finding.get("framework"),  "framework tag missing");
            assertNotNull(finding.get("controlId"),  "controlId missing");
            assertNotNull(finding.get("title"),      "title missing");
            assertNotNull(finding.get("status"),     "status missing");
            assertNotNull(finding.get("evidence"),   "evidence array missing");
            assertTrue(finding.get("evidence") instanceof List,
                    "evidence must be a list");

            var status = finding.get("status").toString();
            assertTrue(Set.of("COVERED", "PARTIAL", "DESIGN", "NOT_ADDRESSED").contains(status),
                    "status must be one of the canonical values, got: " + status);
        }
    }

    @Test
    void summaryCoversAllFrameworks() {
        var out = controller.agtVerifyExport();
        var summary = (Map<?, ?>) out.get("summary");
        assertTrue(summary.containsKey("OWASP_AGENTIC_TOP_10"));
        assertTrue(summary.containsKey("EU_AI_ACT"));
        assertTrue(summary.containsKey("HIPAA"));
        assertTrue(summary.containsKey("SOC2"));
    }

    @Test
    void summaryCountsSumToFindingsCountPerFramework() {
        var out = controller.agtVerifyExport();
        var summary = (Map<?, ?>) out.get("summary");
        var findings = (List<?>) out.get("findings");

        for (var frameworkKey : List.of("OWASP_AGENTIC_TOP_10", "EU_AI_ACT", "HIPAA", "SOC2")) {
            var counts = (Map<?, ?>) summary.get(frameworkKey);
            int totalInSummary = counts.values().stream()
                    .mapToInt(v -> ((Number) v).intValue()).sum();
            int totalInFindings = (int) findings.stream()
                    .filter(f -> ((Map<?, ?>) f).get("framework").equals(frameworkKey))
                    .count();
            assertEquals(totalInFindings, totalInSummary,
                    "summary counts for " + frameworkKey + " must sum to findings count");
        }
    }

    @Test
    void findingsIncludeOwaspControlIds() {
        var out = controller.agtVerifyExport();
        var findings = (List<?>) out.get("findings");
        var controlIds = findings.stream()
                .filter(f -> ((Map<?, ?>) f).get("framework").equals("OWASP_AGENTIC_TOP_10"))
                .map(f -> (String) ((Map<?, ?>) f).get("controlId"))
                .toList();
        assertTrue(controlIds.contains("A01"),
                "OWASP goal-hijacking control A01 must appear in findings");
    }

    @Test
    void evidenceFieldsUseCanonicalKeys() {
        var out = controller.agtVerifyExport();
        var findings = (List<?>) out.get("findings");
        for (var entry : findings) {
            var evidence = (List<?>) ((Map<?, ?>) entry).get("evidence");
            for (var ev : evidence) {
                var evMap = (Map<?, ?>) ev;
                assertTrue(evMap.containsKey("class"), "every evidence entry has a class");
                assertTrue(evMap.containsKey("test"), "every evidence entry has a test pointer");
                assertTrue(evMap.containsKey("consumerGrep"), "consumer-grep anchor present");
            }
        }
    }
}
