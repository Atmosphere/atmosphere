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

import org.atmosphere.ai.StreamingSession;

import java.time.LocalDate;
import java.util.Map;

/**
 * Simulates the Embabel horoscope agent's multi-step workflow for demo/testing.
 * Shows step-by-step progress as the agent extracts the sign, finds events,
 * and generates the horoscope.
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    private static final Map<String, String> ZODIAC_DATES = Map.ofEntries(
            Map.entry("aries", "Mar 21 – Apr 19"),
            Map.entry("taurus", "Apr 20 – May 20"),
            Map.entry("gemini", "May 21 – Jun 20"),
            Map.entry("cancer", "Jun 21 – Jul 22"),
            Map.entry("leo", "Jul 23 – Aug 22"),
            Map.entry("virgo", "Aug 23 – Sep 22"),
            Map.entry("libra", "Sep 23 – Oct 22"),
            Map.entry("scorpio", "Oct 23 – Nov 21"),
            Map.entry("sagittarius", "Nov 22 – Dec 21"),
            Map.entry("capricorn", "Dec 22 – Jan 19"),
            Map.entry("aquarius", "Jan 20 – Feb 18"),
            Map.entry("pisces", "Feb 19 – Mar 20")
    );

    /**
     * @param userMessage the user's prompt
     * @param session     the streaming session
     * @param clientId    the AtmosphereResource UUID
     */
    public static void stream(String userMessage, StreamingSession session, String clientId) {
        try {
            var sign = extractSign(userMessage);

            // Step 1: Extract zodiac sign
            session.progress("Step 1/3: Extracting zodiac sign...");
            Thread.sleep(500);

            if (sign == null) {
                var response = "I need a zodiac sign to generate your horoscope! "
                        + "Try asking something like: \"What's my horoscope for Leo?\" or "
                        + "\"Horoscope for Pisces today\". "
                        + "Available signs: Aries, Taurus, Gemini, Cancer, Leo, Virgo, "
                        + "Libra, Scorpio, Sagittarius, Capricorn, Aquarius, Pisces.";
                streamWords(response, session);
                session.complete(response);
                return;
            }

            session.progress("Step 1/3: Found sign — " + capitalize(sign) + " (" + ZODIAC_DATES.get(sign) + ")");
            Thread.sleep(300);

            // Step 2: Find celestial events
            session.progress("Step 2/3: Finding celestial events for " + capitalize(sign) + "...");
            Thread.sleep(800);

            var events = getCelestialEvents(sign);
            session.progress("Step 2/3: Found celestial alignments");
            Thread.sleep(300);

            // Step 3: Generate horoscope
            session.progress("Step 3/3: Writing your personalized horoscope...");
            Thread.sleep(500);

            var horoscope = generateHoroscope(sign, events);
            streamWords(horoscope, session);
            session.complete(horoscope);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }

    private static void streamWords(String text, StreamingSession session) throws InterruptedException {
        var words = text.split("(?<=\\s)");
        for (var word : words) {
            session.send(word);
            Thread.sleep(40);
        }
    }

    private static String extractSign(String message) {
        var lower = message.toLowerCase();
        for (var sign : ZODIAC_DATES.keySet()) {
            if (lower.contains(sign)) {
                return sign;
            }
        }
        return null;
    }

    private static String getCelestialEvents(String sign) {
        return switch (sign) {
            case "aries" -> "Mars enters your ruling house, boosting confidence and drive";
            case "taurus" -> "Venus aligns with Jupiter, bringing abundance and harmony";
            case "gemini" -> "Mercury retrograde ends, clearing communication channels";
            case "cancer" -> "The Moon forms a trine with Neptune, enhancing intuition";
            case "leo" -> "The Sun conjuncts with Pluto, revealing hidden strengths";
            case "virgo" -> "Mercury sextiles Saturn, favoring detailed planning";
            case "libra" -> "Venus squares Mars, creating dynamic tension in relationships";
            case "scorpio" -> "Pluto trines the Moon, deepening emotional awareness";
            case "sagittarius" -> "Jupiter enters a new sign, expanding horizons";
            case "capricorn" -> "Saturn forms a grand trine, solidifying long-term goals";
            case "aquarius" -> "Uranus opposes the Sun, sparking unexpected breakthroughs";
            case "pisces" -> "Neptune conjuncts the Moon, amplifying creative vision";
            default -> "The stars align in your favor today";
        };
    }

    private static String generateHoroscope(String sign, String events) {
        var today = LocalDate.now();
        return "✨ " + capitalize(sign) + " Horoscope — " + today + " ✨\n\n"
                + "Celestial Influence: " + events + ".\n\n"
                + getHoroscopeText(sign)
                + "\n\n(Demo mode — set OPENAI_API_KEY for AI-generated horoscopes "
                + "powered by Embabel's multi-step agent pipeline)";
    }

    private static String getHoroscopeText(String sign) {
        return switch (sign) {
            case "aries" -> "Today brings a surge of energy and determination. "
                    + "Channel your natural leadership into a project that's been waiting for your spark. "
                    + "A bold move in the afternoon could open unexpected doors.";
            case "taurus" -> "The stars favor patience and persistence today. "
                    + "A financial opportunity may present itself — trust your instincts but do your due diligence. "
                    + "Evening brings comfort and connection with loved ones.";
            case "gemini" -> "Communication flows freely today as Mercury clears the static. "
                    + "A conversation you've been avoiding becomes surprisingly easy. "
                    + "Your quick wit serves you well in a creative brainstorming session.";
            case "cancer" -> "Your intuition is razor-sharp today. Pay attention to gut feelings, "
                    + "especially regarding a decision that affects your home or family. "
                    + "The evening is perfect for nurturing your closest relationships.";
            case "leo" -> "The spotlight finds you naturally today. "
                    + "Your confidence inspires others — use this influence wisely. "
                    + "A creative endeavor gains momentum, and recognition may follow soon.";
            case "virgo" -> "Details matter more than ever today. "
                    + "Your analytical mind catches something others miss — trust that insight. "
                    + "Health and wellness routines pay off; stay consistent.";
            case "libra" -> "Balance is your superpower today, and you'll need it. "
                    + "A diplomatic approach resolves a lingering disagreement. "
                    + "Beauty and art bring unexpected inspiration this evening.";
            case "scorpio" -> "Deep emotions surface today, bringing clarity to a long-standing question. "
                    + "Your intensity is an asset — channel it into transformation rather than confrontation. "
                    + "A secret ally reveals themselves.";
            case "sagittarius" -> "Adventure beckons! Whether physical or intellectual, "
                    + "explore something new today. A philosophical conversation sparks an important insight. "
                    + "Travel plans may solidify in unexpected ways.";
            case "capricorn" -> "Discipline meets opportunity today. "
                    + "Your long-term planning pays dividends as a goal moves within reach. "
                    + "Don't forget to celebrate small victories along the way.";
            case "aquarius" -> "Innovation is your theme today. "
                    + "A radical idea you've been nurturing finds receptive ears. "
                    + "Community connections strengthen, and a group effort exceeds expectations.";
            case "pisces" -> "Creativity flows like a river today — let it carry you. "
                    + "Artistic expression brings healing and joy. "
                    + "Dreams tonight may carry important messages; keep a journal nearby.";
            default -> "The universe holds a special message for you today.";
        };
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
