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

import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.IOUtils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER_MAPPING;
import static org.atmosphere.cpr.AtmosphereFramework.MAPPING_REGEX;

/**
 * Manages {@link AtmosphereHandler} registration, mapping, and lifecycle.
 */
public class HandlerRegistry {

    private static final Logger logger = LoggerFactory.getLogger(HandlerRegistry.class);

    private final AtmosphereConfig config;
    private final InterceptorRegistry interceptorRegistry;
    private final Map<String, AtmosphereHandlerWrapper> atmosphereHandlers = new ConcurrentHashMap<>();
    private EndpointMapper<AtmosphereHandlerWrapper> endpointMapper = new DefaultEndpointMapper<>();
    private String mappingRegex = MAPPING_REGEX;
    private Supplier<BroadcasterFactory> broadcasterFactorySupplier;

    public HandlerRegistry(AtmosphereConfig config, InterceptorRegistry interceptorRegistry) {
        this.config = config;
        this.interceptorRegistry = interceptorRegistry;
    }

    /**
     * Set the supplier for the BroadcasterFactory (resolved lazily during init).
     */
    void setBroadcasterFactorySupplier(Supplier<BroadcasterFactory> supplier) {
        this.broadcasterFactorySupplier = supplier;
    }

    /**
     * Return the map of registered {@link AtmosphereHandler}s.
     */
    public Map<String, AtmosphereHandlerWrapper> handlers() {
        return atmosphereHandlers;
    }

    /**
     * Add an {@link AtmosphereHandler} mapped to a path.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }
        createWrapperAndConfigureHandler(h, mapping, l);

        if (!config.framework().isInit) {
            logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", h.getClass().getName(), mapping);
            logger.info("Installed the following AtmosphereInterceptor mapped to AtmosphereHandler {}", h.getClass().getName());
            if (!l.isEmpty()) {
                for (AtmosphereInterceptor s : l) {
                    logger.info("\t{} : {}", s.getClass().getName(), s);
                }
            }
        }
    }

    /**
     * Add an {@link AtmosphereHandler} with an explicit {@link Broadcaster}.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h, Broadcaster broadcaster, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        createWrapperAndConfigureHandler(h, mapping, l).setBroadcaster(broadcaster);

        if (!config.framework().isInit) {
            logger.info("Installed AtmosphereHandler {} mapped to context-path {} and Broadcaster Class {}", h.getClass().getName(), mapping, broadcaster.getClass().getName());
        } else {
            logger.debug("Installed AtmosphereHandler {} mapped to context-path {} and Broadcaster Class {}",
                    h.getClass().getName(), mapping, broadcaster.getClass().getName());
        }

        if (!l.isEmpty()) {
            logger.info("Installed AtmosphereInterceptor {} mapped to AtmosphereHandler {}", l, h.getClass().getName());
        }
    }

    /**
     * Add an {@link AtmosphereHandler} with a specific broadcaster ID.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h, String broadcasterId, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        createWrapperAndConfigureHandler(h, mapping, l).broadcaster().setID(broadcasterId);

        logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", h.getClass().getName(), mapping);
        if (!l.isEmpty()) {
            logger.info("Installed AtmosphereInterceptor {} mapped to AtmosphereHandler {}", l, h.getClass().getName());
        }
    }

    /**
     * Add a simple handler without interceptors.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h) {
        addAtmosphereHandler(mapping, h, List.of());
    }

    /**
     * Add a handler with a broadcaster ID and no extra interceptors.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h, String broadcasterId) {
        addAtmosphereHandler(mapping, h, broadcasterId, List.of());
    }

    /**
     * Add a handler with a broadcaster and no extra interceptors.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h, Broadcaster broadcaster) {
        addAtmosphereHandler(mapping, h, broadcaster, List.of());
    }

    protected AtmosphereHandlerWrapper createWrapperAndConfigureHandler(AtmosphereHandler h, String mapping, List<AtmosphereInterceptor> l) {
        var w = new AtmosphereHandlerWrapper(broadcasterFactorySupplier.get(), h, mapping, config);
        addMapping(mapping, w);
        interceptorRegistry.addInterceptorToWrapper(w, l);
        initServletProcessor(h);
        return w;
    }

    private void addMapping(String path, AtmosphereHandlerWrapper w) {
        atmosphereHandlers.put(normalizePath(path), w);
    }

    public String normalizePath(String path) {
        if (path.contains("*")) {
            path = path.replace("*", mappingRegex);
        }

        if (path.endsWith("/")) {
            path = path + mappingRegex;
        }
        return path;
    }

    private void initServletProcessor(AtmosphereHandler h) {
        if (!config.framework().isInit) return;

        try {
            if (h instanceof AtmosphereServletProcessor asp) {
                asp.init(config);
            }
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Remove an {@link AtmosphereHandler}.
     */
    public void removeAtmosphereHandler(String mapping) {
        if (mapping.endsWith("/")) {
            mapping += mappingRegex;
        }
        atmosphereHandlers.remove(mapping);
    }

    /**
     * Remove all {@link AtmosphereHandler}s.
     */
    public void removeAllAtmosphereHandler() {
        atmosphereHandlers.clear();
    }

    /**
     * Add a {@link WebSocketHandler} mapped to "/*".
     */
    public void addWebSocketHandler(WebSocketHandler handler) {
        addWebSocketHandler(Broadcaster.ROOT_MASTER, handler);
    }

    /**
     * Add a {@link WebSocketHandler} mapped to a path.
     */
    public void addWebSocketHandler(String path, WebSocketHandler handler) {
        addWebSocketHandler(path, handler, AtmosphereFramework.REFLECTOR_ATMOSPHEREHANDLER, List.of());
    }

    /**
     * Add a {@link WebSocketHandler} with an {@link AtmosphereHandler}.
     */
    public void addWebSocketHandler(String path, WebSocketHandler handler, AtmosphereHandler h) {
        addWebSocketHandler(path, handler, AtmosphereFramework.REFLECTOR_ATMOSPHEREHANDLER, List.of());
    }

    /**
     * Add a {@link WebSocketHandler} with an {@link AtmosphereHandler} and interceptors.
     */
    public void addWebSocketHandler(String path, WebSocketHandler handler, AtmosphereHandler h, List<AtmosphereInterceptor> l) {
        WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config.framework())
                .registerWebSocketHandler(path,
                        new WebSocketProcessor.WebSocketHandlerProxy(broadcasterFactorySupplier.get().lookup(path, true).getClass(), handler));
        addAtmosphereHandler(path, h, l);
    }

    /**
     * Initialize all registered {@link AtmosphereHandler}s.
     */
    public void initAtmosphereHandler() throws ServletException {
        AtmosphereHandler a;
        AtmosphereHandlerWrapper w;
        for (Entry<String, AtmosphereHandlerWrapper> h : atmosphereHandlers.entrySet()) {
            w = h.getValue();
            a = w.atmosphereHandler();
            if (a instanceof AtmosphereServletProcessor asp) {
                asp.init(config);
            }
        }
        checkWebSocketSupportState();
    }

    /**
     * Add a void handler if no handlers are registered and WebSocket protocol is in use.
     */
    public void checkWebSocketSupportState() {
        if (atmosphereHandlers.isEmpty() && !(config.framework().webSocket().getProtocol() instanceof SimpleHttpProtocol)) {
            logger.debug("Adding a void AtmosphereHandler mapped to /* to allow WebSocket application only");
            addAtmosphereHandler(Broadcaster.ROOT_MASTER, new AbstractReflectorAtmosphereHandler() {
                @Override
                public void onRequest(AtmosphereResource r) throws IOException {
                    logger.debug("No AtmosphereHandler defined.");
                    if (!r.transport().equals(AtmosphereResource.TRANSPORT.WEBSOCKET)) {
                        WebSocket.notSupported(r.getRequest(), r.getResponse());
                    }
                }

                @Override
                public void destroy() {
                }
            });
        }
    }

    /**
     * Initialize the {@link EndpointMapper}.
     */
    @SuppressWarnings("unchecked")
    public void initEndpointMapper() {
        AtmosphereFramework fw = config.framework();
        String s = fw.getServletConfig().getInitParameter(ApplicationConfig.ENDPOINT_MAPPER);
        if (s != null) {
            try {
                endpointMapper = (EndpointMapper<AtmosphereHandlerWrapper>) fw.newClassInstance(EndpointMapper.class, (Class<EndpointMapper<?>>) (Class<?>) IOUtils.loadClass(fw.getClass(), s));
                logger.info("Installed EndpointMapper {} ", s);
            } catch (Exception ex) {
                logger.error("Cannot load the EndpointMapper {}", s, ex);
            }
        }
        endpointMapper.configure(config);
    }

    /**
     * Configure AtmosphereHandler from web.xml init parameters.
     */
    @SuppressWarnings("unchecked")
    public void configureWebDotXmlAtmosphereHandler(ServletConfig sc) {
        String s = sc.getInitParameter(ATMOSPHERE_HANDLER);
        if (s != null) {
            try {
                AtmosphereFramework fw = config.framework();
                String mapping = sc.getInitParameter(ATMOSPHERE_HANDLER_MAPPING);
                if (mapping == null) {
                    mapping = Broadcaster.ROOT_MASTER;
                }
                addAtmosphereHandler(mapping, fw.newClassInstance(AtmosphereHandler.class,
                        (Class<AtmosphereHandler>) IOUtils.loadClass(fw.getClass(), s)));
            } catch (Exception ex) {
                logger.warn("Unable to load WebSocketHandle instance", ex);
            }
        }
    }

    /**
     * Return the current {@link EndpointMapper}.
     */
    public EndpointMapper<AtmosphereHandlerWrapper> endPointMapper() {
        return endpointMapper;
    }

    /**
     * Set the {@link EndpointMapper}.
     */
    @SuppressWarnings("unchecked")
    public void endPointMapper(EndpointMapper<?> endpointMapper) {
        this.endpointMapper = (EndpointMapper<AtmosphereHandlerWrapper>) endpointMapper;
    }

    /**
     * Return the mapping regex.
     */
    public String mappingRegex() {
        return mappingRegex;
    }

    /**
     * Set the mapping regex.
     */
    public void mappingRegex(String mappingRegex) {
        this.mappingRegex = mappingRegex;
    }

    /**
     * Clear all handlers.
     */
    public void clear() {
        atmosphereHandlers.clear();
    }
}
