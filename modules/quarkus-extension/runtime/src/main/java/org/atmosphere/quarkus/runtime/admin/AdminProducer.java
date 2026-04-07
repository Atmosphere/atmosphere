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
package org.atmosphere.quarkus.runtime.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.atmosphere.admin.AdminEventHandler;
import org.atmosphere.admin.AdminEventProducer;
import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI producer for the {@link AtmosphereAdmin} facade in Quarkus.
 * Creates the admin bean lazily when first injected, after the
 * Atmosphere framework is initialized.
 *
 * @since 4.0
 */
@ApplicationScoped
public class AdminProducer {

    private static final Logger logger = LoggerFactory.getLogger(AdminProducer.class);

    @Produces
    @Singleton
    public AtmosphereAdmin atmosphereAdmin() {
        var framework = LazyAtmosphereConfigurator.getFramework();
        if (framework == null) {
            throw new IllegalStateException(
                    "AtmosphereFramework not initialized yet — "
                            + "ensure quarkus.atmosphere.load-on-startup > 0");
        }

        var admin = new AtmosphereAdmin(framework, 1000);

        // Register the admin event WebSocket handler
        var handler = new AdminEventHandler();
        var interceptors = new java.util.LinkedList<org.atmosphere.cpr.AtmosphereInterceptor>();
        org.atmosphere.annotation.AnnotationUtil
                .defaultManagedServiceInterceptors(framework, interceptors);
        framework.addAtmosphereHandler(
                AdminEventHandler.ADMIN_BROADCASTER_ID, handler, interceptors);

        // Install the event producer
        var producer = new AdminEventProducer(framework);
        producer.install();

        // Wire optional AI runtime controller
        try {
            Class.forName("org.atmosphere.ai.AgentRuntimeResolver");
            admin.setAiRuntimeController(
                    new org.atmosphere.admin.ai.AiRuntimeController());
        } catch (ClassNotFoundException ignored) {
            // atmosphere-ai not on classpath
        }

        logger.info("Atmosphere Admin control plane enabled at /api/admin/*");
        logger.info("Atmosphere Admin dashboard at /atmosphere/admin/");
        return admin;
    }
}
