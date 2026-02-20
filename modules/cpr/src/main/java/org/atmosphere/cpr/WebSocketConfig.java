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
package org.atmosphere.cpr;

import org.atmosphere.util.IOUtils;
import org.atmosphere.websocket.DefaultWebSocketFactory;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocketFactory;
import org.atmosphere.websocket.WebSocketProtocol;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletConfig;
import java.util.concurrent.locks.ReentrantLock;

import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROCESSOR;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROTOCOL;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_SUPPORT;

/**
 * Manages WebSocket configuration including protocol, processor, and factory settings.
 */
public class WebSocketConfig {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketConfig.class);

    private final AtmosphereConfig config;

    private boolean webSocketEnabled = true;
    private boolean useServlet30 = true;
    private String webSocketProtocolClassName = SimpleHttpProtocol.class.getName();
    private WebSocketProtocol webSocketProtocol;
    private boolean webSocketProtocolInitialized;
    private boolean hasNewWebSocketProtocol;
    private String webSocketProcessorClassName = DefaultWebSocketProcessor.class.getName();
    private WebSocketFactory webSocketFactory;
    private final ReentrantLock webSocketFactoryLock = new ReentrantLock();

    public WebSocketConfig(AtmosphereConfig config) {
        this.config = config;
    }

    /**
     * Parse WebSocket-related init parameters from the servlet config.
     */
    public void doInitParams(ServletConfig sc) {
        String s = sc.getInitParameter(WEBSOCKET_SUPPORT);
        if (s != null) {
            webSocketEnabled = Boolean.parseBoolean(s);
            config.framework().sessionSupport(false);
        }
        s = sc.getInitParameter(WEBSOCKET_PROTOCOL);
        if (s != null) {
            webSocketProtocolClassName = s;
            hasNewWebSocketProtocol = true;
        }

        s = sc.getInitParameter(WEBSOCKET_PROCESSOR);
        if (s != null) {
            webSocketProcessorClassName = s;
        }

        s = config.getInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT_SERVLET3);
        if (s != null) {
            useServlet30 = Boolean.parseBoolean(s);
        }
    }

    /**
     * Initialize the WebSocket protocol handler.
     */
    @SuppressWarnings("unchecked")
    public void initWebSocket() {
        if (webSocketProtocolInitialized) return;

        if (webSocketProtocol == null) {
            try {
                webSocketProtocol = config.framework().newClassInstance(WebSocketProtocol.class,
                        (Class<WebSocketProtocol>) IOUtils.loadClass(config.framework().getClass(), webSocketProtocolClassName));
                logger.info("Installed WebSocketProtocol {} ", webSocketProtocolClassName);
            } catch (Exception ex) {
                logger.error("Cannot load the WebSocketProtocol {}", webSocketProtocolClassName, ex);
                try {
                    webSocketProtocol = config.framework().newClassInstance(WebSocketProtocol.class, SimpleHttpProtocol.class);
                } catch (Exception e) {
                }
            }
        }
        webSocketProtocolInitialized = true;
        webSocketProtocol.configure(config);
    }

    /**
     * Configure the WebSocket factory (double-checked locking).
     */
    public void configureWebSocketFactory() {
        if (webSocketFactory != null) return;

        webSocketFactoryLock.lock();
        try {
            if (webSocketFactory != null) return;
            try {
                webSocketFactory = config.framework().newClassInstance(WebSocketFactory.class, DefaultWebSocketFactory.class);
            } catch (InstantiationException | IllegalAccessException e) {
                logger.error("", e);
            }
        } finally {
            webSocketFactoryLock.unlock();
        }
    }

    public boolean isEnabled() {
        return webSocketEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.webSocketEnabled = enabled;
    }

    public boolean isUseServlet30() {
        return useServlet30;
    }

    public void setUseServlet30(boolean useServlet30) {
        this.useServlet30 = useServlet30;
    }

    public String getProtocolClassName() {
        return webSocketProtocolClassName;
    }

    public void setProtocolClassName(String className) {
        hasNewWebSocketProtocol = true;
        this.webSocketProtocolClassName = className;
    }

    public WebSocketProtocol getProtocol() {
        initWebSocket();
        return webSocketProtocol;
    }

    public boolean hasNewProtocol() {
        return hasNewWebSocketProtocol;
    }

    public String getProcessorClassName() {
        return webSocketProcessorClassName;
    }

    public void setProcessorClassName(String className) {
        this.webSocketProcessorClassName = className;
    }

    public WebSocketFactory getFactory() {
        if (webSocketFactory == null) {
            configureWebSocketFactory();
        }
        return webSocketFactory;
    }

    public void setFactory(WebSocketFactory factory) {
        this.webSocketFactory = factory;
    }
}
