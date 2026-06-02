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
package org.atmosphere.spring.boot;

import org.atmosphere.interactions.Interaction;
import org.atmosphere.interactions.InteractionStep;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;

/**
 * Shared wire frames + channel naming for the live interaction stream, used by
 * both {@link InteractionStreamBroadcast} (producer) and
 * {@link InteractionStreamHandler} (connect-time replay). Keeping them in one
 * place guarantees the replayed catch-up frames and the live frames are
 * byte-identical, so the browser dedupes cleanly by step sequence.
 */
final class InteractionStreamFrames {

    /** Atmosphere handler mapping the browser connects to (with {@code ?id=}). */
    static final String STREAM_PATH = "/atmosphere/interactions-stream";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InteractionStreamFrames() {
    }

    /** Per-interaction broadcaster id (a synthetic channel, not a request path). */
    static String channelId(String interactionId) {
        return "interaction-stream/" + interactionId;
    }

    /** {@code {"type":"interaction-step","step":{...}}} */
    static String stepFrame(InteractionStep step) {
        var frame = new LinkedHashMap<String, Object>();
        frame.put("type", "interaction-step");
        frame.put("step", step);
        return MAPPER.writeValueAsString(frame);
    }

    /** {@code {"type":"interaction-terminal","status":...,"finalText":...}} */
    static String terminalFrame(Interaction interaction) {
        var frame = new LinkedHashMap<String, Object>();
        frame.put("type", "interaction-terminal");
        frame.put("id", interaction.id());
        frame.put("status", interaction.status().name());
        frame.put("finalText", interaction.finalText());
        frame.put("errorMessage", interaction.errorMessage());
        return MAPPER.writeValueAsString(frame);
    }
}
