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
package org.atmosphere.a2a.processor;

import org.atmosphere.a2a.annotation.A2aServer;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.runtime.A2aHandler;
import org.atmosphere.a2a.runtime.A2aProtocolHandler;
import org.atmosphere.a2a.runtime.TaskManager;
import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

@AtmosphereAnnotation(A2aServer.class)
public class A2aServerProcessor implements Processor<Object> {

    private static final Logger logger = LoggerFactory.getLogger(A2aServerProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        try {
            var annotation = annotatedClass.getAnnotation(A2aServer.class);
            if (annotation == null) {
                return;
            }

            var instance = framework.newClassInstance(Object.class, annotatedClass);
            var registry = new A2aRegistry();
            registry.scan(instance);

            var taskManager = new TaskManager();
            var agentCard = registry.buildAgentCard(
                    annotation.name(), annotation.description(),
                    annotation.version(), annotation.endpoint());

            var protocolHandler = new A2aProtocolHandler(registry, taskManager, agentCard);
            var handler = new A2aHandler(protocolHandler);
            framework.addAtmosphereHandler(annotation.endpoint(), handler, new ArrayList<>());

            logger.info("A2A server '{}' v{} registered at {} - {} skills",
                    annotation.name(), annotation.version(), annotation.endpoint(),
                    registry.skills().size());
        } catch (Exception e) {
            logger.error("Failed to register A2A server from {}", annotatedClass.getName(), e);
        }
    }
}
