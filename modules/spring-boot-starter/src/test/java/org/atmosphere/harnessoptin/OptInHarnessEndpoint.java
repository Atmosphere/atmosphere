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
package org.atmosphere.harnessoptin;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.preset.Harness;

/**
 * Bare {@code @AiEndpoint} fixture that opts into the full harness via the
 * annotation alone — no app-wide harness config, no {@code @Agent} in the
 * scanned package. Any ACTIVE harness state the console reports for this app
 * can therefore only come from the per-endpoint {@code harness()} opt-in,
 * which is exactly what {@link HarnessOptInHttpE2eTest} pins.
 */
@AiEndpoint(path = "/atmosphere/optin-assistant", harness = {Harness.ALL})
public class OptInHarnessEndpoint {

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.send("ack: " + message);
        session.complete();
    }
}
