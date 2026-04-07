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
package org.atmosphere.container;

import jakarta.servlet.ServletContext;
import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jetty 12 HTTP/3 {@link org.atmosphere.cpr.AsyncSupport} implementation.
 * Extends {@link JSR356AsyncSupport} and adds an HTTP/3 (QUIC/UDP) connector
 * to the embedded Jetty server when {@code jetty-http3-server} is on the classpath.
 *
 * <p>All Jetty-specific classes are accessed via reflection so that this class
 * loads cleanly on any platform — including Quarkus/Undertow native image builds
 * where Jetty classes are not on the classpath.</p>
 *
 * <p>The HTTP/3 port defaults to 4443 but can be overridden via the
 * {@code atmosphere.http3.port} init parameter.</p>
 */
public class JettyHttp3AsyncSupport extends JSR356AsyncSupport {

    private static final Logger logger = LoggerFactory.getLogger(JettyHttp3AsyncSupport.class);
    private static final int DEFAULT_HTTP3_PORT = 4443;

    private Object http3Connector;

    public JettyHttp3AsyncSupport(AtmosphereConfig config) {
        super(config);

        Object server = resolveJettyServer(config.getServletContext());
        if (server == null) {
            logger.warn("Jetty HTTP/3 classes on classpath but could not resolve Jetty Server — HTTP/3 disabled");
            return;
        }

        try {
            int port = resolveHttp3Port(config);
            Object sslContextFactory = buildSslContextFactory(config);
            Path pemWorkDir = Files.createTempDirectory("jetty-http3-pem");
            pemWorkDir.toFile().deleteOnExit();

            // All Jetty interaction via reflection — no compile-time Jetty imports
            var sslClass = Class.forName("org.eclipse.jetty.util.ssl.SslContextFactory$Server");
            var quicConfigClass = Class.forName("org.eclipse.jetty.quic.server.ServerQuicConfiguration");
            var quicConfig = quicConfigClass.getConstructor(sslClass, Path.class)
                    .newInstance(sslContextFactory, pemWorkDir);

            var h3FactoryClass = Class.forName("org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory");
            var h3Factory = h3FactoryClass.getConstructor(quicConfigClass).newInstance(quicConfig);

            var serverClass = Class.forName("org.eclipse.jetty.server.Server");
            var connFactoryClass = Class.forName("org.eclipse.jetty.server.ConnectionFactory");
            var connFactoryArray = java.lang.reflect.Array.newInstance(connFactoryClass, 1);
            java.lang.reflect.Array.set(connFactoryArray, 0, h3Factory);

            var connectorClass = Class.forName("org.eclipse.jetty.quic.server.QuicServerConnector");
            http3Connector = connectorClass
                    .getConstructor(serverClass, quicConfigClass, connFactoryArray.getClass())
                    .newInstance(server, quicConfig, connFactoryArray);

            connectorClass.getMethod("setPort", int.class).invoke(http3Connector, port);

            // server.addConnector(connector)
            var addConnector = serverClass.getMethod("addConnector",
                    Class.forName("org.eclipse.jetty.server.Connector"));
            addConnector.invoke(server, http3Connector);

            // If server is already started, start the connector
            boolean started = (boolean) serverClass.getMethod("isStarted").invoke(server);
            if (started) {
                connectorClass.getMethod("start").invoke(http3Connector);
            }

            logger.info("Jetty HTTP/3 connector added on port {} (QUIC/UDP)", port);
        } catch (Exception e) {
            logger.warn("Failed to add Jetty HTTP/3 connector: {}", e.getMessage());
            logger.trace("Jetty HTTP/3 init error", e);
            http3Connector = null;
        }
    }

    private Object resolveJettyServer(ServletContext ctx) {
        // Try Jetty's well-known context attribute
        Object attr = ctx.getAttribute("org.eclipse.jetty.server.Server");
        if (attr != null) {
            return attr;
        }

        // Try reflection: ServletContextHandler.getServletContextHandler(ctx).getServer()
        try {
            var handlerClass = Class.forName("org.eclipse.jetty.ee10.servlet.ServletContextHandler");
            var getHandler = handlerClass.getMethod("getServletContextHandler", ServletContext.class);
            var handler = getHandler.invoke(null, ctx);
            var getServer = handler.getClass().getMethod("getServer");
            return getServer.invoke(handler);
        } catch (Exception e) {
            logger.debug("Could not resolve Jetty Server via reflection: {}", e.getMessage());
        }

        return null;
    }

    private Object buildSslContextFactory(AtmosphereConfig config) throws Exception {
        var sslClass = Class.forName("org.eclipse.jetty.util.ssl.SslContextFactory$Server");
        var sslContextFactory = sslClass.getDeclaredConstructor().newInstance();

        String keyStorePath = config.getInitParameter("atmosphere.http3.keyStorePath");
        if (keyStorePath != null) {
            sslClass.getMethod("setKeyStorePath", String.class).invoke(sslContextFactory, keyStorePath);
            String keyStorePassword = config.getInitParameter("atmosphere.http3.keyStorePassword");
            if (keyStorePassword != null) {
                sslClass.getMethod("setKeyStorePassword", String.class)
                        .invoke(sslContextFactory, keyStorePassword);
            }
            String keyManagerPassword = config.getInitParameter("atmosphere.http3.keyManagerPassword");
            if (keyManagerPassword != null) {
                sslClass.getMethod("setKeyManagerPassword", String.class)
                        .invoke(sslContextFactory, keyManagerPassword);
            }
            return sslContextFactory;
        }

        // Generate self-signed keystore via keytool
        logger.warn("No SSL keystore configured for HTTP/3 — generating self-signed certificate via keytool (dev only)");
        var tempKeystore = generateSelfSignedKeystore();
        sslClass.getMethod("setKeyStorePath", String.class)
                .invoke(sslContextFactory, tempKeystore.toString());
        sslClass.getMethod("setKeyStorePassword", String.class)
                .invoke(sslContextFactory, "changeit");
        sslClass.getMethod("setKeyStoreType", String.class)
                .invoke(sslContextFactory, "PKCS12");
        return sslContextFactory;
    }

    private Path generateSelfSignedKeystore() throws Exception {
        var keystorePath = Files.createTempFile("jetty-http3-", ".p12");
        keystorePath.toFile().deleteOnExit();
        Files.delete(keystorePath);

        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process = new ProcessBuilder(
                keytool,
                "-genkeypair", "-keyalg", "EC", "-groupname", "secp256r1",
                "-alias", "jetty-http3",
                "-dname", "CN=localhost,O=Atmosphere Dev",
                "-validity", "14",
                "-keystore", keystorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit"
        ).redirectErrorStream(true).start();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            var output = new String(process.getInputStream().readAllBytes());
            throw new IllegalStateException("keytool failed (exit " + exitCode + "): " + output);
        }

        logger.info("Generated self-signed HTTP/3 keystore: {}", keystorePath);
        return keystorePath;
    }

    private int resolveHttp3Port(AtmosphereConfig config) {
        String port = config.getInitParameter("atmosphere.http3.port");
        if (port != null) {
            return Integer.parseInt(port);
        }
        return DEFAULT_HTTP3_PORT;
    }

    @Override
    public boolean supportWebTransport() {
        return http3Connector != null;
    }

    @Override
    public String getContainerName() {
        return super.getContainerName() + " with Jetty HTTP/3";
    }
}
