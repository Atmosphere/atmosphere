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

import org.atmosphere.ai.ExecutionHandle;
import org.atmosphere.ai.resume.RunRegistryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Tiny REST surface that pre-registers a run with a known set of buffered
 * events. Playwright's reattach spec calls this to get a deterministic
 * run id it can reconnect with — avoiding the scheduling-timing variable
 * that a true mid-stream disconnect test would carry. The reattach
 * contract under test is the HTTP header → request attribute → onReady
 * → replay wire; wall-clock timing isn't part of the contract.
 *
 * <p>The synthetic run runs against the same
 * {@link RunRegistryHolder#get()} the real {@code AiEndpointHandler}
 * consults, so the reconnect path goes through the identical production
 * code. The only thing synthetic is the producer — events are captured
 * up-front instead of during an active {@code @Prompt} turn.</p>
 */
@RestController
@RequestMapping("/harness")
public class SyntheticRunController {

    private static final Logger logger = LoggerFactory.getLogger(SyntheticRunController.class);

    /** Events the harness pre-captures so the Playwright assertion can be exact. */
    public static final List<String> SYNTHETIC_EVENTS = List.of(
            "replay-event-0", "replay-event-1", "replay-event-2");

    @PostMapping("/synthetic-run")
    public Map<String, Object> registerSyntheticRun() {
        // Register against the process-wide registry — same instance the
        // production reattach path consults on reconnect. The
        // ExecutionHandle is a no-op Settable; the harness never
        // actually runs, we just want the registry entry + replay buffer
        // to exist so AiEndpointHandler.onReady → replayPendingRun can
        // drain it on the next connection that carries the run id.
        var handle = RunRegistryHolder.get().register(
                "/atmosphere/agent/harness",
                // Use the anonymous marker so RunReattachSupport's
                // ownership check admits the reconnecting Playwright
                // client — the harness is explicitly open-mode for CI.
                "anonymous",
                "harness-resource",
                new ExecutionHandle.Settable(() -> { }));

        for (var payload : SYNTHETIC_EVENTS) {
            // Use the wire-protocol type name so the replay path emits a
            // valid AiStreamMessage JSON frame without translation —
            // matches RunEventCapturingSession's capture shape in
            // production.
            handle.replayBuffer().capture("streaming-text", payload);
        }
        handle.replayBuffer().capture("complete", "synthetic");

        logger.info("Synthetic run registered: runId={} events={}",
                handle.runId(), SYNTHETIC_EVENTS.size());

        return Map.of(
                "runId", handle.runId(),
                "events", SYNTHETIC_EVENTS,
                "total", SYNTHETIC_EVENTS.size() + 1);
    }

    /**
     * Companion route for terminal-error replay. The Playwright spec
     * drives this to prove the capturing session surfaces an
     * {@code error} envelope on reconnect — without it a client that
     * reconnects after the @Prompt method threw / timed out sees
     * partial text and hangs forever waiting for a terminator.
     */
    @PostMapping("/synthetic-error-run")
    public Map<String, Object> registerSyntheticErrorRun() {
        var handle = RunRegistryHolder.get().register(
                "/atmosphere/agent/harness",
                // Use the anonymous marker so RunReattachSupport's
                // ownership check admits the reconnecting Playwright
                // client — the harness is explicitly open-mode for CI.
                "anonymous",
                "harness-resource",
                new ExecutionHandle.Settable(() -> { }));

        handle.replayBuffer().capture("streaming-text", "partial before failure");
        handle.replayBuffer().capture("error",
                "Prompt processing timed out after 30000ms");

        logger.info("Synthetic error run registered: runId={}", handle.runId());
        return Map.of(
                "runId", handle.runId(),
                "total", 2);
    }
}
