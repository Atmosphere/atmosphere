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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A card number is 13 to 19 digits, optionally grouped by single spaces or
 * hyphens, that passes the Luhn checksum and stands on its own. A large
 * integer in an answer — a power of two, a factorial, a sequence, the digits
 * of a decimal, a run inside a longer number — is not one, and no PII surface
 * (the guardrail, the stream filter, the session tape) redacts or blocks it.
 */
class PiiCardNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "2^50 = 1125899906842624", "1234567890123456", "20! = 2432902008176640000",
            "2^63 - 1 = 9223372036854775807", "10^18 = 1000000000000000000", "18! = 6402373705728000",
            "1125 8999 0684 2624", "1234-5678-9012-3456",
            "41111111111111111111111", "x4111111111111111", "0.4111111111111111",
            "4111111111111111.5", "-4111111111111111", "+4111111111111111", "a_4111111111111111",
    })
    void aLargeIntegerThatIsNotACardPasses(String text) {
        var answer = "The answer is " + text + " here.";
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
    @ValueSource(strings = {
            "4111 1111 1111 1111", "4111-1111-1111-1111", "4111111111111111",
            "5500 0000 0000 0004", "5500-0000-0000-0004", "3782 822463 10005", "378282246310005",
            "6011111111111117", "6011 1111 1111 1117",
    })
    void aRealCardNumberIsStillPiiEverywhere(String card) {
        var text = "Charge " + card + " today.";
        var guardrail = new PiiRedactionGuardrail();
        assertInstanceOf(GuardrailResult.Block.class, guardrail.inspectResponse(text),
                "a response with " + card + " passed");
        var redacted = assertInstanceOf(GuardrailResult.Modify.class,
                guardrail.inspectRequest(new AiRequest(text)));
        assertEquals("Charge [redacted-cc] today.", redacted.modifiedRequest().message());
        assertEquals("Charge [REDACTED] today.", new PiiRedactionFilter().redact(text));
        assertEquals(Map.of("text", "Charge [REDACTED] today."),
                new PiiTapeRedactor().redact("message", Map.of("text", text)));
    }

    @Test
    void aCardFollowedByMoreDigitsIsRedactedUpToItsLastDigit() {
        var text = "Card 4111 1111 1111 1111 12/27, cvv on the back.";
        var redacted = assertInstanceOf(GuardrailResult.Modify.class,
                new PiiRedactionGuardrail().inspectRequest(new AiRequest(text)));
        assertEquals("Card [redacted-cc] 12/27, cvv on the back.", redacted.modifiedRequest().message());
        assertEquals("Card [REDACTED] 12/27, cvv on the back.", new PiiRedactionFilter().redact(text));
    }

    @Test
    void aCardAndANonCardInOneTextOnlyTheCardIsRedacted() {
        var text = "2^50 = 1125899906842624; pay with 4111111111111111.";
        assertEquals("2^50 = 1125899906842624; pay with [REDACTED].", new PiiRedactionFilter().redact(text));
        var redacted = assertInstanceOf(GuardrailResult.Modify.class,
                new PiiRedactionGuardrail().inspectRequest(new AiRequest(text)));
        assertEquals("2^50 = 1125899906842624; pay with [redacted-cc].", redacted.modifiedRequest().message());
    }
}
