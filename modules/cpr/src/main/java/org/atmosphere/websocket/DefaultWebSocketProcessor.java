/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.config.service.Singleton;
import org.atmosphere.config.service.WebSocketHandlerService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereMappingException;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceFactory;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.VoidExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.atmosphere.cpr.ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROTOCOL_EXECUTION;
import static org.atmosphere.cpr.FrameworkConfig.ASYNCHRONOUS_HOOK;
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
public class DefaultWebSocketProcessor implements WebSocketProcessor, Serializable {

    private static final Logger logger = LoggerFactory.getLogger(DefaultWebSocketProcessor.class);

    private final AtmosphereFramework framework;
    private final WebSocketProtocol webSocketProtocol;
    private final AtomicBoolean loggedMsg = new AtomicBoolean(false);
    private final boolean destroyable;
    private final boolean executeAsync;
    private ExecutorService asyncExecutor;
    private ScheduledExecutorService scheduler;
    private final Map<String, WebSocketHandler> handlers = new ConcurrentHashMap<String, WebSocketHandler>();
    private final EndpointMapper<WebSocketHandler> mapper = new DefaultEndpointMapper<WebSocketHandler>();
    private final AtmosphereHandler voidHandler = new AtmosphereHandler() {
        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        }

        @Override
        public void destroy() {
        }
    };
    private boolean wildcardMapping = false;
    // 2MB - like maxPostSize
    private int byteBufferMaxSize = 2097152;
    private int charBufferMaxSize = 2097152;

    private ByteBuffer bb = ByteBuffer.allocate(8192);
    private CharBuffer cb = CharBuffer.allocate(8192);

    public DefaultWebSocketProcessor(AtmosphereFramework framework) {
        this.framework = framework;
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

        AtmosphereConfig config = framework.getAtmosphereConfig();
        if (executeAsync) {
            asyncExecutor = ExecutorsFactory.getAsyncOperationExecutor(config, "WebSocket");
        } else {
            asyncExecutor = VoidExecutorService.VOID;
        }

        scheduler = ExecutorsFactory.getScheduler(config);
        optimizeMapping();
    }

    @Override
    public WebSocketProcessor registerWebSocketHandler(String path, WebSocketHandler webSockethandler) {
        handlers.put(path, webSockethandler);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void open(final WebSocket webSocket, final AtmosphereRequest request, final AtmosphereResponse response) throws IOException {
        if (!loggedMsg.getAndSet(true)) {
            logger.debug("Atmosphere detected WebSocket: {}", webSocket.getClass().getName());
        }

        // TODO: Fix this. Instead add an Interceptor.
        if (framework.getAtmosphereConfig().handlers().size() == 0) {
            framework.addAtmosphereHandler("/*", voidHandler);
        }

        request.headers(configureHeader(request)).setAttribute(WebSocket.WEBSOCKET_SUSPEND, true);

        AtmosphereResource r = AtmosphereResourceFactory.getDefault().create(framework.getAtmosphereConfig(),
                response,
                framework.getAsyncSupport());

        request.setAttribute(INJECTED_ATMOSPHERE_RESOURCE, r);
        request.setAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID, r.uuid());

        webSocket.resource(r);
        webSocketProtocol.onOpen(webSocket);

        // We must dispatch to execute AtmosphereInterceptor
        dispatch(webSocket, request, response);
        if (handlers.size() != 0) {
            WebSocketHandler handler = mapper.map(request, handlers);
            if (handler == null) {
                logger.debug("No WebSocketHandler maps request for {} with mapping {}", request.getRequestURI(), handlers);
                throw new AtmosphereMappingException("No AtmosphereHandler maps request for " + request.getRequestURI());
            }
            handler = postProcessMapping(webSocket, request, handler);

            // Force suspend.
            webSocket.webSocketHandler(handler).resource().suspend(-1);
            handler.onOpen(webSocket);
        }
        request.removeAttribute(INJECTED_ATMOSPHERE_RESOURCE);

        if (webSocket.resource() != null) {
            final AsynchronousProcessor.AsynchronousProcessorHook hook =
                    new AsynchronousProcessor.AsynchronousProcessorHook((AtmosphereResourceImpl) webSocket.resource());
            request.setAttribute(ASYNCHRONOUS_HOOK, hook);

            final Action action = ((AtmosphereResourceImpl) webSocket.resource()).action();
            if (action.timeout() != -1 && !framework.getAsyncSupport().getContainerName().contains("Netty")) {
                final AtomicReference<Future<?>> f = new AtomicReference();
                f.set(scheduler.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        if (WebSocket.class.isAssignableFrom(webSocket.getClass())
                                && System.currentTimeMillis() - WebSocket.class.cast(webSocket).lastWriteTimeStampInMilliseconds() > action.timeout()) {
                            hook.timedOut();
                            f.get().cancel(true);
                        }
                    }
                }, action.timeout(), action.timeout(), TimeUnit.MILLISECONDS));
            }
        } else {
            logger.warn("AtmosphereResource was null");
        }
        notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent("", CONNECT, webSocket));
    }

    protected WebSocketHandler postProcessMapping(WebSocket webSocket, AtmosphereRequest request, WebSocketHandler w) {

        if (!wildcardMapping()) return w;

        String path;
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
            if (handlers.get(path) == null) {
                // AtmosphereHandlerService
                if (w.getClass().getAnnotation(WebSocketHandlerService.class) != null) {
                    String targetPath = w.getClass().getAnnotation(WebSocketHandlerService.class).path();
                    if (targetPath.indexOf("{") != -1 && targetPath.indexOf("}") != -1) {
                        try {
                            boolean singleton = w.getClass().getAnnotation(Singleton.class) != null;
                            if (!singleton) {
                                registerWebSocketHandler(path, w.getClass().newInstance());
                            } else {
                                registerWebSocketHandler(path, w);
                            }
                            w = handlers.get(path);
                        } catch (Throwable e) {
                            logger.warn("Unable to create WebSocketHandler", e);
                        }
                    }
                }
            }
        }
        webSocket.resource().setBroadcaster(framework.getBroadcasterFactory().lookup(path, true));
        return w;
    }

    private void dispatch(final WebSocket webSocket, List<AtmosphereRequest> list) {
        if (list == null) return;

        for (final AtmosphereRequest r : list) {
            if (r != null) {

                boolean b = r.dispatchRequestAsynchronously();
                asyncExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        AtmosphereResponse w = new AtmosphereResponse(webSocket, r, destroyable);
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void invokeWebSocketProtocol(final WebSocket webSocket, String webSocketMessage) {
        WebSocketHandler webSocketHandler = webSocket.webSocketHandler();

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
            if (!WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.getClass())) {
                try {
                    webSocketHandler.onTextMessage(webSocket, webSocketMessage);
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, byte[] data, int offset, int length) {
        WebSocketHandler webSocketHandler = webSocket.webSocketHandler();

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
            if (!WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.getClass())) {
                try {
                    webSocketHandler.onByteMessage(webSocket, data, offset, length);
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

    private void handleException(Exception ex, WebSocket webSocket, WebSocketHandler webSocketHandler) {
        logger.error("", ex);
        AtmosphereResource r = webSocket.resource();
        webSocketHandler.onError(webSocket, new WebSocketException(ex,
                new AtmosphereResponse.Builder()
                        .request(r != null ? r.getRequest() : null)
                        .status(500)
                        .statusMessage("Server Error").build()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, InputStream stream) {
        WebSocketHandler webSocketHandler = webSocket.webSocketHandler();
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
                if (WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.getClass())) {
                    WebSocketStreamingHandler.class.cast(webSocketHandler).onBinaryStream(webSocket, stream);
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void invokeWebSocketProtocol(WebSocket webSocket, Reader reader) {
        WebSocketHandler webSocketHandler = webSocket.webSocketHandler();

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
                if (WebSocketStreamingHandler.class.isAssignableFrom(webSocketHandler.getClass())) {
                    WebSocketStreamingHandler.class.cast(webSocketHandler).onTextStream(webSocket, reader);
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
                    new AtmosphereResponse.Builder()
                            .request(request)
                            .status(500)
                            .statusMessage("Server Error").build()));
            return;
        }

        if (r.getStatus() >= 400) {
            webSocketProtocol.onError(webSocket, new WebSocketException("Status code higher or equal than 400", r));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close(WebSocket webSocket, int closeCode) {
        logger.trace("WebSocket closed with {}", closeCode);

        WebSocketHandler webSocketHandler = webSocket.webSocketHandler();
        // A message might be in the process of being processed and the websocket gets closed. In that corner
        // case the webSocket.resource will be set to false and that might cause NPE in some WebSocketProcol implementation
        // We could potentially synchronize on webSocket but since it is a rare case, it is better to not synchronize.
        // synchronized (webSocket) {

        closeCode = closeCode(closeCode);
        notifyListener(webSocket, new WebSocketEventListener.WebSocketEvent(closeCode, CLOSE, webSocket));
        AtmosphereResourceImpl resource = (AtmosphereResourceImpl) webSocket.resource();

        if (resource == null) {
            logger.warn("Unable to retrieve AtmosphereResource for {}", webSocket);
        } else {
            AtmosphereRequest r = resource.getRequest(false);
            AtmosphereResponse s = resource.getResponse(false);
            try {
                webSocketProtocol.onClose(webSocket);

                if (resource != null && resource.isInScope()) {

                    if (webSocketHandler != null) {
                        webSocketHandler.onClose(webSocket);
                    }

                    AsynchronousProcessor.AsynchronousProcessorHook h = (AsynchronousProcessor.AsynchronousProcessorHook)
                            r.getAttribute(ASYNCHRONOUS_HOOK);
                    if (!resource.isCancelled() && h != null) {
                        if (closeCode == 1005) {
                            h.closed();
                        } else {
                            h.timedOut();
                        }

                        resource.setIsInScope(false);
                        try {
                            resource.cancel();
                        } catch (IOException e) {
                            logger.trace("", e);
                        }
                    }
                    AtmosphereResourceImpl.class.cast(resource)._destroy();
                }

            } finally {
                if (r != null) {
                    r.destroy(true);
                }

                if (s != null) {
                    s.destroy(true);
                }

                if (webSocket != null) {
                    webSocket.resource(null);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
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

    private int closeCode(int closeCode) {
        // Tomcat and Jetty differ, same with browser
        if (closeCode == 1000 && framework.getAsyncSupport().getContainerName().contains("Tomcat")) {
            closeCode = 1005;
        }
        return closeCode;
    }


    /**
     * {@inheritDoc}
     */
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
                            if (Integer.class.getClass().isAssignableFrom(event.message().getClass())) {
                                boolean isClosedByClient = Integer.class.cast(event.message()) == 1000 ? true : false;
                                l.onDisconnect(new AtmosphereResourceEventImpl(r, !isClosedByClient, false, isClosedByClient, null));
                            }
                            WebSocketEventListener.class.cast(l).onDisconnect(event);
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
            }
        }
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
        while (read > -1) {
            bb.position(bb.position() + read);
            if (bb.remaining() == 0) {
                resizeByteBuffer();
            }
            read = is.read(bb.array(), bb.position(), bb.remaining());
        }
        bb.flip();
        try {
            invokeWebSocketProtocol(webSocket, bb.array(), 0, bb.limit());
        } finally {
            bb.clear();
        }
    }

    protected void dispatchReader(WebSocket webSocket, Reader r) throws IOException {
        int read = 0;
        while (read > -1) {
            cb.position(cb.position() + read);
            if (cb.remaining() == 0) {
                resizeCharBuffer();
            }
            read = r.read(cb.array(), cb.position(), cb.remaining());
        }
        cb.flip();
        try {
            invokeWebSocketProtocol(webSocket, cb.toString());
        } finally {
            cb.clear();
        }
    }

    private void resizeByteBuffer() throws IOException {
        int maxSize = getByteBufferMaxSize();
        if (bb.limit() >= maxSize) {
            throw new IOException("Message Buffer too small");
        }

        long newSize = bb.limit() * 2;
        if (newSize > maxSize) {
            newSize = maxSize;
        }

        // Cast is safe. newSize < maxSize and maxSize is an int
        ByteBuffer newBuffer = ByteBuffer.allocate((int) newSize);
        bb.rewind();
        newBuffer.put(bb);
        bb = newBuffer;
    }

    private void resizeCharBuffer() throws IOException {
        int maxSize = getCharBufferMaxSize();
        if (cb.limit() >= maxSize) {
            throw new IOException("Message Buffer too small");
        }

        long newSize = cb.limit() * 2;
        if (newSize > maxSize) {
            newSize = maxSize;
        }

        // Cast is safe. newSize < maxSize and maxSize is an int
        CharBuffer newBuffer = CharBuffer.allocate((int) newSize);
        cb.rewind();
        newBuffer.put(cb);
        cb = newBuffer;
    }

    /**
     * Obtain the current maximum size (in bytes) of the buffer used for binary
     * messages.
     */
    public final int getByteBufferMaxSize() {
        return byteBufferMaxSize;
    }

    /**
     * Set the maximum size (in bytes) of the buffer used for binary messages.
     */
    public final void setByteBufferMaxSize(int byteBufferMaxSize) {
        this.byteBufferMaxSize = byteBufferMaxSize;
    }

    /**
     * Obtain the current maximum size (in characters) of the buffer used for
     * binary messages.
     */
    public final int getCharBufferMaxSize() {
        return charBufferMaxSize;
    }

    /**
     * Set the maximum size (in characters) of the buffer used for textual
     * messages.
     */
    public final void setCharBufferMaxSize(int charBufferMaxSize) {
        this.charBufferMaxSize = charBufferMaxSize;
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
}
