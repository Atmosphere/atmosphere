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
package org.atmosphere.samples.springboot.springairouting;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulates Spring AI routing responses for demo/testing purposes.
 * Shows how prompts would be routed to different models based on content.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    /**
     * @param userMessage the user's prompt
     * @param session     the streaming session
     * @param topic       the topic path parameter (from the URL template)
     * @param clientId    the AtmosphereResource UUID
     */
    public static void stream(String userMessage, StreamingSession session,
                              String topic, String clientId) {
        var category = classifyPrompt(userMessage);
        var response = generateResponse(category, topic);
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — topic: " + topic + ", routed to: " + category.model);
            for (var word : words) {
                session.send(word);
                Thread.sleep(50);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static PromptCategory classifyPrompt(String message) {
        var lower = message.toLowerCase();
        if (lower.contains("code") || lower.contains("function") || lower.contains("program")
                || lower.contains("debug") || lower.contains("algorithm") || lower.contains("java")
                || lower.contains("python") || lower.contains("javascript")) {
            return PromptCategory.CODE;
        }
        if (lower.contains("write") || lower.contains("story") || lower.contains("poem")
                || lower.contains("creative") || lower.contains("imagine") || lower.contains("fiction")) {
            return PromptCategory.CREATIVE;
        }
        if (lower.contains("math") || lower.contains("calculate") || lower.contains("equation")
                || lower.contains("solve") || lower.contains("formula")) {
            return PromptCategory.MATH;
        }
        return PromptCategory.GENERAL;
    }

    private static String generateResponse(PromptCategory category, String topic) {
        return switch (category) {
            case CODE -> "[Topic: " + topic + " | Routed to: " + category.model + "] "
                    + "Your code question was routed to a specialized code model. "
                    + "In production, the RoutingLlmClient would use a ContentBased rule to detect "
                    + "programming keywords and route to a model optimized for code generation "
                    + "(like GPT-4 or Claude Sonnet). "
                    + "The content safety filter is also active, ensuring no harmful code "
                    + "patterns are generated.";
            case CREATIVE -> "[Topic: " + topic + " | Routed to: " + category.model + "] "
                    + "Your creative writing request was routed to a model optimized for creativity. "
                    + "The RoutingLlmClient detects creative writing keywords (story, poem, imagine) "
                    + "and routes to a model with higher temperature settings for more "
                    + "imaginative responses.";
            case MATH -> "[Topic: " + topic + " | Routed to: " + category.model + "] "
                    + "Your math question was routed to a reasoning-focused model. "
                    + "Complex reasoning tasks benefit from models with chain-of-thought "
                    + "capabilities. The RoutingLlmClient sends these to models like o1 or "
                    + "Gemini 2.5 Pro that excel at step-by-step reasoning.";
            case GENERAL -> "[Topic: " + topic + " | Routed to: " + category.model + "] "
                    + "Your general question uses the default model — fast and cost-effective. "
                    + "The RoutingLlmClient only routes to specialized models when it detects "
                    + "specific content patterns. For general chat, the default model provides "
                    + "the best balance of speed and cost. "
                    + "Try asking a code question, a math problem, or requesting a creative story "
                    + "to see different routing in action.";
        };
    }

    private enum PromptCategory {
        CODE("gpt-4o (code-specialized)"),
        CREATIVE("claude-3.5-sonnet (creative)"),
        MATH("o1-mini (reasoning)"),
        GENERAL("gemini-2.5-flash (default)");

        final String model;

        PromptCategory(String model) {
            this.model = model;
        }
    }
}
