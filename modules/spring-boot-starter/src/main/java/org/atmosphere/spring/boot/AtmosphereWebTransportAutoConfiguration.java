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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

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
@ConditionalOnClass(name = "reactor.netty.http.server.HttpServer")
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
                server.start();
                running = true;
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

    /**
     * REST endpoint exposing the WebTransport server configuration for the
     * browser client. Returns the HTTP/3 port and certificate hash needed
     * for {@code serverCertificateHashes} (self-signed dev certs).
     */
    @RestController
    public static class WebTransportInfoController {

        private final ReactorNettyTransportServer server;
        private final AtmosphereProperties properties;

        public WebTransportInfoController(ReactorNettyTransportServer server, AtmosphereProperties properties) {
            this.server = server;
            this.properties = properties;
        }

        @GetMapping("/api/webtransport-info")
        public Map<String, Object> info() {
            var result = new LinkedHashMap<String, Object>();
            result.put("port", properties.getWebTransport().getPort());
            result.put("enabled", server.isRunning());
            var hash = server.certificateHash();
            if (hash != null) {
                result.put("certificateHash", hash);
            }
            return result;
        }
    }
}
