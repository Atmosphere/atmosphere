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

    @Test
    void customRulesBundleSupportsNonEnglishLocales() {
        // Prove the i18n seam: a French-locale rule set wires through the
        // same constructor. Operators deploying in fr-FR would source these
        // patterns from their own resource bundle / service.
        var categories = new java.util.LinkedHashMap<String, java.util.regex.Pattern>();
        TicketClassifierInterceptor.Rules.add(categories, "refund", "remboursement|retour");
        TicketClassifierInterceptor.Rules.add(categories, "shipping", "livraison|colis|suivi");
        TicketClassifierInterceptor.Rules.add(categories, "billing", "facture|paiement");
        var urgent = java.util.regex.Pattern.compile(
                "\\b(urgent|immédiatement|cassé|volé|fraude\\w*)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        var high = java.util.regex.Pattern.compile(
                "\\b(plainte|probl[eè]me|manquant)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        var french = TicketClassifierInterceptor.Rules.of(
                categories, "général", urgent, high);
        var interceptor = new TicketClassifierInterceptor(french);

        var refundOut = interceptor.preProcess(
                new AiRequest("Je veux un remboursement pour ma commande"), null);
        assertEquals("refund", refundOut.metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));

        var urgentOut = interceptor.preProcess(
                new AiRequest("ma carte a été utilisée dans une fraude"), null);
        assertEquals("urgent", urgentOut.metadata().get(TicketClassifierInterceptor.PRIORITY_KEY));

        var fallbackOut = interceptor.preProcess(new AiRequest("bonjour"), null);
        assertEquals("général",
                fallbackOut.metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
    }

    @Test
    void mergingRuleBundlesJoinsPatterns() {
        var base = TicketClassifierInterceptor.Rules.english();
        var extraCategories = new java.util.LinkedHashMap<String, java.util.regex.Pattern>();
        TicketClassifierInterceptor.Rules.add(extraCategories, "shipping", "livraison|colis");
        var extra = TicketClassifierInterceptor.Rules.of(
                extraCategories, "general",
                java.util.regex.Pattern.compile("\\burgent\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bissue\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE));
        var merged = base.merge(extra);
        var interceptor = new TicketClassifierInterceptor(merged);

        // Either language's "shipping" keyword hits the shipping category.
        assertEquals("shipping", interceptor.preProcess(
                new AiRequest("tracking number please"), null)
                .metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
        assertEquals("shipping", interceptor.preProcess(
                new AiRequest("ma livraison est en retard"), null)
                .metadata().get(TicketClassifierInterceptor.CATEGORY_KEY));
    }
}
