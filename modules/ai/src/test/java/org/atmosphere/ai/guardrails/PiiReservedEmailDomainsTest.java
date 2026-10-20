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

import org.atmosphere.ai.AiGuardrail.GuardrailResult;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.filter.PiiPatterns;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An address at a domain RFC 2606 / RFC 6761 reserve for documentation and
 * testing ({@code alice@example.com} in a code answer) belongs to no one, so
 * it is not PII: no surface redacts or blocks it. A real address, and a
 * look-alike that only contains a reserved name, still is.
 */
class PiiReservedEmailDomainsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "alice@example.com", "bob@example.net", "carol@example.org",
            "Dave@EXAMPLE.COM", "eve@mail.example.com", "frank@a.b.example.org",
            "grace@host.example", "heidi@api.test", "ivan@nowhere.invalid", "judy@dev.localhost",
    })
    void anAddressAtAReservedDomainIsNotPii(String address) {
        var code = "user = User(\"" + address + "\")\nsend_email(" + address + ").";
        assertFalse(PiiPatterns.EMAIL.matcher(code).find(), "flagged as PII: " + address);

        var guardrail = new PiiRedactionGuardrail();
        assertInstanceOf(GuardrailResult.Pass.class, guardrail.inspectResponse(code),
                "a code answer with " + address + " was blocked");
        assertInstanceOf(GuardrailResult.Pass.class, guardrail.inspectRequest(new AiRequest(code)),
                "a request with " + address + " was redacted");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alice@gmail.com", "bob@acme.io", "alice@example.com.evil.io",
            "alice@notexample.com", "alice@example.co", "alice@testing.com",
            "alice@example.community", "alice@test.example.io",
    })
    void aRealAddressOrALookAlikeIsStillPii(String address) {
        var text = "Contact " + address + " for details.";
        assertTrue(PiiPatterns.EMAIL.matcher(text).find(), "missed as PII: " + address);

        var guardrail = new PiiRedactionGuardrail();
        assertInstanceOf(GuardrailResult.Block.class, guardrail.inspectResponse(text),
                "a response with " + address + " passed");
        var redacted = assertInstanceOf(GuardrailResult.Modify.class,
                guardrail.inspectRequest(new AiRequest(text)));
        assertEquals("Contact [redacted-email] for details.", redacted.modifiedRequest().message());
    }
}
