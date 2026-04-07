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
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jetty 12 HTTP/3 {@link org.atmosphere.cpr.AsyncSupport} implementation.
 * Extends {@link JSR356AsyncSupport} and adds an HTTP/3 (QUIC/UDP) connector
 * to the embedded Jetty server when {@code jetty-http3-server} is on the classpath.
 *
 * <p>Uses {@link QuicServerConnector} with {@link HTTP3ServerConnectionFactory}
 * to add a QUIC/UDP connector alongside the existing HTTP/1.1 and/or HTTP/2
 * connectors on the Jetty server.</p>
 *
 * <p>The HTTP/3 connector uses a self-signed certificate by default for
 * development. For production, configure a proper keystore via
 * {@code atmosphere.http3.keyStorePath} and {@code atmosphere.http3.keyStorePassword}
 * init parameters.</p>
 *
 * <p>The HTTP/3 port defaults to 4443 but can be overridden via the
 * {@code atmosphere.http3.port} init parameter.</p>
 */
public class JettyHttp3AsyncSupport extends JSR356AsyncSupport {

    private static final Logger logger = LoggerFactory.getLogger(JettyHttp3AsyncSupport.class);
    private static final int DEFAULT_HTTP3_PORT = 4443;

    private QuicServerConnector http3Connector;

    public JettyHttp3AsyncSupport(AtmosphereConfig config) {
        super(config);

        Server server = resolveJettyServer(config.getServletContext());
        if (server == null) {
            logger.warn("Jetty HTTP/3 classes on classpath but could not resolve Jetty Server — HTTP/3 disabled");
            return;
        }

        try {
            int port = resolveHttp3Port(config);
            var sslContextFactory = buildSslContextFactory(config);
            Path pemWorkDir = Files.createTempDirectory("jetty-http3-pem");
            pemWorkDir.toFile().deleteOnExit();

            var quicConfig = new ServerQuicConfiguration(sslContextFactory, pemWorkDir);
            var h3Factory = new HTTP3ServerConnectionFactory(quicConfig);
            http3Connector = new QuicServerConnector(server, quicConfig, h3Factory);
            http3Connector.setPort(port);

            server.addConnector(http3Connector);

            // If the server is already started (e.g., Spring Boot embedded), start the connector now
            if (server.isStarted()) {
                http3Connector.start();
            }

            logger.info("Jetty HTTP/3 connector added on port {} (QUIC/UDP)", port);
        } catch (Exception e) {
            logger.warn("Failed to add Jetty HTTP/3 connector: {}", e.getMessage());
            logger.trace("Jetty HTTP/3 init error", e);
        }
    }

    /**
     * Resolve the Jetty {@link Server} from the servlet context.
     * Tries the well-known context attribute first, then falls back
     * to reflection via {@code ServletContextHandler.getServletContextHandler()}.
     */
    private Server resolveJettyServer(ServletContext ctx) {
        // Try Jetty's well-known context attribute
        Object attr = ctx.getAttribute("org.eclipse.jetty.server.Server");
        if (attr instanceof Server s) {
            return s;
        }

        // Try reflection: ServletContextHandler.getServletContextHandler(ctx).getServer()
        try {
            var handlerClass = Class.forName("org.eclipse.jetty.ee10.servlet.ServletContextHandler");
            var getHandler = handlerClass.getMethod("getServletContextHandler", ServletContext.class);
            var handler = getHandler.invoke(null, ctx);
            var getServer = handler.getClass().getMethod("getServer");
            return (Server) getServer.invoke(handler);
        } catch (Exception e) {
            logger.debug("Could not resolve Jetty Server via reflection: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Build the SSL context factory. Uses a configured keystore if provided,
     * otherwise generates a self-signed certificate programmatically.
     */
    private SslContextFactory.Server buildSslContextFactory(AtmosphereConfig config) throws Exception {
        var sslContextFactory = new SslContextFactory.Server();

        String keyStorePath = config.getInitParameter("atmosphere.http3.keyStorePath");
        if (keyStorePath != null) {
            sslContextFactory.setKeyStorePath(keyStorePath);
            String keyStorePassword = config.getInitParameter("atmosphere.http3.keyStorePassword");
            if (keyStorePassword != null) {
                sslContextFactory.setKeyStorePassword(keyStorePassword);
            }
            String keyManagerPassword = config.getInitParameter("atmosphere.http3.keyManagerPassword");
            if (keyManagerPassword != null) {
                sslContextFactory.setKeyManagerPassword(keyManagerPassword);
            }
            return sslContextFactory;
        }

        // Generate a self-signed keystore for development using keytool (JDK tool).
        // We use keytool as a subprocess instead of sun.security.x509 internals
        // because the JDK module system restricts access to those classes.
        logger.warn("No SSL keystore configured for HTTP/3 — generating self-signed certificate via keytool (dev only)");
        var tempKeystore = generateSelfSignedKeystore();
        sslContextFactory.setKeyStorePath(tempKeystore.toString());
        sslContextFactory.setKeyStorePassword("changeit");
        sslContextFactory.setKeyStoreType("PKCS12");
        return sslContextFactory;
    }

    /**
     * Generate a temporary PKCS12 keystore with a self-signed ECDSA certificate
     * using the JDK's {@code keytool} command. The keystore file is marked for
     * deletion on JVM exit.
     */
    private Path generateSelfSignedKeystore() throws Exception {
        var keystorePath = Files.createTempFile("jetty-http3-", ".p12");
        keystorePath.toFile().deleteOnExit();
        // Delete the empty file first — keytool refuses to overwrite
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
