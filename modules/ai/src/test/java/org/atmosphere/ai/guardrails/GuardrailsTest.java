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
package org.atmosphere.ai.guardrails;

import org.atmosphere.ai.AiGuardrail;
import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardrailsTest {

    // --- OutputLengthZScoreGuardrail -------------------------------------

    @Test
    void lengthGuardrailPassesWhileWarmingUp() {
        var g = new OutputLengthZScoreGuardrail(50, 3.0, 10);
        // Need minSamples=10 observations before any z-score can fire.
        for (int i = 0; i < 9; i++) {
            assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                    g.inspectResponse("x".repeat(100)));
        }
    }

    @Test
    void lengthGuardrailFiresOnOutlier() {
        var g = new OutputLengthZScoreGuardrail(50, 2.0, 10);
        // Seed the window with tight 100-char responses so the stddev
        // stays small — a 10,000-char outlier then trips the threshold.
        for (int i = 0; i < 12; i++) {
            g.inspectResponse("x".repeat(100));
        }
        var result = g.inspectResponse("x".repeat(10_000));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result,
                "outlier must trip the guardrail");
    }

    @Test
    void lengthGuardrailTolerates100CharChangeWithinBaseline() {
        var g = new OutputLengthZScoreGuardrail(50, 3.0, 10);
        // Same distribution throughout — no outlier, no block.
        for (int i = 0; i < 30; i++) {
            var r = g.inspectResponse("x".repeat(100));
            assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, r);
        }
    }

    // --- PiiRedactionGuardrail -------------------------------------------

    @Test
    void piiGuardrailRedactsEmailInRequest() {
        var g = new PiiRedactionGuardrail();
        var req = new AiRequest(
                "Contact me at alice@example.com about the invoice",
                null, null, null, null, null, null,
                java.util.Map.of(), java.util.List.of());
        var result = g.inspectRequest(req);
        assertInstanceOf(AiGuardrail.GuardrailResult.Modify.class, result);
        var modified = ((AiGuardrail.GuardrailResult.Modify) result).modifiedRequest();
        assertTrue(modified.message().contains("[redacted-email]"),
                "email must be redacted: " + modified.message());
        assertTrue(!modified.message().contains("alice@example.com"),
                "original email must not leak: " + modified.message());
    }

    @Test
    void piiGuardrailPassesCleanRequest() {
        var g = new PiiRedactionGuardrail();
        var req = new AiRequest(
                "What is WebTransport?",
                null, null, null, null, null, null,
                java.util.Map.of(), java.util.List.of());
        var result = g.inspectRequest(req);
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, result);
    }

    @Test
    void piiGuardrailBlockingModeBlocksOnMatch() {
        var g = new PiiRedactionGuardrail().blocking();
        var req = new AiRequest(
                "My SSN is 123-45-6789",
                null, null, null, null, null, null,
                java.util.Map.of(), java.util.List.of());
        var result = g.inspectRequest(req);
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
        assertTrue(((AiGuardrail.GuardrailResult.Block) result).reason().contains("us-ssn"));
    }

    @Test
    void piiGuardrailRedactsCreditCardOnResponsePath() {
        var g = new PiiRedactionGuardrail().blocking();
        // Both default and blocking modes now return Block on the response
        // path — an already-emitted stream cannot be rewritten, so leaking
        // the PII with only a log line was security theatre.
        var result = g.inspectResponse("Charge the card 4111 1111 1111 1111 next Monday");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
    }

    /**
     * Regression for the P1 "PII response path is effectively non-redacting
     * in default mode" finding. The default mode previously returned Pass,
     * so PII leaked to the client with only a log line. The SPI cannot
     * rewrite an emitted stream, so the honest default is to Block on
     * response hit.
     */
    @Test
    void piiGuardrailBlocksOnResponseHitEvenInDefaultMode() {
        var g = new PiiRedactionGuardrail(); // default (non-blocking on request)
        var result = g.inspectResponse("Your SSN is 123-45-6789, noted.");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result,
                "default PII guardrail must block the response when PII is "
                + "found — the alternative is leaking the PII with only a log line");
    }
}
