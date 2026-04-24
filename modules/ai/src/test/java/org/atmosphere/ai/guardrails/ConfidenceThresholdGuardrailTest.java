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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceThresholdGuardrailTest {

    @Test
    void highConfidencePasses() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        var response = "{\"answer\": \"42\", \"confidence\": 0.92}";
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                guardrail.inspectResponse(response));
    }

    @Test
    void lowConfidenceBlocks() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        var response = "{\"answer\": \"maybe\", \"confidence\": 0.55}";
        var result = guardrail.inspectResponse(response);
        var block = assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
        assertTrue(block.reason().contains("0.55"));
        assertTrue(block.reason().contains("0.7"));
        assertTrue(block.reason().toLowerCase().contains("human review"));
    }

    @Test
    void exactThresholdPasses() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        var response = "{\"confidence\": 0.7}";
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                guardrail.inspectResponse(response));
    }

    @Test
    void missingFieldPasses() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                guardrail.inspectResponse("{\"answer\": \"no field here\"}"));
    }

    @Test
    void customFieldName() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7).withFieldName("certainty");
        var response = "{\"certainty\": 0.4, \"confidence\": 0.99}";
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                guardrail.inspectResponse(response));
    }

    @Test
    void integerValueHandled() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                guardrail.inspectResponse("{\"confidence\": 1}"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                guardrail.inspectResponse("{\"confidence\": 0}"));
    }

    @Test
    void whitespaceToleranceInRegex() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        var response = "{ \"confidence\"  :  0.5 , \"other\": 1 }";
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class,
                guardrail.inspectResponse(response));
    }

    @Test
    void malformedValuePasses() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        // "not-a-number" won't match the regex (regex requires digits)
        // so this actually passes via the "no match" path.
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                guardrail.inspectResponse("{\"confidence\": \"not-a-number\"}"));
    }

    @Test
    void emptyResponsePasses() {
        var guardrail = new ConfidenceThresholdGuardrail(0.7);
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                guardrail.inspectResponse(""));
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class,
                guardrail.inspectResponse(null));
    }

    @Test
    void invalidConfigRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceThresholdGuardrail(0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceThresholdGuardrail(1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceThresholdGuardrail(0.7, ""));
    }

    @Test
    void defaultThresholdMatchesV5Roadmap() {
        assertEquals(0.7, ConfidenceThresholdGuardrail.DEFAULT_THRESHOLD, 1e-9);
    }
}
