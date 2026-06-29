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
package org.atmosphere.samples.springboot.springaiadvisors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the Spring AI advisors sample.
 *
 * <p>Demonstrates binding your own Spring AI {@code ChatClient} to Atmosphere
 * with {@code SpringAiAgentRuntime.setChatClient(...)}: Atmosphere keeps the
 * {@code defaultAdvisors(...)} you configured, and you can attach additional
 * advisors per request. See {@link BoundChatClientConfig}.</p>
 */
@SpringBootApplication
public class SpringAiAdvisorsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiAdvisorsApplication.class, args);
    }
}
