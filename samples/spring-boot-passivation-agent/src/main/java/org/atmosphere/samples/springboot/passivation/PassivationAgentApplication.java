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
package org.atmosphere.samples.springboot.passivation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the passivation-agent sample.
 *
 * <p>Proves the Atmosphere 4 PASSIVATION capability end to end: a paused
 * agent conversation is snapshotted into a durable
 * {@link org.atmosphere.checkpoint.CheckpointStore} and, when an external
 * signal arrives (here, an HTTP call standing in for "human approval
 * granted"), the conversation is rehydrated and the agent continues from
 * exactly where it left off — same system prompt, same history, same
 * identity columns — rather than restarting cold.</p>
 *
 * <p>The pause/resume trigger is a plain REST controller, not an
 * {@code @AiEndpoint} or {@code @Agent} streaming surface. That is the
 * documented shape: {@code AiCapability.PASSIVATION} is application policy,
 * not a user-facing endpoint — the application decides <em>when</em> to
 * pause and on which signal to resume. See
 * {@link org.atmosphere.checkpoint.AgentPassivation}.</p>
 */
@SpringBootApplication
public class PassivationAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PassivationAgentApplication.class, args);
    }
}
