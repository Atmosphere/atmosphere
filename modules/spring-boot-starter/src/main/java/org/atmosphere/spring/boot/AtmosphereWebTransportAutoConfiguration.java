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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the Atmosphere WebTransport over HTTP/3 transport.
 * Activates when Reactor Netty HTTP/3 is on the classpath and
 * {@code atmosphere.web-transport.enabled=true} is set.
 *
 * <p>The HTTP/3 sidecar is now managed by
 * {@link org.atmosphere.spring.boot.webtransport.ReactorNettyHttp3AsyncSupport}
 * (detected via {@link org.atmosphere.cpr.DefaultAsyncSupportResolver}), so this
 * auto-configuration only bridges Spring Boot properties to Atmosphere init
 * parameters and registers the {@link AltSvcFilter} for Alt-Svc header
 * advertisement.</p>
 */
@AutoConfiguration(after = AtmosphereAutoConfiguration.class)
@ConditionalOnClass(name = {
        "reactor.netty.http.server.HttpServer",
        "io.netty.handler.codec.http3.Http3"
})
@ConditionalOnProperty(name = "atmosphere.web-transport.enabled", havingValue = "true")
@EnableConfigurationProperties(AtmosphereProperties.class)
public class AtmosphereWebTransportAutoConfiguration {

    /**
     * Bridge Spring Boot WebTransport properties to Atmosphere init parameters
     * so the {@code ReactorNettyHttp3AsyncSupport} (resolved by the framework)
     * picks them up during initialization.
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereWebTransportInitParamBridge")
    public Object atmosphereWebTransportInitParamBridge(
            AtmosphereFramework framework,
            AtmosphereProperties properties) {
        var wt = properties.getWebTransport();
        framework.addInitParameter("atmosphere.http3.enabled", "true");
        framework.addInitParameter("atmosphere.http3.port", String.valueOf(wt.getPort()));
        framework.addInitParameter("atmosphere.http3.host", wt.getHost());
        if (wt.getSsl().getCertificate() != null) {
            framework.addInitParameter("atmosphere.http3.ssl.certificate", wt.getSsl().getCertificate());
        }
        if (wt.getSsl().getPrivateKey() != null) {
            framework.addInitParameter("atmosphere.http3.ssl.private-key", wt.getSsl().getPrivateKey());
        }
        if (wt.getSsl().getPrivateKeyPassword() != null) {
            framework.addInitParameter("atmosphere.http3.ssl.private-key-password",
                    wt.getSsl().getPrivateKeyPassword());
        }
        return Boolean.TRUE; // Sentinel bean — actual value unused
    }

    /**
     * Start the Reactor Netty HTTP/3 sidecar AFTER the servlet container is
     * fully started. This avoids blocking framework init and ensures the
     * BroadcasterFactory and WebSocket endpoints are ready.
     */
    @Bean
    @ConditionalOnMissingBean(name = "atmosphereWebTransportLifecycle")
    public org.springframework.context.SmartLifecycle atmosphereWebTransportLifecycle(
            AtmosphereFramework framework,
            AtmosphereProperties properties) {
        return new org.springframework.context.SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    var wt = properties.getWebTransport();
                    var server = new org.atmosphere.spring.boot.webtransport.ReactorNettyTransportServer(
                            framework, wt);
                    server.start();
                    running = true;
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(AtmosphereWebTransportAutoConfiguration.class)
                            .warn("WebTransport server failed to start (app continues without HTTP/3): {}",
                                    e.getMessage());
                }
            }

            @Override
            public void stop() { running = false; }

            @Override
            public boolean isRunning() { return running; }

            @Override
            public int getPhase() { return Integer.MAX_VALUE - 1; }
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
