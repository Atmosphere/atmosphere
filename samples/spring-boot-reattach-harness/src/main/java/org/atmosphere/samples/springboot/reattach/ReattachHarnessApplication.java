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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Deterministic harness for the mid-stream reattach wire. Used by the
 * {@code e2e/tests/reattach.spec.ts} Playwright spec to drive the
 * full HTTP → {@code RunRegistry} → {@code RunEventReplayBuffer} →
 * {@code RunReattachSupport} chain without timing flakiness.
 *
 * <p>The sample exposes two surfaces:</p>
 * <ol>
 *   <li>{@link SlowEmitterChat} — an {@code @AiEndpoint} whose
 *       {@code @Prompt} method emits events on a fixed 500ms cadence
 *       via {@code session.send(...)}. Matches the literal harness spec
 *       ChefFamille asked for; useful for manual verification and
 *       future timing-tolerant integration tests.</li>
 *   <li>{@link SyntheticRunController} — a tiny REST surface that
 *       pre-registers a run with a known set of buffered events and
 *       returns the run id. Playwright hits this for deterministic CI
 *       coverage — the reattach contract is about the header-to-replay
 *       wire, not about wall-clock timing, so removing the scheduling
 *       variable removes flake without weakening what's proven.</li>
 * </ol>
 */
@SpringBootApplication
public class ReattachHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReattachHarnessApplication.class, args);
    }
}
