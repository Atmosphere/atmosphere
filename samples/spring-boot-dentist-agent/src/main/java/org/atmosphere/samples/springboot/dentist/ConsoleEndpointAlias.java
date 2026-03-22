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
package org.atmosphere.samples.springboot.dentist;

import org.atmosphere.annotation.AnnotationUtil;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;

/**
 * Registers the agent handler at {@code /atmosphere/ai-chat} so the built-in
 * console routes to Dr. Molar.
 */
@Component
public class ConsoleEndpointAlias {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleEndpointAlias.class);

    ConsoleEndpointAlias(AtmosphereFramework framework) {
        framework.getAtmosphereConfig().startupHook(f -> {
            var agentPath = "/atmosphere/agent/dentist";
            var consolePath = "/atmosphere/ai-chat";

            var agentEntry = f.getAtmosphereHandlers().get(agentPath);
            if (agentEntry == null) {
                return;
            }

            List<AtmosphereInterceptor> interceptors = new LinkedList<>();
            AnnotationUtil.defaultManagedServiceInterceptors(f, interceptors);

            f.addAtmosphereHandler(consolePath,
                    agentEntry.atmosphereHandler(), interceptors);
            logger.info("Console path {} routes to Dr. Molar agent", consolePath);
        });
    }
}
