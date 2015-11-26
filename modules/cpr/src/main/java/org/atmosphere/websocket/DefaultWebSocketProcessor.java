/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.websocket;

import org.atmosphere.annotation.AnnotationUtil;
import org.atmosphere.config.service.Singleton;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereMappingException;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.Utils;
import org.atmosphere.util.VoidExecutorService;
import org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent;
import org.atmosphere.websocket.protocol.StreamingHttpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.Action.TYPE.SKIP_ATMOSPHEREHANDLER;
import static org.atmosphere.cpr.ApplicationConfig.INVOKE_ATMOSPHERE_INTERCEPTOR_ON_WEBSOCKET_MESSAGE;
import static org.atmosphere.cpr.ApplicationConfig.IN_MEMORY_STREAMING_BUFFER_SIZE;
import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROTOCOL_EXECUTION;
import static org.atmosphere.cpr.AtmosphereFramework.REFLECTOR_ATMOSPHEREHANDLER;
import static org.atmosphere.cpr.Broadcaster.ROOT_MASTER;
import static org.atmosphere.cpr.FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CLOSE;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CONNECT;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.MESSAGE;

/**
 * Like the {@link org.atmosphere.cpr.AsynchronousProcessor} class, this class is responsible for dispatching WebSocket request to the
 * proper {@link org.atmosphere.websocket.WebSocket} implementation. This class can be extended in order to support any protocol
 * running on top  websocket.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultWebSocketProcessor implements WebSocketProcessor, Serializable, WebSocketPingPongListener {

    private static final long serialVersionUID = 7526472295622776149L;
    private static final Logger logger = LoggerFactory.getLogger(DefaultWebSocketProcessor.class);

    private /* final */ AtmosphereFramework framework;
    private /* final */ WebSocketProtocol webSocketProtocol;
    private /* final */ boolean destroyable;
    private /* final */ boolean executeAsync;
    private ExecutorService asyncExecutor;
    private ScheduledExecutorService scheduler;
    private final Map<String, WebSocketHandlerProxy> handlers = new ConcurrentHashMap<String, WebSocketHandlerProxy>();
    private final EndpointMapper<WebSocketHandlerProxy> mapper = new DefaultEndpointMapper<WebSocketHandlerProxy>();
    private boolean wildcardMapping;
    // 2MB - like maxPostSize
    private int byteBufferMaxSize = 2097152;
    private int charBufferMaxSize = 2097152;
    private /* final */ long closingTime;
    private AsynchronousProcessor asynchronousProcessor;
    private /* final */ boolean invokeInterceptors;

    public DefaultWebSocketProcessor() {
    }

    public WebSocketProcessor configure(AtmosphereConfig config) {
        this.framework = config.framework();
        this.webSocketProtocol = framework.getWebSocketProtocol();

        String s = framework.getAtmosphereConfig().getInitParameter(RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
        if (s != null && Boolean.valueOf(s)) {
            destroyable = true;
        } else {
            destroyable = false;
        }

        s = framework.getAtmosphereConfig().getInitParameter(WEBSOCKET_PROTOCOL_EXECUTION);
        if (s != null && Boolean.valueOf(s)) {
            executeAsync = true;
        } else {
            executeAsync = false;
        }

        s = framework.getAtmosphereConfig().getInitParameter(IN_MEMORY_STREAMING_BUFFER_SIZE);
        if (s != null) {
            byteBufferMaxSize = Integer.valueOf(s);
            charBufferMaxSize = byteBufferMaxSize;
        }

        if (executeAsync) {
            asyncExecutor = ExecutorsFactory.getAsyncOperationExecutor(config, "WebSocket");
        } else {
            asyncExecutor = VoidExecutorService.VOID;
        }

        scheduler = ExecutorsFactory.getScheduler(config);
        optimizeMapping();

        closingTime = Long.valueOf(config.getInitParameter(ApplicationConfig.CLOSED_ATMOSPHERE_THINK_TIME, "0"));
        invokeInterceptors = Boolean.valueOf(config.getInitParameter(INVOKE_ATMOSPHERE_INTERCEPTOR_ON_WEBSOCKET_MESSAGE, "true"));
        config.startupHook(new AtmosphereConfig.StartupHook() {
            @Override
            public void started(final AtmosphereFramework framework) {
                if (AsynchronousProcessor.class.isAssignableFrom(framework.getAsyncSupport().getClass())) {
                    asynchronousProcessor = AsynchronousProcessor.class.cast(framework.getAsyncSupport());
                } else {
                    asynchronousProcessor = new AsynchronousProcessor(framework.getAtmosphereConfig()) {
                        @Override
                        public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                            return framework.getAsyncSupport().service(req, res);
                        }
                    };
                }
            }
        });
        return this;
    }

    @Override
    public boolean handshake(HttpServletRequest request) {
        if (request != null) {
            logger.trace("Processing request {}", request);
        }
        return true;
    }

    @Override
    public WebSocketProcessor registerWebSocketHandler(String path, WebSocketHandlerProxy webSockethandler) {
        handlers.put(path, webSockethandler.path(path));
        return this;
    }

    @Override
    public final void open(final WebSocket webSocket, final AtmosphereRequest request, final AtmosphereResponse response) throws IOException {

        if (framework.isDestroyed()) return;

        // TODO: Fix this. Instead add an Interceptor.
        if (framework.getAtmosphereConfig().handlers().isEmpty()) {
            synchronized (framework) {
                if (handlers.isEmpty()) {
                    logger.warn("No AtmosphereHandler or WebSocketHandler installed. Adding a default one.");
                }
                framework.addAtmosphereHandler(ROOT_MASTER, REFLECTOR_ATMOSPHEREHANDLER);
            }
        }

        request.headers(configureHeader(request)).setAttribute(WebSocket.WEBSOCKET_SUSPEND, true);

        AtmosphereResource r = framework.atmosphereFactory().create(framework.getAtmosphereConfig(),
                response,
                framework.getAsyncSupport());

        boolean cleanUpAfterDisconnect = false;
        try {
            request.setAttribute(INJECTED_ATMOSPHERE_RESOURCE, r);
            request.setAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID, r.uuid());

            if (Utils.firefoxWebSocketEnabled(request)) {
                request.setAttribute("firefox", "true");
            }

            AtmosphereResourceImpl.class.cast(r).webSocket(webSocket);
            webSocket.resource(r);
            webSocketProtocol.onOpen(webSocket);
            WebSocketHandler proxy = null;
            if (!handlers.isEmpty()) {
                WebSocketHandlerProxy handler = mapper.map(request, handlers);
                if (handler == null) {
                    logger.debug("No WebSocketHandler maps request for {} with mapping {}", request.getRequestURI(), handlers);
                    throw new AtmosphereMappingException("No AtmosphereHandler maps request for " + request.getRequestURI());
                }
                proxy = postProcessMapping(webSocket, request, handler);
            }

            dispatch(webSocket, request, response);

            if (proxy != null) {
                webSocket.webSocketHandler(proxy).resource().suspend(-1);
                proxy.onOpen(webSocket);
            }

            request.removeAttribute(INJECTED_ATMOSPHERE_RESOURCE);

            // Resource can be null if the client disconnect.
            if (webSocket.resource() != null) {
                final Action action = ((AtmosphereResourceImpl) webSocket.resource()).action();
                if (action.timeout() != -1 && !framework.getAsyncSupport().getContainerName().contains("Netty")) {
                    final AtomicReference<Future<?>> f = new AtomicReference();
                    f.set(scheduler.scheduleAtFixedRate(new Runnable() {
                        @Override
                        public void run() {
                            if (WebSocket.class.isAssignableFrom(webSocket.getClass())
                                    && System.currentTimeMillis() - WebSocket.class.cast(webSocket).lastWriteTimeStampInMilliseconds() > action.timeout()) {
                                asynchronousProcessor.endRequest(((AtmosphereResourceImpl) webSocket.resource()), false);
                                f.get().cancel(true);
                            }
                        }
                    }, action.timeout(), action.timeout(), TimeUnit.MILLISECONDS));
                }
            } else {
                logger.warn("AtmosphereResource was null");
                cleanUpAfterDisconnect = true;
            }
            notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent("", CONNECT, webSocket));
        } catch (AtmosphereMappingException ex) {
            cleanUpAfterDisconnect = true;
            throw ex;
        } catch (IOException ex) {
            cleanUpAfterDisconnect = true;
            throw ex;
        } catch (Exception ex) {
            logger.trace("onOpen exception", ex);
            cleanUpAfterDisconnect = true;
        } finally {
            if (cleanUpAfterDisconnect) {
                logger.warn("Problem opening websocket for {}", r.uuid());
                framework.atmosphereFactory().remove(r.uuid());

                AtmosphereResourceEventImpl.class.cast(r.getAtmosphereResourceEvent()).setCancelled(true);
                AsynchronousProcessor.class.cast(framework.getAsyncSupport()).completeLifecycle(r, true);
            }
            webSocket.shiftAttributes();
        }
    }

    protected WebSocketHandler postProcessMapping(WebSocket webSocket, AtmosphereRequest request, WebSocketHandlerProxy w) {
        WebSocketHandlerProxy p = null;
        String path = w.path();
        if (wildcardMapping()) {
            String pathInfo = null;
            try {
                pathInfo = request.getPathInfo();
            } catch (IllegalStateException ex) {
                // http://java.net/jira/browse/GRIZZLY-1301
            }

            if (pathInfo != null) {
                path = request.getServletPath() + pathInfo;
            } else {
                path = request.getServletPath();
            }

            if (path == null || path.isEmpty()) {
                path = "/";
            }

            synchronized (handlers) {
                p = handlers.get(path);
                if (p == null) {
                    // AtmosphereHandlerService
                    WebSocketHandlerService a = w.proxied.getClass().getAnnotation(WebSocketHandlerService.class);
                    if (a != null) {
                        String targetPath = a.path();
                        if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                            try {
                                boolean singleton = w.proxied.getClass().getAnnotation(Singleton.class) != null;

                                if (!singleton) {
                                    w = new WebSocketHandlerProxy(a.broadcaster(),
                                            framework.newClassInstance(WebSocketHandler.class, w.proxied.getClass()));
                                }

                                registerWebSocketHandler(path, new WebSocketHandlerProxy(a.broadcaster(), w));
                                request.localAttributes().put(Named.class.getName(), path.substring(targetPath.indexOf("{")));

                                p = handlers.get(path);
                            } catch (Throwable e) {
                                logger.warn("Unable to create WebSocketHandler", e);
                            }
                        }
                    }
                }
            }
        }

        try {
            webSocket.resource().setBroadcaster(AnnotationUtil.broadcaster(framework, p != null ? p.broadcasterClazz : w.broadcasterClazz, path));
        } catch (Exception e) {
            logger.error("", e);
        }

        return p != null ? p : w;
    }

    private void dispatch(final WebSocket webSocket, List<AtmosphereRequest> list) {
        if (list == null) return;

        for (final AtmosphereRequest r : list) {
            if (r != null) {

                asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        AtmosphereResponse w = new AtmosphereResponseImpl(webSocket, r, destroyable);
                        try {
                            dispatch(webSocket, r, w);
                        } finally {
                            r.destroy();
                            w.destroy();
                        }
                    }
                });
            }
        }
    }

    private WebSocketHandlerProxy webSocketHandlerForMessage(WebSocket webSocket) {
        AtmosphereResourceImpl impl = AtmosphereResourceImpl.class.cast(webSocket.resource());
        if (impl != null) {
            impl.getRequest(false).setAttribute(FrameworkConfig.WEBSOCKET_MESSAGE, "true");

            try {
                Utils.inject(impl);
            } catch (IllegalAccessException e) {
                logger.warn("", e);
            }

        }
        return WebSocketHandlerProxy.class.cast(webSocket.webSocketHandler());
    }


    private void invokeInterceptors(WebSocketHandlerProxy webSocketHandler,
                                    WebSocket webSocket, Object webSocketMessageAsBody) throws IOException {
        invokeInterceptors(webSocketHandler, webSocket, webSocketMessageAsBody, 0, 0);
    }

    private void invokeInterceptors(WebSocketHandlerProxy webSocketHandler,
                                    WebSocket webSocket, Object webSocketMessageAsBody, int offset, int length) throws IOException {

        AtmosphereResourceImpl resource = AtmosphereResourceImpl.class.cast(webSocket.resource());
        if (resource == null) {
            return;
        }

        AtmosphereRequest request = resource.getRequest(false);
        if (String.class.isAssignableFrom(webSocketMessageAsBody.getClass())) {
            request.body(String.class.cast(webSocketMessageAsBody));
        } else if (Reader.class.isAssignableFrom(webSocketMessageAsBody.getClass())) {
            request.body(Reader.class.cast(webSocketMessageAsBody));
        } else if (InputStream.class.isAssignableFrom(webSocketMessageAsBody.getClass())) {
            request.body(InputStream.class.cast(webSocketMessageAsBody));
        } else {
            request.body(new ByteArrayInputStream((byte[]) webSocketMessageAsBody, offset, length));
        }

        String path = webSocketHandler.proxied.getClass().isAnnotationPresent(WebSocketHandlerService.class) ?
                webSocketHandler.proxied.getClass().getAnnotation(WebSocketHandlerService.class).path() : "/";

        AtmosphereFramework.AtmosphereHandlerWrapper w = framework.getAtmosphereHandlers().get(framework.normalizePath(path));
        List<AtmosphereInterceptor> l;
        if (w == null) {
            l = framework.interceptors();
        } else {
            l = w.interceptors;
        }

        try {
            // Globally defined
            int tracing = 0;
            Action a = asynchronousProcessor.invokeInterceptors(l, resource, tracing);
            if (a.type() != Action.TYPE.CONTINUE && a.type() != Action.TYPE.SKIP_ATMOSPHEREHANDLER) {
                return;
            }

            //Unit test mock the request and will throw NPE.
            boolean skipAtmosphereHandler = request.getAttribute(SKIP_ATMOSPHEREHANDLER.name()) != null
                    ? (Boolean) request.getAttribute(SKIP_ATMOSPHEREHANDLER.name()) : Boolean.FALSE;
            if (!skipAtmosphereHandler) {
                try {
                    if (String.class.isAssignableFrom(webSocketMessageAsBody.getClass())) {
                        webSocketHandler.onTextMessage(webSocket, String.class.cast(webSocketMessageAsBody));
                    } else if (Reader.class.isAssignableFrom(webSocketMessageAsBody.getClass())) {
                        WebSocketStreamingHandler.class.cast(webSocketHandler.proxied).onTextStream(webSocket, Reader.class.cast(webSocketMessageAsBody));
                    } else if (InputStream.class.isAssignableFrom(webSocketMessageAsBody.getClass())) {
                        WebSocketStreamingHandler.class.cast(webSocketHandler.proxied()).onBinaryStream(webSocket, InputStream.class.cast(webSocketMessageAsBody));
                    } else {
                        webSocketHandler.onByteMessage(webSocket, (byte[]) webSocketMessageAsBody, offset, length);
                    }
                } catch (IOException t) {
                    resource.onThrowable(t);
                    throw t;
                }
            }
            request.setAttribute(SKIP_ATMOSPHEREHANDLER.name(), Boolean.FALSE);
        } finally {
            asynchronousProcessor.postInterceptors(l, resource);
        }
    }

    @Override
    public void invokeWebSocketProtocol(final WebSocket webSocket, String webSocketMessage) {
        WebSocketHandlerProxy webSocketHandler = webSocketHandlerForMessage(webSocket);

        if (webSocketHandler == null) {
            if (!WebSocketProtocolStream.class.isAssignableFrom(webSocketProtocol.getClass())) {
                List<AtmosphereRequest> list = webSocketProtocol.onMessage(webSocket, webSocketMessage);
                dispatch(webSocket, list);
            } else {
                logger.debug("The WebServer doesn't support streaming. Wrapping the message as stream.");
                invokeWebSocketProtocol(webSocket, new StringReader(webSocketMessage));
                return;
            }
        } else {
            if (!WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.proxied().getClass())) {
                try {
                    if (invokeInterceptors) {
                        invokeInterceptors(webSocketHandler, webSocket, webSocketMessage);
                    } else {
                        webSocketHandler.onTextMessage(webSocket, webSocketMessage);
                    }
                } catch (Exception ex) {
                    handleException(ex, webSocket, webSocketHandler);
                }
            } else {
                logger.debug("The WebServer doesn't support streaming. Wrapping the message as stream.");
                invokeWebSocketProtocol(webSocket, new StringReader(webSocketMessage));
                return;
            }
        }
        notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent(webSocketMessage, MESSAGE, webSocket));
    }

    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, byte[] data, int offset, int length) {
        WebSocketHandlerProxy webSocketHandler = webSocketHandlerForMessage(webSocket);

        if (webSocketHandler == null) {
            if (!WebSocketProtocolStream.class.isAssignableFrom(webSocketProtocol.getClass())) {
                List<AtmosphereRequest> list = webSocketProtocol.onMessage(webSocket, data, offset, length);
                dispatch(webSocket, list);
            } else {
                logger.debug("The WebServer doesn't support streaming. Wrapping the message as stream.");
                invokeWebSocketProtocol(webSocket, new ByteArrayInputStream(data, offset, length));
                return;
            }
        } else {
            if (!WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.proxied().getClass())) {
                try {
                    if (invokeInterceptors) {
                        invokeInterceptors(webSocketHandler, webSocket, data, offset, length);
                    } else {
                        webSocketHandler.onByteMessage(webSocket, data, offset, length);
                    }
                } catch (Exception ex) {
                    handleException(ex, webSocket, webSocketHandler);
                }
            } else {
                logger.debug("The WebServer doesn't support streaming. Wrapping the message as stream.");
                invokeWebSocketProtocol(webSocket, new ByteArrayInputStream(data, offset, length));
                return;
            }
        }
        notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent<byte[]>(data, MESSAGE, webSocket));
    }

    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, InputStream stream) {
        WebSocketHandlerProxy webSocketHandler = webSocketHandlerForMessage(webSocket);

        try {
            if (webSocketHandler == null) {
                if (WebSocketProtocolStream.class.isAssignableFrom(webSocketProtocol.getClass())) {
                    List<AtmosphereRequest> list = WebSocketProtocolStream.class.cast(webSocketProtocol).onBinaryStream(webSocket, stream);
                    dispatch(webSocket, list);
                } else {
                    dispatchStream(webSocket, stream);
                    return;
                }
            } else {
                if (WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.proxied.getClass())) {
                    if (invokeInterceptors) {
                        invokeInterceptors(webSocketHandler, webSocket, stream);
                    } else {
                        WebSocketStreamingHandler.class.cast(webSocketHandler.proxied()).onBinaryStream(webSocket, stream);
                    }
                } else {
                    dispatchStream(webSocket, stream);
                    return;
                }

            }
        } catch (Exception ex) {
            handleException(ex, webSocket, webSocketHandler);
        }

        notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent<InputStream>(stream, MESSAGE, webSocket));
    }

    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, Reader reader) {
        WebSocketHandlerProxy webSocketHandler = webSocketHandlerForMessage(webSocket);

        try {
            if (webSocketHandler == null) {
                if (WebSocketProtocolStream.class.isAssignableFrom(webSocketProtocol.getClass())) {
                    List<AtmosphereRequest> list = WebSocketProtocolStream.class.cast(webSocketProtocol).onTextStream(webSocket, reader);
                    dispatch(webSocket, list);
                } else {
                    dispatchReader(webSocket, reader);
                    return;
                }
            } else {
                if (WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.proxied().getClass())) {
                    if (invokeInterceptors) {
                        invokeInterceptors(webSocketHandler, webSocket, reader);
                    } else {
                        WebSocketStreamingHandler.class.cast(webSocketHandler.proxied()).onTextStream(webSocket, reader);
                    }
                } else {
                    dispatchReader(webSocket, reader);
                    return;
                }
            }
        } catch (Exception ex) {
            handleException(ex, webSocket, webSocketHandler);
        }

        notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent<Reader>(reader, MESSAGE, webSocket));
    }

    private void handleException(Exception ex, WebSocket webSocket, WebSocketHandler webSocketHandler) {
        logger.error("", ex);
        AtmosphereResource r = webSocket.resource();
        if (r != null) {
            webSocketHandler.onError(webSocket, new WebSocketException(ex,
                    new AtmosphereResponseImpl.Builder()
                            .request(r != null ? AtmosphereResourceImpl.class.cast(r).getRequest(false) : null)
                            .status(500)
                            .statusMessage("Server Error").build()));
        }
    }

    /**
     * Dispatch to request/response to the {@link org.atmosphere.cpr.AsyncSupport} implementation as it was a normal HTTP request.
     *
     * @param request a {@link AtmosphereRequest}
     * @param r       a {@link AtmosphereResponse}
     */
    public final void dispatch(WebSocket webSocket, final AtmosphereRequest request, final AtmosphereResponse r) {
        if (request == null) return;

        try {
            framework.doCometSupport(request, r);
        } catch (Throwable e) {
            logger.warn("Failed invoking AtmosphereFramework.doCometSupport()", e);
            webSocketProtocol.onError(webSocket, new WebSocketException(e,
                    new AtmosphereResponseImpl.Builder()
                            .request(request)
                            .status(500)
                            .statusMessage("Server Error").build()));
            return;
        }

        if (r.getStatus() >= 400) {
            webSocketProtocol.onError(webSocket, new WebSocketException("Status code higher or equal than 400", r));
        }
    }

    @Override
    public void close(final WebSocket webSocket, int closeCode) {
        WebSocketHandler webSocketHandler = webSocket.webSocketHandler();
        // A message might be in the process of being processed and the websocket gets closed. In that corner
        // case the webSocket.resource will be set to false and that might cause NPE in some WebSocketProcol implementation
        // We could potentially synchronize on webSocket but since it is a rare case, it is better to not synchronize.
        // synchronized (webSocket) {

        final AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();

        if (resource == null) {
            logger.trace("Already closed {}", webSocket);
        } else {
            final boolean allowedToClose = allowedCloseCode(closeCode);

            final AtmosphereRequest r = resource.getRequest(false);
            final AtmosphereResponse s = resource.getResponse(false);
            boolean ff = r.getAttribute("firefox") != null;
            boolean completeLifecycle = true;
            try {
                webSocketProtocol.onClose(webSocket);

                if (webSocketHandler != null) {
                    webSocketHandler.onClose(webSocket);
                }

                logger.trace("About to close AtmosphereResource for {} with code {}", resource, closeCode);
                if (!resource.getAtmosphereResourceEvent().isClosedByClient() && !resource.getAtmosphereResourceEvent().isClosedByApplication() && !resource.isCancelled()) {
                    // See https://github.com/Atmosphere/atmosphere/issues/1590
                    // Better to call onDisconnect that onResume.
                    if (allowedToClose) {
                        if (ff || closingTime > 0) {
                            completeLifecycle = false;
                            logger.debug("Delaying closing operation for firefox and resource {}", resource.uuid());
                            ExecutorsFactory.getScheduler(framework.getAtmosphereConfig()).schedule(new Callable<Object>() {
                                @Override
                                public Object call() throws Exception {
                                    executeClose(webSocket, 1005);
                                    finish(webSocket, resource, r, s, !allowedToClose);
                                    return null;
                                }
                            }, ff ? (closingTime == 0 ? 1000 : closingTime) : closingTime, TimeUnit.MILLISECONDS);
                            resource.getAndSetPendingClose();
                        } else {
                            executeClose(webSocket, closeCode);
                        }
                    } else {
                        logger.debug("Timeout {}", resource.uuid());
                        asynchronousProcessor.endRequest(AtmosphereResourceImpl.class.cast(webSocket.resource()), false);
                    }
                } else {
                    logger.trace("Unable to properly complete {}", resource == null ? "null" : resource.uuid());
                    completeLifecycle = false;
                }
            } finally {
                if (completeLifecycle) {
                    finish(webSocket, resource, r, s, !allowedToClose);
                }
            }
        }
    }

    // Highly bogus based on which I/O layer we are using.
    private boolean allowedCloseCode(int closeCode) {
        return closeCode <= 1001 || closeCode > 1004 ? true : false;
    }

    private void finish(WebSocket webSocket, AtmosphereResource resource, AtmosphereRequest r, AtmosphereResponse s, boolean closeWebSocket) {
        // Don't take any risk in case something goes wrong and remove the associated resource.
        framework.atmosphereFactory().remove(resource.uuid());
        if (webSocket != null) {
            try {
                r.setAttribute(WebSocket.CLEAN_CLOSE, Boolean.TRUE);
                webSocket.resource(null);

                if (closeWebSocket) webSocket.close(s);
            } catch (IOException e) {
                logger.trace("", e);
            }
        }

        if (r != null) {
            r.destroy(true);
        }

        if (s != null) {
            s.destroy(true);
        }
    }

    public void executeClose(WebSocket webSocket, int closeCode) {
        AtmosphereResource r = webSocket.resource();

        boolean isClosedByClient = r == null ? true : r.getAtmosphereResourceEvent().isClosedByClient();
        try {
            if (r != null) {
                asynchronousProcessor.endRequest(AtmosphereResourceImpl.class.cast(r), true);
            }
        } finally {
            if (!isClosedByClient) {
                notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent(closeCode, CLOSE, webSocket));
            }
        }
    }

    @Override
    public void destroy() {
        boolean shared = framework.isShareExecutorServices();
        if (asyncExecutor != null && !shared) {
            asyncExecutor.shutdown();
        }

        if (scheduler != null && !shared) {
            scheduler.shutdown();
        }
    }

    @Override
    public void notifyListener(WebSocket webSocket, WebSocketEventListener.WebSocketEvent event) {
        AtmosphereResource resource = webSocket.resource();
        if (resource == null) return;

        AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(resource);
        for (AtmosphereResourceEventListener l : r.atmosphereResourceEventListener()) {
            if (WebSocketEventListener.class.isAssignableFrom(l.getClass())) {
                try {
                    switch (event.type()) {
                        case CONNECT:
                            WebSocketEventListener.class.cast(l).onConnect(event);
                            break;
                        case DISCONNECT:
                            WebSocketEventListener.class.cast(l).onDisconnect(event);
                            onDisconnect(event, l);
                            break;
                        case CONTROL:
                            WebSocketEventListener.class.cast(l).onControl(event);
                            break;
                        case MESSAGE:
                            WebSocketEventListener.class.cast(l).onMessage(event);
                            break;
                        case HANDSHAKE:
                            WebSocketEventListener.class.cast(l).onHandshake(event);
                            break;
                        case CLOSE:
                            boolean isClosedByClient = r.getAtmosphereResourceEvent().isClosedByClient();
                            l.onDisconnect(new AtmosphereResourceEventImpl(r, !isClosedByClient, false, isClosedByClient, null));
                            onDisconnect(event, l);
                            WebSocketEventListener.class.cast(l).onClose(event);
                            break;
                    }
                } catch (Throwable t) {
                    logger.debug("Listener error {}", t);
                    try {
                        WebSocketEventListener.class.cast(l).onThrowable(new AtmosphereResourceEventImpl(r, false, false, t));
                    } catch (Throwable t2) {
                        logger.warn("Listener error {}", t2);
                    }
                }
            } else {
                switch (event.type()) {
                    case CLOSE:
                        boolean isClosedByClient = r.getAtmosphereResourceEvent().isClosedByClient();
                        l.onDisconnect(new AtmosphereResourceEventImpl(r, !isClosedByClient, false, isClosedByClient, null));
                        break;
                }
            }
        }
    }

    private void onDisconnect(WebSocketEvent event, AtmosphereResourceEventListener l) {
        if (event.webSocket() != null && event.webSocket().resource() != null) {
            framework.notifyDestroyed(event.webSocket().resource().uuid());
        }
        WebSocketEventListener.class.cast(l).onDisconnect(event);
    }

    public static final Map<String, String> configureHeader(AtmosphereRequest request) {
        Map<String, String> headers = new HashMap<String, String>();

        Enumeration<String> e = request.getParameterNames();
        String s;
        while (e.hasMoreElements()) {
            s = e.nextElement();
            headers.put(s, request.getParameter(s));
        }

        headers.put(HeaderConfig.X_ATMOSPHERE_TRANSPORT, HeaderConfig.WEBSOCKET_TRANSPORT);
        return headers;
    }

    protected void dispatchStream(WebSocket webSocket, InputStream is) throws IOException {
        int read = 0;
        ByteBuffer bb = webSocket.bb;
        try {
            while (read > -1) {
                bb.position(bb.position() + read);
                if (bb.remaining() == 0) {
                    bb = resizeByteBuffer(webSocket);
                }
                read = is.read(bb.array(), bb.position(), bb.remaining());
            }
            bb.flip();

            invokeWebSocketProtocol(webSocket, bb.array(), 0, bb.limit());
        } finally {
            bb.clear();
        }
    }

    protected void dispatchReader(WebSocket webSocket, Reader r) throws IOException {
        int read = 0;
        CharBuffer cb = webSocket.cb;
        try {
            while (read > -1) {
                cb.position(cb.position() + read);
                if (cb.remaining() == 0) {
                    cb = resizeCharBuffer(webSocket);
                }
                read = r.read(cb.array(), cb.position(), cb.remaining());
            }
            cb.flip();
            invokeWebSocketProtocol(webSocket, cb.toString());
        } finally {
            cb.clear();
        }
    }

    private ByteBuffer resizeByteBuffer(WebSocket webSocket) throws IOException {
        int maxSize = byteBufferMaxSize;
        ByteBuffer bb = webSocket.bb;
        if (bb.limit() >= maxSize) {
            throw new IOException("Message Buffer too small. Use " + StreamingHttpProtocol.class.getName() + " when streaming over websocket.");
        }

        long newSize = bb.limit() * 2;
        if (newSize > maxSize) {
            newSize = maxSize;
        }

        // Cast is safe. newSize < maxSize and maxSize is an int
        ByteBuffer newBuffer = ByteBuffer.allocate((int) newSize);
        bb.rewind();
        newBuffer.put(bb);
        webSocket.bb = newBuffer;
        return newBuffer;
    }

    private CharBuffer resizeCharBuffer(WebSocket webSocket) throws IOException {
        int maxSize = charBufferMaxSize;
        CharBuffer cb = webSocket.cb;
        if (cb.limit() >= maxSize) {
            throw new IOException("Message Buffer too small. Use " + StreamingHttpProtocol.class.getName() + " when streaming over websocket.");
        }

        long newSize = cb.limit() * 2;
        if (newSize > maxSize) {
            newSize = maxSize;
        }

        // Cast is safe. newSize < maxSize and maxSize is an int
        CharBuffer newBuffer = CharBuffer.allocate((int) newSize);
        cb.rewind();
        newBuffer.put(cb);
        webSocket.cb = newBuffer;
        return newBuffer;
    }

    protected void optimizeMapping() {
        for (String w : framework.getAtmosphereConfig().handlers().keySet()) {
            if (w.contains("{") && w.contains("}")) {
                wildcardMapping = true;
            }
        }
    }

    public boolean wildcardMapping() {
        return wildcardMapping;
    }

    public DefaultWebSocketProcessor wildcardMapping(boolean wildcardMapping) {
        this.wildcardMapping = wildcardMapping;
        return this;
    }

    public Map<String, WebSocketHandlerProxy> handlers() {
        return handlers;
    }

    public boolean executeAsync() {
        return executeAsync;
    }

    public boolean destroyable() {
        return destroyable;
    }

    public int byteBufferMaxSize() {
        return byteBufferMaxSize;
    }

    public DefaultWebSocketProcessor byteBufferMaxSize(int byteBufferMaxSize) {
        this.byteBufferMaxSize = byteBufferMaxSize;
        return this;
    }

    public int charBufferMaxSize() {
        return charBufferMaxSize;
    }

    public DefaultWebSocketProcessor charBufferMaxSize(int charBufferMaxSize) {
        this.charBufferMaxSize = charBufferMaxSize;
        return this;
    }

    public long closingTime() {
        return closingTime;
    }

    public EndpointMapper<WebSocketHandlerProxy> mapper() {
        return mapper;
    }

    public boolean invokeInterceptors() {
        return invokeInterceptors;
    }

    @Override
    public void onPong(WebSocket webSocket, byte[] payload, int offset, int length) {
        WebSocketHandlerProxy webSocketHandler = webSocketHandlerForMessage(webSocket);

        if (webSocketHandler != null &&
                WebSocketPingPongListener.class.isAssignableFrom(webSocketHandler.proxied().getClass())) {
            WebSocketPingPongListener.class.cast(webSocketHandler.proxied()).onPong(webSocket, payload, offset, length);
        }
    }

    @Override
    public void onPing(WebSocket webSocket, byte[] payload, int offset, int length) {
        WebSocketHandlerProxy webSocketHandler = webSocketHandlerForMessage(webSocket);

        if (webSocketHandler != null &&
                WebSocketPingPongListener.class.isAssignableFrom(webSocketHandler.proxied().getClass())) {
            WebSocketPingPongListener.class.cast(webSocketHandler.proxied()).onPing(webSocket, payload, offset, length);
        }
    }
}
