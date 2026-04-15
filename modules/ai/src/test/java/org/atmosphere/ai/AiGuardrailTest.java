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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AiGuardrailTest {

    @Test
    void defaultInspectRequestPasses() {
        AiGuardrail guardrail = new AiGuardrail() {};
        var result = guardrail.inspectRequest(new AiRequest("hello"));
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, result);
    }

    @Test
    void defaultInspectResponsePasses() {
        AiGuardrail guardrail = new AiGuardrail() {};
        var result = guardrail.inspectResponse("some response");
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, result);
    }

    @Test
    void passFactoryMethod() {
        var result = AiGuardrail.GuardrailResult.pass();
        assertInstanceOf(AiGuardrail.GuardrailResult.Pass.class, result);
    }

    @Test
    void blockFactoryMethod() {
        var result = AiGuardrail.GuardrailResult.block("unsafe content");
        assertInstanceOf(AiGuardrail.GuardrailResult.Block.class, result);
        assertEquals("unsafe content", ((AiGuardrail.GuardrailResult.Block) result).reason());
    }

    @Test
    void modifyFactoryMethod() {
        var modified = new AiRequest("modified");
        var result = AiGuardrail.GuardrailResult.modify(modified);
        assertInstanceOf(AiGuardrail.GuardrailResult.Modify.class, result);
        var modifyResult = (AiGuardrail.GuardrailResult.Modify) result;
        assertEquals("modified", modifyResult.modifiedRequest().message());
    }

    @Test
    void sealedInterfaceExhaustiveSwitch() {
        AiGuardrail.GuardrailResult result = AiGuardrail.GuardrailResult.pass();
        var name = switch (result) {
            case AiGuardrail.GuardrailResult.Pass p -> "pass";
            case AiGuardrail.GuardrailResult.Modify m -> "modify";
            case AiGuardrail.GuardrailResult.Block b -> "block";
        };
        assertEquals("pass", name);
    }
}
