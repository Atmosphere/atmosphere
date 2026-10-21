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
package org.atmosphere.ai.filter;

import org.atmosphere.ai.AiGuardrail.GuardrailResult;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.guardrails.PiiRedactionGuardrail;
import org.atmosphere.ai.tape.PiiTapeRedactor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A phone number is a number written the way a phone number is written: with
 * a leading {@code +}, or with separators or parentheses between its groups.
 * A bare run of digits — a math result, a quantity, a year, an order number,
 * the digits of a decimal — is not one, and no PII surface (the guardrail, the
 * stream filter, the session tape) redacts or blocks it.
 */
class PiiPhoneNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "1234567890", "5145551234", "3.14159265358", "12 x 30 = 360",
            "2^31 = 2147483648", "sqrt(144) = 12", "123456789", "123456789012",
            "3+15145551234", "1.5145551234", "0.000123456789", "x+2=5",
            "514-555-12345", "514\n555\n1234", "In 2026 we sold 1500000 units",
    })
    void aBareDigitRunOrAMathExpressionIsNotAPhone(String text) {
        var answer = "The answer is " + text + ".";
        assertFalse(PiiPatterns.PHONE.matcher(answer).find(), "PHONE flagged: " + text);
        assertFalse(PiiPatterns.US_PHONE.matcher(answer).find(), "US_PHONE flagged: " + text);

        var guardrail = new PiiRedactionGuardrail();
        assertInstanceOf(GuardrailResult.Pass.class, guardrail.inspectResponse(answer),
                "a response with " + text + " was blocked");
        assertInstanceOf(GuardrailResult.Pass.class, guardrail.inspectRequest(new AiRequest(answer)),
                "a request with " + text + " was redacted");

        assertEquals(answer, new PiiRedactionFilter().redact(answer), "the stream filter redacted " + text);
        assertEquals(Map.of("text", answer), new PiiTapeRedactor().redact("message", Map.of("text", answer)),
                "the tape redacted " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+12345678901234567", "+1234567"})
    void aPlusSignBeforeTooManyOrTooFewDigitsIsNotAPhone(String text) {
        assertFalse(PiiPatterns.PHONE.matcher("delta " + text + " units").find(), "PHONE flagged: " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+15145551234", "(514) 555-1234", "514-555-1234", "514.555.1234", "514 555 1234",
            "555-123-4567", "(555) 123-4567", "(555)123-4567", "+1 555 123 4567",
            "+1 (514) 555-1234", "1-800-555-1234", "555-867-5309",
    })
    void aNorthAmericanNumberIsStillPiiEverywhere(String phone) {
        var text = "Call " + phone + " today.";
        assertTrue(PiiPatterns.US_PHONE.matcher(text).find(), "US_PHONE missed: " + phone);
        assertGuardrailRedactsAndBlocks(phone);
        assertEquals("Call [REDACTED] today.", new PiiRedactionFilter().redact(text));
        assertEquals(Map.of("text", "Call [REDACTED] today."),
                new PiiTapeRedactor().redact("message", Map.of("text", text)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+44 20 7946 0958", "+33 1 23 45 67 89", "+49 30 12345678", "+491701234567",
            "+15145551234", "+1 (514) 555-1234", "514-555-1234",
    })
    void anInternationalNumberIsPiiForTheGuardrail(String phone) {
        assertTrue(PiiPatterns.PHONE.matcher("Call " + phone + " today.").find(), "PHONE missed: " + phone);
        assertGuardrailRedactsAndBlocks(phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice,514-555-1234", "Alice;514.555.1234", "(514-555-1234)", "tel:+15145551234"})
    void aPhoneNextToPunctuationIsStillFound(String text) {
        assertTrue(PiiPatterns.PHONE.matcher(text).find(), "PHONE missed: " + text);
        assertTrue(PiiPatterns.US_PHONE.matcher(text).find(), "US_PHONE missed: " + text);
    }

    private static void assertGuardrailRedactsAndBlocks(String phone) {
        var text = "Call " + phone + " today.";
        var guardrail = new PiiRedactionGuardrail();
        assertInstanceOf(GuardrailResult.Block.class, guardrail.inspectResponse(text),
                "a response with " + phone + " passed");
        var redacted = assertInstanceOf(GuardrailResult.Modify.class,
                guardrail.inspectRequest(new AiRequest(text)));
        assertEquals("Call [redacted-phone] today.", redacted.modifiedRequest().message());
    }
}
