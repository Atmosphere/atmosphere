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
package org.atmosphere.samples.springboot.onedepagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot entry point for the "one dependency + a single {@code @Agent} class"
 * sample. This is the only class besides {@link ChatAgent}; it does nothing but
 * hand control to Spring Boot, which — via the single
 * {@code atmosphere-ai-spring-boot-starter} dependency — auto-configures the
 * embedded servlet container, the Atmosphere runtime + Console, the AI pipeline,
 * and the {@code AgentProcessor} that registers {@link ChatAgent} as a live
 * streaming web endpoint.
 *
 * <p>With no {@code LLM_API_KEY} configured the framework's built-in demo
 * runtime streams a canned response token-by-token, so the app is a running
 * streaming chat app out of the box, keyless. Set {@code LLM_API_KEY} (and
 * optionally {@code LLM_MODEL} / {@code LLM_BASE_URL}) to stream from a real
 * OpenAI-compatible provider without touching the code.</p>
 */
@SpringBootApplication
public class OneDepAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OneDepAgentApplication.class, args);
    }
}
