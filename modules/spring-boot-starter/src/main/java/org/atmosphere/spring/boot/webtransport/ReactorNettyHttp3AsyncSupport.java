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
import org.atmosphere.spring.boot.AtmosphereProperties.WebTransportProperties;
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

    public ReactorNettyHttp3AsyncSupport(AtmosphereConfig config) {
        super(config);

        try {
            var properties = buildProperties(config);
            server = new ReactorNettyTransportServer(config.framework(), properties);
            server.start();
            logger.info("Reactor Netty HTTP/3 sidecar started on port {} (QUIC/UDP)",
                    properties.getPort());
        } catch (Exception e) {
            logger.warn("Failed to start Reactor Netty HTTP/3 sidecar: {}", e.getMessage());
            logger.trace("Reactor Netty HTTP/3 init error", e);
            server = null;
        }
    }

    private WebTransportProperties buildProperties(AtmosphereConfig config) {
        var props = new WebTransportProperties();
        props.setEnabled(true);

        String port = config.getInitParameter("atmosphere.http3.port");
        if (port != null) {
            props.setPort(Integer.parseInt(port));
        }
        String host = config.getInitParameter("atmosphere.http3.host");
        if (host != null) {
            props.setHost(host);
        }

        String cert = config.getInitParameter("atmosphere.http3.ssl.certificate");
        String key = config.getInitParameter("atmosphere.http3.ssl.private-key");
        if (cert != null && key != null) {
            props.getSsl().setCertificate(cert);
            props.getSsl().setPrivateKey(key);
            String keyPassword = config.getInitParameter("atmosphere.http3.ssl.private-key-password");
            if (keyPassword != null) {
                props.getSsl().setPrivateKeyPassword(keyPassword);
            }
        }

        return props;
    }

    @Override
    public boolean supportWebTransport() {
        return server != null && server.isRunning();
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
