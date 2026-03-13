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

import org.atmosphere.cache.BoundedMemoryCache;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.interceptor.MessageAckInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers a {@link BoundedMemoryCache} and
 * {@link MessageAckInterceptor} when message caching is enabled.
 *
 * <p>Enable via {@code atmosphere.cache.enabled=true} in application properties.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(BoundedMemoryCache.class)
@ConditionalOnProperty(prefix = "atmosphere.cache", name = "enabled", havingValue = "true")
public class AtmosphereCacheAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereCacheAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(BroadcasterCache.class)
    public BoundedMemoryCache boundedMemoryCache(AtmosphereFramework framework) {
        var cache = new BoundedMemoryCache();
        cache.configure(framework.getAtmosphereConfig());

        // Set as the default broadcaster cache class
        framework.getAtmosphereConfig().properties()
                .put(ApplicationConfig.BROADCASTER_CACHE, BoundedMemoryCache.class.getName());

        logger.info("Registered BoundedMemoryCache as default broadcaster cache");
        return cache;
    }

    @Bean
    @ConditionalOnMissingBean(MessageAckInterceptor.class)
    public MessageAckInterceptor messageAckInterceptor(AtmosphereFramework framework) {
        var interceptor = new MessageAckInterceptor();
        framework.interceptor(interceptor);
        logger.info("Registered MessageAckInterceptor");
        return interceptor;
    }
}
