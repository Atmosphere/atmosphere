/*
 * Copyright 2008-2022 Async-IO.org
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

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletContext;
import javax.websocket.DeploymentException;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JSR356AsyncSupport extends Servlet30CometSupport {

    private static final Logger logger = LoggerFactory.getLogger(JSR356AsyncSupport.class);
    private static final String PATH = "/{path";
    private final ServerEndpointConfig.Configurator configurator;

    public JSR356AsyncSupport(AtmosphereConfig config) {
        this(config, config.getServletContext());
    }

    public JSR356AsyncSupport(AtmosphereConfig config, ServletContext ctx) {
        super(config);
        ServerContainer container = (ServerContainer) ctx.getAttribute(ServerContainer.class.getName());

        if (container == null) {
            if (ctx.getServerInfo().contains("WebLogic")) {
                logger.error("{} must use JDK 1.8+ with WebSocket", ctx.getServerInfo());
            }
            throw new IllegalStateException("Unable to configure jsr356 at that stage. ServerContainer is null");
        }

        int pathLength = 5;
        String s = config.getInitParameter(ApplicationConfig.JSR356_PATH_MAPPING_LENGTH);
        if (s != null) {
            pathLength = Integer.parseInt(s);
        }
        logger.trace("JSR356 Path mapping Size {}", pathLength);

        String servletPath = config.getInitParameter(ApplicationConfig.JSR356_MAPPING_PATH);
        if (servletPath == null) {
            servletPath = IOUtils.guestServletPath(config);
            if (servletPath.equals("") || servletPath.equals("/") || servletPath.equals("/*")) {
                servletPath = PATH +"}";
            }
        }
        logger.info("JSR 356 Mapping path {}", servletPath);
        configurator = IS_RUNNING_ON_QUARKUS ? new QuarkusAtmosphereConfigurator(config.framework())
                : new AtmosphereConfigurator(config.framework());

        StringBuilder b = new StringBuilder(servletPath);
        for (int i = 0; i < pathLength; i++) {
            try {
                container.addEndpoint(ServerEndpointConfig.Builder.create(JSR356Endpoint.class, b.toString())
                        .extensions(new ArrayList<>(container.getInstalledExtensions())).configurator(configurator).build());
            } catch (DeploymentException e) {
                logger.warn("Duplicate Servlet Mapping Path {}. Use {} init-param to prevent this message", servletPath, ApplicationConfig.JSR356_MAPPING_PATH);
                logger.trace("", e);
                servletPath = IOUtils.guestServletPath(config);
                logger.warn("Duplicate guess {}", servletPath, e);
                b.setLength(0);
                b.append(servletPath);
            }
            b.append(PATH).append(i).append("}");
        }
    }

    public boolean supportWebSocket() {
        return true;
    }

    @Override
    public String getContainerName() {
        return super.getContainerName() + " and jsr356/WebSocket API";
    }

    public final static class AtmosphereConfigurator extends ServerEndpointConfig.Configurator {

        private final AtmosphereFramework framework;
        /**
         * TODO: UGLY!
         * GlassFish/Jetty call modifyHandshake BEFORE getEndpointInstance() where other jsr356 do the reverse.
         */
        final ThreadLocal<JSR356Endpoint> endPoint = new ThreadLocal<>();
        final ThreadLocal<HandshakeRequest> hRequest = new ThreadLocal<>();

        private static final AtomicBoolean hasContainerDefaultConfigurator = new AtomicBoolean(Boolean.FALSE);

        static {
            checkContainerDefaultConfigurator();
        }
        
        public AtmosphereConfigurator(AtmosphereFramework framework) {
            this.framework = framework;
        }

        public <T> T getEndpointInstance(java.lang.Class<T> endpointClass) throws java.lang.InstantiationException {
            if (JSR356Endpoint.class.isAssignableFrom(endpointClass)) {
                JSR356Endpoint e = new JSR356Endpoint(framework, WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework));
                if (hRequest.get() != null) {
                    e.handshakeRequest(hRequest.get());
                    hRequest.set(null);
                } else {
                    endPoint.set(e);
                }
                return (T) e;
            } else {
                return super.getEndpointInstance(endpointClass);
            }
        }

        /**
         * Checkes if a javax.websocket.server.ServerEndpointConfig.Configurator can be fetched via ServiceLoader
         * and stores the result in the boolean variable {@link #hasContainerDefaultConfigurator}.
         * This is necessary e.g. in a scenario where Jetty >= 10 is used as embedded server in an OSGi application,
         * beacause in this scenario the ServiceLoader won't work and a RuntimeException is thrown when calling one of the methods
         * {@link #checkOrigin(String)}, {@link #getNegotiatedSubprotocol(List, List)} or {@link #getNegotiatedExtensions(List, List)}.
         * 
         */
        private static void checkContainerDefaultConfigurator() {
            for(ServerEndpointConfig.Configurator impl : ServiceLoader.load(javax.websocket.server.ServerEndpointConfig.Configurator.class)) {
                hasContainerDefaultConfigurator.set(Boolean.TRUE);
                return;
            }
            hasContainerDefaultConfigurator.set(Boolean.FALSE);
        }

        /**
         * Calls {@link #checkOrigin(String)} in super class if a Default Configurator could be loaded.
         * Otherwise <code>true</code> is returned as default. 
         */
        public boolean checkOrigin(String originHeaderValue) {
            if(hasContainerDefaultConfigurator.get()) {
                return super.checkOrigin(originHeaderValue);
            }
            // no default configurator, returning true as fallback
            return true;
        }

        /**
         * Calls {@link #getNegotiatedSubprotocol(List, List)} in super class if a Default Configurator could be loaded.
         * Otherwise <code>""</code> is returned as default. 
         */
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
            if(hasContainerDefaultConfigurator.get()) {
                return super.getNegotiatedSubprotocol(supported, requested);
            }
            // no default configurator, returning "" as fallback
            return "";
        }

        /**
         * Calls {@link #getNegotiatedExtensions(List, List)} in super class if a Default Configurator could be loaded.
         * Otherwise the list of matching extensions is returned as default. 
         */
        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
            if(hasContainerDefaultConfigurator.get()) {
                return super.getNegotiatedExtensions(installed, requested);
            }
            
            // no default configurator, find matching extensions as default implementation
            List<Extension> negotiatedExtensions = new ArrayList<>();
            for (Extension ext : requested) {
                // Only choose the first extension if multiple with the same name.
                long matches = negotiatedExtensions.stream().filter(e -> e.getName().equals(ext.getName())).count();
                if (matches == 0)
                    negotiatedExtensions.add(ext);
            }

            return negotiatedExtensions;
        }

        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            // Broken GlassFish, which call modifyHandshake BEFORE getEndpointInstance!
            if (endPoint.get() == null) {
                hRequest.set(request);
            } else {
                endPoint.get().handshakeRequest(request);
                endPoint.set(null);
            }
        }
    }

    private static final boolean IS_RUNNING_ON_QUARKUS;
    static {
        boolean runningOnQuarkus = false;
        try {
            Class.forName("io.quarkus.runtime.Quarkus");
            runningOnQuarkus = true;
        } catch (ClassNotFoundException ex) {
            // class not found, not running on Quarkus. Just ignore the exception.
        }
        IS_RUNNING_ON_QUARKUS = runningOnQuarkus;
    }

    public final static class QuarkusAtmosphereConfigurator extends ServerEndpointConfig.Configurator {

        private final AtmosphereFramework framework;
        /**
         * TODO: UGLY!
         * Quarkus call modifyHandshake BEFORE getEndpointInstance() where other jsr356 do the reverse.
         * However, because of VertX the thread pool of Undertow handling regular http requests
         * differ to the thread pool handling websocket requests, so we can't use ThreadLocals to remember
         * the HandshakeRequest. Use BlockingDeque with the capacity of 1 since we don't want
         * the requests to get mixed up in case of high concurrency.
         *
         * The reasoning for BlockingDeque is that we expect that a call to getEndpointInstance() will
         * immediately follow call to modifyHandshake(). We will therefore store the request into this deque,
         * and we will pick it up in getEndpointInstance().
         *
         * The deque has a capacity of 1: if there are any concurrent attempts to initialize the endpoint,
         * any follow-up modifyHandshake() will be delayed until the getEndpointInstance() is called.
         *
         * For the ThreadLocal-based solution see the original JSR356AsyncSupport class from Atmosphere.
         */
        private final BlockingDeque<HandshakeRequest> requests = new LinkedBlockingDeque<>(1);

        public QuarkusAtmosphereConfigurator(AtmosphereFramework framework) {
            this.framework = framework;
        }

        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            if (JSR356Endpoint.class.isAssignableFrom(endpointClass)) {
                JSR356Endpoint e = new JSR356Endpoint(framework, WebSocketProcessorFactory.getDefault().getWebSocketProcessor(framework));
                final HandshakeRequest request = requests.removeFirst();
                e.handshakeRequest(request);
                return (T) e;
            } else {
                return super.getEndpointInstance(endpointClass);
            }
        }

        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            try {
                // if there's an ongoing endpoint initialization, delay this call
                // until the getEndpointInstance() has been called.
                if (!requests.offer(request, 1, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Timed out waiting for getEndpointInstance() to be called");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
