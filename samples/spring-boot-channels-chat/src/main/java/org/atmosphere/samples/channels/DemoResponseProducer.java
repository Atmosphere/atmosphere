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
package org.atmosphere.samples.channels;

import org.atmosphere.ai.StreamingSession;

/**
 * Simulated streaming for demo mode (no LLM API key configured).
 */
public final class DemoResponseProducer {

    private DemoResponseProducer() {
    }

    public static void stream(String userMessage, StreamingSession session) {
        var response = "Hello! I'm the **omnichannel AI assistant**, powered by **Atmosphere**.\n\n"
                + "I'm available on:\n"
                + "1. **Web** — WebSocket streaming (you're here!)\n"
                + "2. **Telegram** — via Bot API\n"
                + "3. **Slack** — via Events API + Block Kit\n"
                + "4. **Discord** — via Bot API\n"
                + "5. **WhatsApp** — via Cloud API\n\n"
                + "Same AI, every channel. Set `LLM_API_KEY` to connect a real LLM.";
        var words = response.split("(?<=\\s)");

        try {
            session.progress("Demo mode — no LLM_API_KEY configured");
            for (var word : words) {
                session.send(word);
                Thread.sleep(30);
            }
            session.complete(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }
}
