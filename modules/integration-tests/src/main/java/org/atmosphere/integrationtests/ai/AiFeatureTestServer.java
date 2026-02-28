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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.integrationtests.EmbeddedAtmosphereServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded Jetty server with all AI feature test endpoints registered.
 * Used by Playwright E2E tests via exec:java.
 */
public class AiFeatureTestServer {

    private static final Logger logger = LoggerFactory.getLogger(AiFeatureTestServer.class);

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("server.port", 8090);

        var server = new EmbeddedAtmosphereServer()
                .withPort(port)
                .withInitParam(ApplicationConfig.ANNOTATION_PACKAGE, "NONE")
                .withInitParam(ApplicationConfig.WEBSOCKET_SUPPORT, "true");
        server.start();

        var framework = server.getFramework();

        // Register AI test handlers
        framework.addAtmosphereHandler("/ai/filters", new FilterTestHandler());
        framework.addAtmosphereHandler("/ai/fanout", new FanOutTestHandler());
        framework.addAtmosphereHandler("/ai/cache", new CacheTestHandler());
        framework.addAtmosphereHandler("/ai/routing", new RoutingTestHandler());
        framework.addAtmosphereHandler("/ai/budget", new BudgetTestHandler());
        framework.addAtmosphereHandler("/ai/cache-coalescing", new CacheCoalescingTestHandler());
        framework.addAtmosphereHandler("/ai/cost-routing", new CostLatencyRoutingTestHandler());
        framework.addAtmosphereHandler("/ai/combined-cost-cache", new CombinedCostCacheTestHandler());
        framework.addAtmosphereHandler("/ai/classroom/math", new ClassroomTestHandler("math"));
        framework.addAtmosphereHandler("/ai/classroom/code", new ClassroomTestHandler("code"));

        logger.info("AI Feature Test Server started on port {}", server.getPort());
        logger.info("Endpoints: /ai/filters, /ai/fanout, /ai/cache, /ai/routing, /ai/budget, "
                + "/ai/cache-coalescing, /ai/cost-routing, /ai/combined-cost-cache, "
                + "/ai/classroom/math, /ai/classroom/code");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (Exception e) {
                logger.warn("Error stopping server", e);
            }
        }));

        Thread.currentThread().join();
    }
}
