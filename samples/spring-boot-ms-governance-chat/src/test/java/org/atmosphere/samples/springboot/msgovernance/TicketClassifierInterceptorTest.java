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

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketClassifierInterceptorTest {

    private final TicketClassifierInterceptor interceptor = new TicketClassifierInterceptor();

    @Test
    void refundTakesPriorityOverOrder() {
        var out = interceptor.preProcess(
                new AiRequest("I want a refund for my order"), null);
        assertEquals("refund", out.metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
    }

    @Test
    void shippingDetected() {
        var out = interceptor.preProcess(
                new AiRequest("where is my delivery"), null);
        assertEquals("shipping", out.metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
    }

    @Test
    void billingDetected() {
        var out = interceptor.preProcess(
                new AiRequest("my invoice looks wrong"), null);
        assertEquals("billing", out.metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
    }

    @Test
    void generalFallback() {
        var out = interceptor.preProcess(
                new AiRequest("hello there"), null);
        assertEquals("general", out.metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
    }

    @Test
    void urgentKeywordsTripUrgentPriority() {
        var out = interceptor.preProcess(
                new AiRequest("my credit card was used in a fraudulent charge"), null);
        assertEquals("urgent", out.metadata().get(TicketClassifierInterceptor.PRIORITY_KEY));
    }

    @Test
    void highKeywordsTripHighPriority() {
        var out = interceptor.preProcess(
                new AiRequest("I have a complaint about the order"), null);
        assertEquals("high", out.metadata().get(TicketClassifierInterceptor.PRIORITY_KEY));
    }

    @Test
    void defaultPriorityIsNormal() {
        var out = interceptor.preProcess(
                new AiRequest("product info please"), null);
        assertEquals("normal", out.metadata().get(TicketClassifierInterceptor.PRIORITY_KEY));
    }

    @Test
    void preservesExistingMetadata() {
        var in = new AiRequest("hi", "", null, null, null, null, null,
                Map.of("other_key", "other_value"), java.util.List.of());
        var out = interceptor.preProcess(in, null);
        assertEquals("other_value", out.metadata().get("other_key"));
        assertEquals("general", out.metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
    }
}
