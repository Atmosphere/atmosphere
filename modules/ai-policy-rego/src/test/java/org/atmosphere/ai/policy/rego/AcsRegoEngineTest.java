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

import org.atmosphere.ai.governance.acs.AcsManifest;
import org.atmosphere.ai.governance.acs.AcsPolicyEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine-local tests that need no {@code opa} binary: bundle resolution
 * confinement, fail-closed verdicts, verdict-JSON parsing, and ServiceLoader
 * registration. The end-to-end evaluation against real {@code opa} lives in
 * {@link AcsRegoEngineIntegrationTest}.
 */
class AcsRegoEngineTest {

    @Test
    void registeredViaServiceLoaderForTypeRego() {
        var found = false;
        for (var engine : ServiceLoader.load(AcsPolicyEngine.class)) {
            if (engine instanceof AcsRegoEngine) {
                found = true;
                assertEquals("rego", engine.type());
            }
        }
        assertTrue(found, "AcsRegoEngine must be ServiceLoader-visible");
    }

    @Test
    void bundleEscapingManifestDirectoryIsRejected(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("inside.rego"), "package p\n");
        var escape = assertThrows(IllegalArgumentException.class,
                () -> AcsRegoEngine.resolveBundle(dir, "../outside.rego"));
        assertTrue(escape.getMessage().contains("escapes"));

        assertThrows(IllegalArgumentException.class,
                () -> AcsRegoEngine.resolveBundle(dir, "/etc/passwd"));
    }

    @Test
    void missingBundleAndMissingDirectoryDenyFailClosed(@TempDir Path dir) {
        var engine = new AcsRegoEngine();

        var noDir = engine.evaluate(evaluation(null, "./policy.rego"));
        assertEquals(AcsPolicyEngine.Decision.DENY, noDir.decision());
        assertEquals(AcsPolicyEngine.RUNTIME_ERROR, noDir.reason());

        var noFile = engine.evaluate(evaluation(dir, "./missing.rego"));
        assertEquals(AcsPolicyEngine.Decision.DENY, noFile.decision());
        assertTrue(noFile.message().contains("does not exist"));

        var noBundle = engine.evaluate(evaluation(dir, null));
        assertEquals(AcsPolicyEngine.Decision.DENY, noBundle.decision());
        assertTrue(noBundle.message().contains("declares no bundle"));
    }

    @Test
    void parsesAcsVerdictShapes() {
        var allow = AcsRegoEngine.parseVerdict(
                opaResult("{\"decision\": \"allow\"}"));
        assertEquals(AcsPolicyEngine.Decision.ALLOW, allow.decision());

        var deny = AcsRegoEngine.parseVerdict(opaResult(
                "{\"decision\": \"deny\", \"reason\": \"external_recipient_blocked\","
                        + " \"message\": \"Blocked.\"}"));
        assertEquals(AcsPolicyEngine.Decision.DENY, deny.decision());
        assertEquals("external_recipient_blocked", deny.reason());
        assertEquals("Blocked.", deny.message());

        var transform = AcsRegoEngine.parseVerdict(opaResult(
                "{\"decision\": \"transform\", \"transform\":"
                        + " {\"path\": \"$policy_target\", \"value\": \"[REDACTED]\"}}"));
        assertEquals(AcsPolicyEngine.Decision.TRANSFORM, transform.decision());
        assertEquals("$policy_target", transform.transformPath());
        assertEquals("[REDACTED]", transform.transformValue());

        // Regression: a "reason" between the decision and the transform
        // object means the first "transform" substring in the JSON is the
        // DECISION VALUE, not the key — the parser must skip value matches
        // and bind the real "transform": key.
        var transformWithReason = AcsRegoEngine.parseVerdict(opaResult(
                "{\"decision\": \"transform\", \"reason\": \"redact_tracking_token\","
                        + " \"transform\": {\"path\": \"$policy_target\","
                        + " \"value\": \"[REDACTED]\"}}"));
        assertEquals(AcsPolicyEngine.Decision.TRANSFORM, transformWithReason.decision());
        assertEquals("$policy_target", transformWithReason.transformPath());
        assertEquals("[REDACTED]", transformWithReason.transformValue());

        assertEquals(AcsPolicyEngine.Decision.ALLOW,
                AcsRegoEngine.parseVerdict(opaResult("true")).decision());
        assertEquals(AcsPolicyEngine.Decision.DENY,
                AcsRegoEngine.parseVerdict(opaResult("false")).decision());
    }

    // An undefined query (`"result": []`) must deny — a verdict rule that
    // produced nothing is a runtime error, never a default allow.
    @Test
    void undefinedOrMalformedVerdictsDenyFailClosed() {
        var undefined = AcsRegoEngine.parseVerdict("{\"result\": []}");
        assertEquals(AcsPolicyEngine.Decision.DENY, undefined.decision());
        assertEquals(AcsPolicyEngine.RUNTIME_ERROR, undefined.reason());

        var unknownDecision = AcsRegoEngine.parseVerdict(
                opaResult("{\"decision\": \"maybe\"}"));
        assertEquals(AcsPolicyEngine.Decision.DENY, unknownDecision.decision());

        var missingTransform = AcsRegoEngine.parseVerdict(
                opaResult("{\"decision\": \"transform\"}"));
        assertEquals(AcsPolicyEngine.Decision.DENY, missingTransform.decision());

        assertEquals(AcsPolicyEngine.Decision.DENY,
                AcsRegoEngine.parseVerdict("").decision());
    }

    private static String opaResult(String value) {
        return "{\"result\": [{\"expressions\": [{\"value\": " + value + "}]}]}";
    }

    private static AcsPolicyEngine.Evaluation evaluation(Path manifestDir, String bundle) {
        return new AcsPolicyEngine.Evaluation("p",
                new AcsManifest.PolicyRef("rego", bundle, null),
                "data.p.verdict", manifestDir, "input",
                Map.of("intervention_point", "input"));
    }
}
