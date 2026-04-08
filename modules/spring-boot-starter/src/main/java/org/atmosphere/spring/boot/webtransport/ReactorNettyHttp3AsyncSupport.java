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
package org.atmosphere.spring.boot.webtransport;

import org.atmosphere.container.JSR356AsyncSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reactor Netty HTTP/3 {@link org.atmosphere.cpr.AsyncSupport} implementation.
 * Extends {@link JSR356AsyncSupport} and starts a Reactor Netty HTTP/3 sidecar
 * server alongside the servlet container when {@code netty-codec-http3} is on
 * the classpath.
 *
 * <p>Unlike {@link org.atmosphere.container.JettyHttp3AsyncSupport} which adds
 * an HTTP/3 connector to the existing Jetty server, this implementation starts
 * a separate Netty-based QUIC server on its own UDP port. This works with any
 * servlet container (Tomcat, Undertow, etc.) — the sidecar bridges HTTP/3
 * requests into Atmosphere via {@link DefaultWebTransportProcessor}.</p>
 *
 * <p>Configuration via init parameters:</p>
 * <ul>
 *   <li>{@code atmosphere.http3.port} — UDP port (default 4443)</li>
 *   <li>{@code atmosphere.http3.host} — bind address (default 0.0.0.0)</li>
 *   <li>{@code atmosphere.http3.ssl.certificate} — PEM certificate path</li>
 *   <li>{@code atmosphere.http3.ssl.private-key} — PEM private key path</li>
 *   <li>{@code atmosphere.http3.ssl.private-key-password} — key password</li>
 * </ul>
 */
public class ReactorNettyHttp3AsyncSupport extends JSR356AsyncSupport {

    private static final Logger logger = LoggerFactory.getLogger(ReactorNettyHttp3AsyncSupport.class);

    private ReactorNettyTransportServer server;
    private boolean sidecarEnabled;

    public ReactorNettyHttp3AsyncSupport(AtmosphereConfig config) {
        super(config);
        // Sidecar startup is deferred to SmartLifecycle (managed by
        // AtmosphereWebTransportAutoConfiguration) to avoid blocking
        // framework init. This constructor only establishes the AsyncSupport
        // detection — the actual HTTP/3 server starts after the servlet
        // container is ready.
        String enabled = config.getInitParameter("atmosphere.http3.enabled");
        if ("true".equalsIgnoreCase(enabled)) {
            logger.info("Reactor Netty HTTP/3 sidecar enabled — will start via SmartLifecycle");
            this.sidecarEnabled = true;
        }
    }

    @Override
    public boolean supportWebTransport() {
        return sidecarEnabled;
    }

    @Override
    public String getContainerName() {
        return super.getContainerName() + " with Reactor Netty HTTP/3";
    }

    /** The underlying transport server, for use by info controllers. */
    public ReactorNettyTransportServer transportServer() {
        return server;
    }
}
