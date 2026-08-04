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
package org.atmosphere.samples.springboot.dentist;

import jakarta.annotation.PostConstruct;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.llm.DemoAgentRuntime;
import org.springframework.stereotype.Component;

/**
 * Installs Dr. Molar's canned responses as the {@link DemoAgentRuntime}
 * strategy so the sample answers in persona when it boots without an
 * {@code LLM_API_KEY}. Runs at startup; the framework then drives every
 * demo-mode request through the standard pipeline like any real runtime —
 * the SKILL.md {@code ## Guardrails} scope policy, memory, metrics, tape
 * and dev inspector all fire exactly as they would against a real provider.
 */
@Component
public class DemoResponseProducer {

    @PostConstruct
    public void installDentistStrategy() {
        DemoAgentRuntime.setResponseStrategy(DemoResponseProducer::generateFor);
    }

    /**
     * Keyword-branched Dr. Molar persona responses, keyed on the incoming
     * {@link AgentExecutionContext#message()}. Package-private so the unit
     * test can drive the persona branches directly.
     */
    static String generateFor(AgentExecutionContext context) {
        var lower = context.message() == null ? "" : context.message().toLowerCase();

        if (lower.contains("broke") || lower.contains("broken") || lower.contains("cracked")) {
            return "I'm sorry to hear about your broken tooth! That can be really "
                    + "stressful. First, don't panic — most broken teeth can be repaired. "
                    + "Rinse your mouth gently with warm water and apply a cold compress "
                    + "to reduce swelling. If you have the broken piece, save it in milk. "
                    + "Try /firstaid for detailed steps, or /urgency to check how soon "
                    + "you need to see a dentist. Remember, I'm an AI assistant — please "
                    + "see a real dentist as soon as possible!";
        }

        if (lower.contains("pain") || lower.contains("hurt") || lower.contains("ache")) {
            return "I understand you're in pain — dental pain can be really intense. "
                    + "For immediate relief, try ibuprofen (Advil) if you can take it, "
                    + "and apply a cold compress to the outside of your cheek. "
                    + "Type /pain for detailed pain management tips. "
                    + "On a scale of 1-10, how bad is your pain? This will help me "
                    + "guide you better.";
        }

        if (lower.contains("bleeding") || lower.contains("blood")) {
            return "Bleeding from a dental injury needs attention. Bite down gently "
                    + "on a piece of gauze or a moistened tea bag for 15-20 minutes. "
                    + "If the bleeding is heavy and won't stop after 20 minutes of "
                    + "pressure, please go to the emergency room. For light bleeding, "
                    + "it should subside — but still see a dentist soon.";
        }

        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm Dr. Molar, your emergency dental assistant. "
                    + "I can help with broken teeth, dental pain, and emergency guidance. "
                    + "Tell me what happened, or try /help to see what I can do. "
                    + "Remember, I'm an AI — always follow up with a real dentist!";
        }

        return "I'm Dr. Molar, your dental emergency assistant (running in demo mode). "
                + "I can help with broken, chipped, or cracked teeth. "
                + "Tell me what happened to your tooth, or try these commands:\n"
                + "- /firstaid — Quick first-aid steps\n"
                + "- /urgency — Check how urgently you need care\n"
                + "- /pain — Pain management tips\n"
                + "- /help — See all commands\n\n"
                + "Set LLM_API_KEY for full AI-powered responses!";
    }
}
