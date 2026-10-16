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

import org.atmosphere.ai.AiConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes {@link PacedModelClient} the model of this application: the
 * application's own {@link AiConfig.LlmSettings} bean, which the starter adopts
 * in place of the settings it builds from {@code LLM_*}, so the harness answers
 * the same way whatever keys the host exports. The mode is {@code local}: the
 * model runs in this process and needs no key, so the built-in runtime serves
 * it rather than the canned replies of the demo runtime.
 */
@Configuration(proxyBeanMethods = false)
public class HarnessModelConfiguration {

    @Bean
    PacedModelClient pacedModelClient() {
        return new PacedModelClient();
    }

    @Bean
    AiConfig.LlmSettings harnessModelSettings(PacedModelClient pacedModelClient) {
        return new AiConfig.LlmSettings(pacedModelClient, PacedModelClient.MODEL, "local", null, null);
    }
}
