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
package org.atmosphere.samples.springboot.checkpoint;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the checkpoint-agent sample.
 *
 * <p>Demonstrates durable agent execution using {@code atmosphere-checkpoint}:
 * an approval-gated workflow where the coordinator hands off work to a
 * specialist, the specialist's result is persisted as a snapshot, and the
 * workflow can be resumed later from that snapshot (simulating a human
 * approval step that may span minutes, hours, or days).</p>
 */
@SpringBootApplication
public class CheckpointAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckpointAgentApplication.class, args);
    }
}
