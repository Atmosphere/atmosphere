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
package org.atmosphere.samples.springboot.embabelhoroscope;

import com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Embabel Horoscope sample application.
 *
 * <p>Excludes {@link AgentPlatformAutoConfiguration} so the app starts in demo
 * mode without a real LLM provider. When a valid {@code OPENAI_API_KEY} is set,
 * remove the exclusion to enable the full Embabel agent pipeline.</p>
 */
@SpringBootApplication(exclude = AgentPlatformAutoConfiguration.class)
public class EmbabelHoroscopeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmbabelHoroscopeApplication.class, args);
    }
}
