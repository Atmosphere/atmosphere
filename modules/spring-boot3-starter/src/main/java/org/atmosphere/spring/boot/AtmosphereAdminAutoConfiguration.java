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
package org.atmosphere.spring.boot;

import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.admin.a2a.TaskController;
import org.atmosphere.admin.ai.AiRuntimeController;
import org.atmosphere.admin.coordinator.CoordinatorController;
import org.atmosphere.admin.mcp.McpController;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that wires the Atmosphere Admin control plane when
 * {@code atmosphere-admin} is on the classpath.
 *
 * <p>Creates the {@link AtmosphereAdmin} facade and optional subsystem
 * controllers (coordinator, A2A tasks, AI runtimes, MCP registry) based
 * on classpath detection.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(AtmosphereAdmin.class)
@ConditionalOnBean(AtmosphereFramework.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "atmosphere.admin.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereAdminAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereAdminAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AtmosphereAdmin atmosphereAdmin(AtmosphereFramework framework) {
        var admin = new AtmosphereAdmin(framework, 1000);
        logger.info("Atmosphere Admin control plane enabled at /api/admin/*");
        return admin;
    }

    /**
     * Wires the AI runtime controller when the AI module is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.ai.AgentRuntimeResolver")
    static class AiAdminConfiguration {

        @Bean
        AiRuntimeController atmosphereAdminAiRuntimeController(AtmosphereAdmin admin) {
            var controller = new AiRuntimeController();
            admin.setAiRuntimeController(controller);
            logger.debug("Atmosphere Admin: AI runtime controller wired");
            return controller;
        }
    }

    /**
     * Wires the MCP controller when the MCP module is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.mcp.registry.McpRegistry")
    static class McpAdminConfiguration {

        @Bean
        McpController atmosphereAdminMcpController(AtmosphereAdmin admin,
                                                    org.atmosphere.mcp.registry.McpRegistry registry) {
            var controller = new McpController(registry);
            admin.setMcpController(controller);
            logger.debug("Atmosphere Admin: MCP controller wired");
            return controller;
        }
    }

    /**
     * Wires the A2A task controller when the A2A module is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.a2a.runtime.TaskManager")
    static class A2aAdminConfiguration {

        @Bean
        TaskController atmosphereAdminTaskController(AtmosphereAdmin admin,
                                                      org.atmosphere.a2a.runtime.TaskManager taskManager) {
            var controller = new TaskController(taskManager);
            admin.setTaskController(controller);
            logger.debug("Atmosphere Admin: A2A task controller wired");
            return controller;
        }
    }

    /**
     * Wires the coordinator controller when the coordinator module is available.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.atmosphere.coordinator.fleet.AgentFleet")
    static class CoordinatorAdminConfiguration {

        @Bean
        CoordinatorController atmosphereAdminCoordinatorController(AtmosphereAdmin admin) {
            // Fleet instances are injected into coordinator handlers at startup,
            // not stored in a global bean. The controller is created with empty
            // fleets and populated later via the framework startup hook.
            var controller = new CoordinatorController(
                    java.util.Map.of(),
                    org.atmosphere.coordinator.journal.CoordinationJournal.NOOP);
            admin.setCoordinatorController(controller);
            logger.debug("Atmosphere Admin: Coordinator controller wired");
            return controller;
        }
    }
}
