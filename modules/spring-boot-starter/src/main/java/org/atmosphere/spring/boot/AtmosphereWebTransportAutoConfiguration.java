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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.spring.boot.webtransport.AltSvcFilter;
import org.atmosphere.spring.boot.webtransport.ReactorNettyTransportServer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Atmosphere WebTransport over HTTP/3 transport.
 * Activates when Reactor Netty is on the classpath and
 * {@code atmosphere.web-transport.enabled=true} is set.
 *
 * <p>Starts a secondary Reactor Netty HTTP/3 server alongside the servlet
 * container and registers an {@link AltSvcFilter} to advertise it to
 * browsers.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(name = {
        "reactor.netty.http.server.HttpServer",
        "io.netty.handler.codec.http3.Http3"
})
@ConditionalOnProperty(name = "atmosphere.web-transport.enabled", havingValue = "true")
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereWebTransportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReactorNettyTransportServer reactorNettyTransportServer(
            AtmosphereFramework framework,
            AtmosphereProperties properties) {
        return new ReactorNettyTransportServer(framework, properties.getWebTransport());
    }

    @Bean
    @ConditionalOnMissingBean(name = "atmosphereWebTransportLifecycle")
    public SmartLifecycle atmosphereWebTransportLifecycle(ReactorNettyTransportServer server) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    server.start();
                    running = true;
                } catch (Exception e) {
                    // Don't crash the app — WebTransport is optional.
                    // Self-signed cert generation fails in GraalVM native image.
                    org.slf4j.LoggerFactory.getLogger(AtmosphereWebTransportAutoConfiguration.class)
                            .warn("WebTransport server failed to start (app continues without HTTP/3): {}",
                                    e.getMessage());
                }
            }

            @Override
            public void stop() {
                server.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                // Start after the servlet container (default phase 0)
                return Integer.MAX_VALUE - 1;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(name = "atmosphereAltSvcFilter")
    @ConditionalOnProperty(name = "atmosphere.web-transport.add-alt-svc", matchIfMissing = true)
    public FilterRegistrationBean<AltSvcFilter> atmosphereAltSvcFilter(AtmosphereProperties properties) {
        var registration = new FilterRegistrationBean<>(
                new AltSvcFilter(properties.getWebTransport().getPort()));
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }

}
