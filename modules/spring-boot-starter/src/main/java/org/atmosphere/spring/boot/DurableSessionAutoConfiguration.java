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

import java.time.Duration;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.session.DurableSessionInterceptor;
import org.atmosphere.session.InMemorySessionStore;
import org.atmosphere.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Atmosphere durable sessions.
 *
 * <p>Activated when {@code atmosphere.durable-sessions.enabled=true}
 * and the {@code atmosphere-durable-sessions} JAR is on the classpath.
 * If no {@link SessionStore} bean is provided, an {@link InMemorySessionStore}
 * is created as a fallback (useful for development).</p>
 *
 * <p>For production, add {@code atmosphere-durable-sessions-sqlite} or
 * {@code atmosphere-durable-sessions-redis} and declare a {@link SessionStore}
 * bean.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(SessionStore.class)
@ConditionalOnProperty(prefix = "atmosphere.durable-sessions", name = "enabled", havingValue = "true")
public class DurableSessionAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(DurableSessionAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SessionStore sessionStore() {
        logger.info("No SessionStore bean found — using InMemorySessionStore (sessions will not survive restart)");
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public DurableSessionInterceptor durableSessionInterceptor(
            SessionStore store,
            AtmosphereFramework framework,
            AtmosphereProperties properties) {
        var dsProps = properties.getDurableSessions();
        var ttl = Duration.ofMinutes(dsProps.getSessionTtlMinutes());
        var cleanup = Duration.ofSeconds(dsProps.getCleanupIntervalSeconds());

        var interceptor = new DurableSessionInterceptor(store, ttl, cleanup);
        framework.interceptor(interceptor);

        logger.info("Durable sessions enabled (ttl={}, cleanup={})", ttl, cleanup);
        return interceptor;
    }
}
