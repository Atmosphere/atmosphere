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
package org.atmosphere.samples.springboot.embabelhoroscope;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;

/**
 * Embabel horoscope agent with multi-step workflow.
 *
 * <p>Ported from the official embabel-agent-examples horoscope sample.
 * The agent performs three sequential actions:</p>
 * <ol>
 *   <li>{@code extractSign} — identifies the zodiac sign from user input</li>
 *   <li>{@code findEvents} — discovers relevant celestial events</li>
 *   <li>{@code generateHoroscope} — creates the personalized horoscope</li>
 * </ol>
 *
 * <p>Each step streams progress updates via Atmosphere so the user sees
 * real-time feedback as the agent works through its pipeline.</p>
 */
@Agent(name = "horoscope-agent",
       description = "Generates personalized horoscopes based on zodiac signs")
public class HoroscopeAgent {

    @Action(description = "Extract the zodiac sign from user input")
    public String extractSign(String userMessage) {
        return "Extract the zodiac sign from this message and return just the sign name: " + userMessage;
    }

    @Action(description = "Find relevant celestial events for the zodiac sign")
    public String findEvents(String zodiacSign) {
        return "Find current celestial events and planetary alignments relevant to " + zodiacSign;
    }

    @Action(description = "Generate a personalized horoscope writeup")
    public String generateHoroscope(String signAndEvents) {
        return "Create a brief, insightful horoscope based on: " + signAndEvents;
    }
}
