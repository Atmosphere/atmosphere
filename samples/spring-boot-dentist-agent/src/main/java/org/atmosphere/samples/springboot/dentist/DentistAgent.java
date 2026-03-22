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

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.annotation.Command;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dr. Molar — an emergency dental assistant agent.
 *
 * <p>Demonstrates {@code @Agent} with:</p>
 * <ul>
 *   <li>Skill file for system prompt + metadata</li>
 *   <li>Slash commands for quick actions</li>
 *   <li>AI tools for structured assessment</li>
 *   <li>Slack and Telegram channel support</li>
 * </ul>
 */
@Agent(name = "dentist",
        skillFile = "prompts/dentist-skill.md",
        description = "Emergency dental assistant — helps with broken teeth and dental emergencies")
public class DentistAgent {

    private static final Logger logger = LoggerFactory.getLogger(DentistAgent.class);

    @Ready
    public void onReady(AtmosphereResource resource) {
        logger.info("Patient {} connected to Dr. Molar", resource.uuid());
    }

    @Disconnect
    public void onDisconnect(AtmosphereResourceEvent event) {
        logger.info("Patient {} disconnected", event.getResource().uuid());
    }

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Patient message: {}", message);
        var settings = AiConfig.get();
        if (settings == null || settings.client().apiKey() == null
                || settings.client().apiKey().isBlank()) {
            DemoResponseProducer.stream(message, session);
            return;
        }
        session.stream(message);
    }

    // --- Slash Commands ---

    @Command(value = "/firstaid",
            description = "Quick first-aid steps for a broken tooth")
    public String firstAid() {
        return """
                Broken Tooth First Aid:
                1. Rinse your mouth gently with warm water
                2. Apply a cold compress to the outside of your cheek (20 min on, 20 min off)
                3. If bleeding, bite down gently on gauze or a wet tea bag
                4. Save any broken pieces — keep them moist in milk or saliva
                5. Take ibuprofen for pain (follow package directions)
                6. Cover sharp edges with dental wax or sugar-free gum
                7. See a dentist within 24 hours — sooner if pain is severe

                Type /urgency to check if you need immediate care.""";
    }

    @Command(value = "/urgency",
            description = "Help determine how urgently you need a dentist")
    public String urgency() {
        return """
                How urgent is your situation? Check these:

                GO TO ER NOW if:
                - Heavy, uncontrolled bleeding
                - Difficulty breathing or swallowing
                - Suspected jaw fracture
                - Tooth knocked into airway

                SEE DENTIST TODAY if:
                - Tooth completely knocked out (bring the tooth!)
                - Severe pain that OTC meds don't help
                - Large piece broken off with nerve exposed (pink/red spot)
                - Tooth is very loose or displaced

                SEE DENTIST WITHIN 1-2 DAYS if:
                - Small chip with no pain
                - Mild sensitivity to hot/cold
                - Cracked tooth with manageable pain

                Describe your symptoms and I'll help assess further.""";
    }

    @Command(value = "/pain",
            description = "Pain management tips")
    public String pain() {
        return """
                Pain Management for Dental Emergencies:

                OTC Options:
                - Ibuprofen (Advil/Motrin): best for dental pain — reduces inflammation
                - Acetaminophen (Tylenol): if you can't take ibuprofen
                - You can alternate both (not at same time)

                Home Remedies:
                - Cold compress: 20 min on, 20 min off
                - Salt water rinse: 1/2 tsp salt in 8oz warm water
                - Clove oil on a cotton ball: natural numbing
                - Elevate your head when sleeping

                AVOID:
                - Aspirin directly on gums (causes burns)
                - Very hot or cold foods
                - Chewing on the injured side
                - Poking at the break with your tongue

                If pain is 8+ out of 10, seek emergency dental care.""";
    }

    // --- AI Tools ---

    @AiTool(name = "assess_emergency",
            description = "Classify the severity of a dental emergency based on symptoms")
    public String assessEmergency(
            @Param(value = "injury_type",
                    description = "Type: chipped, cracked, broken, knocked_out, loose") String injuryType,
            @Param(value = "pain_level",
                    description = "Pain level 1-10") String painLevel,
            @Param(value = "bleeding",
                    description = "Is there bleeding? yes/no/heavy") String bleeding) {

        int pain;
        try {
            pain = Integer.parseInt(painLevel);
        } catch (NumberFormatException e) {
            pain = 5;
        }

        var severity = classifySeverity(injuryType, pain, bleeding);
        return "Assessment: " + severity.level + " — " + severity.recommendation
                + " (injury: " + injuryType + ", pain: " + pain + "/10, bleeding: " + bleeding + ")";
    }

    @AiTool(name = "pain_relief",
            description = "Recommend appropriate pain management for the patient's situation")
    public String painRelief(
            @Param(value = "pain_level",
                    description = "Pain level 1-10") String painLevel,
            @Param(value = "allergies",
                    description = "Known allergies (e.g. nsaids, none)") String allergies) {

        int pain;
        try {
            pain = Integer.parseInt(painLevel);
        } catch (NumberFormatException e) {
            pain = 5;
        }

        var hasNsaidAllergy = allergies.toLowerCase().contains("nsaid")
                || allergies.toLowerCase().contains("ibuprofen");

        if (pain <= 3) {
            return "Mild pain: cold compress and salt water rinse should help. "
                    + "OTC acetaminophen if needed.";
        }
        if (pain <= 6) {
            if (hasNsaidAllergy) {
                return "Moderate pain with NSAID allergy: take acetaminophen (Tylenol) "
                        + "as directed. Apply cold compress 20 min on/off. Clove oil may help.";
            }
            return "Moderate pain: ibuprofen 400mg every 6 hours with food. "
                    + "Cold compress 20 min on/off. Salt water rinse after meals.";
        }
        return "Severe pain (level " + pain + "): alternate ibuprofen and acetaminophen "
                + "(stagger by 3 hours). Cold compress continuously. "
                + "Seek emergency dental care today — this level of pain may indicate "
                + "nerve exposure or infection.";
    }

    private record Severity(String level, String recommendation) {
    }

    private Severity classifySeverity(String injuryType, int pain, String bleeding) {
        if ("heavy".equalsIgnoreCase(bleeding)) {
            return new Severity("EMERGENCY",
                    "Go to the ER immediately for heavy bleeding control");
        }
        if ("knocked_out".equalsIgnoreCase(injuryType)) {
            return new Severity("URGENT",
                    "See a dentist within 30 minutes. Keep tooth moist in milk!");
        }
        if (pain >= 8 || "broken".equalsIgnoreCase(injuryType)) {
            return new Severity("SAME-DAY",
                    "See a dentist today. Possible nerve exposure.");
        }
        if ("cracked".equalsIgnoreCase(injuryType) || pain >= 5) {
            return new Severity("SOON",
                    "See a dentist within 1-2 days. Manage pain with OTC meds.");
        }
        return new Severity("ROUTINE",
                "Schedule a dental appointment this week. Apply dental wax to sharp edges.");
    }
}
