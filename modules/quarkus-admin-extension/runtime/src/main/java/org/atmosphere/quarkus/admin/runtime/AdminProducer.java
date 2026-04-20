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
package org.atmosphere.quarkus.admin.runtime;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.atmosphere.admin.AdminEventHandler;
import org.atmosphere.admin.AdminEventProducer;
import org.atmosphere.admin.AtmosphereAdmin;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.quarkus.runtime.LazyAtmosphereConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI producer for the {@link AtmosphereAdmin} facade in Quarkus.
 * Eagerly initialized at startup via {@link Startup} to ensure the
 * Atmosphere framework is available.
 *
 * @since 4.0
 */
@Startup
@ApplicationScoped
public class AdminProducer {

    private static final Logger logger = LoggerFactory.getLogger(AdminProducer.class);

    private volatile AtmosphereAdmin admin;

    @Produces
    @Singleton
    public AtmosphereAdmin atmosphereAdmin() {
        if (admin != null) {
            return admin;
        }

        // Wait for framework — it may not be ready yet at first call
        AtmosphereFramework framework = null;
        for (int i = 0; i < 50; i++) {
            framework = LazyAtmosphereConfigurator.getFramework();
            if (framework != null) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (framework == null) {
            logger.warn("Atmosphere Admin: framework not available — admin disabled");
            admin = new AtmosphereAdmin(null, 1000);
            return admin;
        }

        admin = new AtmosphereAdmin(framework, 1000);
        // Default authorizer: REQUIRE_PRINCIPAL — Jakarta Security must
        // resolve a non-anonymous Principal for any /api/admin/* write.
        // Operators wire a custom ControlAuthorizer via CDI (or disable
        // via atmosphere.admin.http-write-enabled=false). Fail-closed
        // per Correctness Invariant #6.
        admin.setAuthorizer(org.atmosphere.admin.ControlAuthorizer.REQUIRE_PRINCIPAL);

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
        logger.info("Atmosphere Admin dashboard at /admin/");
        return admin;
    }
}
