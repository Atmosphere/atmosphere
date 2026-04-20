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
package org.atmosphere.samples.springboot.reattach;

import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.ai.annotation.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code @AiEndpoint} whose {@code @Prompt} emits six events on a fixed
 * 500ms cadence — the deterministic slow-LLM stand-in the reattach
 * harness needs. Every {@code session.send(...)} flows through
 * {@code RunEventCapturingSession}, landing in the run's
 * {@code RunEventReplayBuffer} so a reconnecting client carrying
 * {@code X-Atmosphere-Run-Id} can replay what it missed.
 *
 * <p>Path: {@code /atmosphere/agent/harness}. The prompt content is
 * ignored — the harness is about the wire, not the semantics.</p>
 */
@AiEndpoint(path = "/atmosphere/agent/harness",
        requires = {AiCapability.TEXT_STREAMING})
public class SlowEmitterChat {

    private static final Logger logger = LoggerFactory.getLogger(SlowEmitterChat.class);

    /** Events per turn. Fixed so the Playwright spec can assert exact replay counts. */
    public static final int EVENTS_PER_TURN = 6;

    /** Fixed cadence — 500ms between sends. Total turn: {@value #EVENTS_PER_TURN} × 500ms = 3s. */
    public static final long EVENT_INTERVAL_MS = 500L;

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        logger.info("Harness @Prompt received: {} (runId={})", message, session.runId().orElse("-"));
        try {
            for (int i = 0; i < EVENTS_PER_TURN; i++) {
                session.send("event-" + i);
                if (i < EVENTS_PER_TURN - 1) {
                    Thread.sleep(EVENT_INTERVAL_MS);
                }
            }
            session.complete("done");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.error(e);
        }
    }
}
