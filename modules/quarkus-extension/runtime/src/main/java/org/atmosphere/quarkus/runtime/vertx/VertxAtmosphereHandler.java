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
package org.atmosphere.quarkus.runtime.vertx;

import io.quarkus.arc.Arc;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serves Atmosphere from a Quarkus Vert.x route, without a servlet container.
 *
 * <p>HTTP: the body is collected on the event loop (bounded by
 * {@code maxBodyBytes}, 413 beyond it), then the exchange moves to a virtual
 * thread which activates the CDI request context, resolves the caller's
 * {@link SecurityIdentity} (HTTP permissions have already run on the router),
 * builds the Atmosphere request/response and calls
 * {@link AtmosphereFramework#doCometSupport}. With
 * {@link VertxBlockingAsyncSupport} a suspended request parks that virtual thread
 * until it is resumed, times out, or the client disconnects — which cancels it.</p>
 *
 * <p>WebSocket: the upgrade happens on the router ({@code toWebSocket()}), and
 * {@link VertxWebSocketConnection} feeds whole messages to the
 * {@link WebSocketProcessor} in order.</p>
 */
public final class VertxAtmosphereHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(VertxAtmosphereHandler.class);

    private final AtmosphereFramework framework;
    private final WebSocketProcessor processor;
    private final ExecutorService executor;
    private final ThreadFactory threads;
    private final String servletPath;
    private final long maxBodyBytes;
    private final long drainTimeoutMs;

    /**
     * @param servletPath   the mapping prefix without {@code /*}, e.g. {@code /atmosphere}
     *                      ({@code ""} for a root mapping)
     * @param maxBodyBytes  largest request body accepted, in bytes
     * @param drainTimeoutMs how long a writer waits for a full write queue to drain
     */
    public VertxAtmosphereHandler(AtmosphereFramework framework, WebSocketProcessor processor,
                                  ExecutorService executor, ThreadFactory threads,
                                  String servletPath, long maxBodyBytes, long drainTimeoutMs) {
        this.framework = framework;
        this.processor = processor;
        this.executor = executor;
        this.threads = threads;
        this.servletPath = servletPath;
        this.maxBodyBytes = maxBodyBytes;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    @Override
    public void handle(RoutingContext rc) {
        var request = rc.request();
        if (isWebSocketUpgrade(request)) {
            if (!framework.webSocketEnabled()) {
                // quarkus.atmosphere.websocket-support=false: refuse the upgrade so
                // the client falls back to an HTTP transport.
                rc.response().setStatusCode(501).end();
                return;
            }
            upgrade(rc);
            return;
        }
        // A body route earlier on the router may already have consumed the body.
        if (rc.body() != null && rc.body().available()) {
            var consumed = rc.body().buffer();
            if (consumed != null && consumed.length() > maxBodyBytes) {
                rc.response().setStatusCode(413).end();
                return;
            }
            dispatch(rc, consumed == null ? new byte[0] : consumed.getBytes());
            return;
        }
        var body = Buffer.buffer();
        var tooLarge = new boolean[1];
        request.handler(chunk -> {
            if (tooLarge[0]) {
                return;
            }
            if (body.length() + chunk.length() > maxBodyBytes) {
                tooLarge[0] = true;
                rc.response().setStatusCode(413).end();
                return;
            }
            body.appendBuffer(chunk);
        });
        request.exceptionHandler(t -> logger.trace("Request body read failed", t));
        request.endHandler(v -> {
            if (tooLarge[0] || rc.response().ended()) {
                return;
            }
            dispatch(rc, body.getBytes());
        });
        if (request.isEnded()) {
            // Fully received before this route ran: endHandler will not fire again.
            dispatch(rc, body.getBytes());
        } else {
            request.resume();
        }
    }

    private void dispatch(RoutingContext rc, byte[] body) {
        try {
            executor.execute(() -> service(rc, body));
        } catch (RejectedExecutionException e) {
            // Shutting down: refuse rather than drop the exchange silently.
            logger.debug("Atmosphere Vert.x executor rejected a request", e);
            if (!rc.response().ended()) {
                rc.response().setStatusCode(503).end();
            }
        }
    }

    private void service(RoutingContext rc, byte[] body) {
        var requestContext = Arc.container().requestContext();
        requestContext.activate();
        VertxServletResponse nativeResponse = null;
        try {
            bindCurrentRequest(rc);
            var identity = resolveIdentity(rc);
            AtmosphereRequest request = atmosphereRequest(rc, identity).body(body).build();
            nativeResponse = new VertxServletResponse(rc.response(), drainTimeoutMs);
            // Wrapped exactly like a servlet response, so the interceptor writer
            // (SSE, padding, framing) is installed on top the same way.
            AtmosphereResponse response = AtmosphereResponseImpl.wrap(nativeResponse);
            cancelOnDisconnect(rc, request, response, nativeResponse);
            framework.doCometSupport(request, response);
            nativeResponse.finish();
        } catch (Exception e) {
            logger.warn("Atmosphere request {} {} failed", rc.request().method(), rc.request().path(), e);
            if (nativeResponse != null) {
                nativeResponse.fail();
            } else if (!rc.response().ended()) {
                rc.response().setStatusCode(500).end();
            }
        } finally {
            requestContext.terminate();
        }
    }

    /**
     * A client that goes away while its request is suspended must release the
     * parked virtual thread and fire the resource's disconnect listeners.
     */
    private void cancelOnDisconnect(RoutingContext rc, AtmosphereRequest request,
                                    AtmosphereResponse response, VertxServletResponse nativeResponse) {
        var once = new AtomicBoolean();
        Runnable cancel = () -> {
            if (!once.compareAndSet(false, true)) {
                return;
            }
            if (framework.getAsyncSupport() instanceof BlockingIOCometSupport blocking) {
                try {
                    blocking.cancelled(request, response);
                } catch (Exception e) {
                    logger.debug("Cancelling a disconnected request failed", e);
                }
            }
        };
        rc.response().closeHandler(v -> {
            if (!nativeResponse.completedByServer()) {
                threads.newThread(cancel).start();
            }
        });
        if (rc.response().closed() && !nativeResponse.completedByServer()) {
            threads.newThread(cancel).start();
        }
    }

    private void upgrade(RoutingContext rc) {
        // The upgrade request's identity and headers are captured before the
        // handshake; the socket then lives outside any HTTP request context.
        rc.request().toWebSocket().onSuccess(socket -> threads.newThread(() -> {
            try {
                var identity = resolveIdentity(rc);
                var request = atmosphereRequest(rc, identity).build();
                var webSocket = new VertxWebSocket(framework.getAtmosphereConfig(), socket, drainTimeoutMs);
                new VertxWebSocketConnection(socket, webSocket, processor, request).start(threads);
            } catch (RuntimeException e) {
                logger.warn("WebSocket setup failed for {}", rc.request().path(), e);
                socket.close((short) 1011, "Setup failed");
            }
        }).start()).onFailure(t -> {
            logger.debug("WebSocket upgrade failed for {}", rc.request().path(), t);
            if (!rc.response().ended()) {
                rc.fail(400, t);
            }
        });
    }

    private AtmosphereRequestImpl.Builder atmosphereRequest(RoutingContext rc, SecurityIdentity identity) {
        var request = rc.request();
        var headers = new LinkedHashMap<String, String>();
        request.headers().forEach(h -> headers.merge(h.getKey(), h.getValue(), (a, b) -> a + ", " + b));
        var query = new LinkedHashMap<String, String[]>();
        var params = rc.queryParams();
        for (var name : params.names()) {
            query.put(name, params.getAll(name).toArray(String[]::new));
        }
        var mount = rc.mountPoint() == null ? "" : stripTrailingSlash(rc.mountPoint());
        var path = request.path();
        var withinContext = path.startsWith(mount) ? path.substring(mount.length()) : path;
        String pathInfo;
        String servlet;
        if (!servletPath.isEmpty() && withinContext.startsWith(servletPath)) {
            servlet = servletPath;
            pathInfo = withinContext.substring(servletPath.length());
        } else {
            servlet = "";
            pathInfo = withinContext;
        }
        var builder = new AtmosphereRequestImpl.Builder()
                .method(request.method().name())
                .requestURI(path)
                .requestURL(request.absoluteURI())
                .contextPath(mount)
                .servletPath(servlet)
                .pathInfo(pathInfo.isEmpty() ? null : pathInfo)
                .queryString(request.query())
                .queryStrings(query)
                .headers(headers)
                .contentType(request.getHeader(HttpHeaders.CONTENT_TYPE))
                .isSecure(request.isSSL());
        var remote = request.remoteAddress();
        if (remote != null) {
            builder.remoteAddr(remote.hostAddress()).remoteHost(remote.hostAddress()).remotePort(remote.port());
        }
        var local = request.localAddress();
        if (local != null) {
            builder.localAddr(local.hostAddress()).localName(local.hostAddress()).localPort(local.port())
                    .serverName(local.hostAddress()).serverPort(local.port());
        }
        if (identity != null && !identity.isAnonymous()) {
            Principal principal = identity.getPrincipal();
            builder.userPrincipal(principal);
        }
        return builder;
    }

    /**
     * The caller's identity as Quarkus HTTP security resolved it on the router:
     * the proactive {@link QuarkusHttpUser}, or the deferred identity when proactive
     * authentication is off. Also published to {@link CurrentIdentityAssociation}
     * so {@code @Inject SecurityIdentity} works in handlers.
     */
    @SuppressWarnings("unchecked") // DEFERRED_IDENTITY_KEY is stored as Uni<SecurityIdentity> by Quarkus
    private static SecurityIdentity resolveIdentity(RoutingContext rc) {
        SecurityIdentity identity = null;
        if (rc.user() instanceof QuarkusHttpUser user) {
            identity = user.getSecurityIdentity();
        } else if (rc.get(QuarkusHttpUser.DEFERRED_IDENTITY_KEY) instanceof Uni<?> deferred) {
            identity = ((Uni<SecurityIdentity>) deferred).await().indefinitely();
        }
        if (identity != null && Arc.container().requestContext().isActive()) {
            var association = Arc.container().instance(CurrentIdentityAssociation.class);
            if (association.isAvailable()) {
                association.get().setIdentity(identity);
            }
        }
        return identity;
    }

    private static void bindCurrentRequest(RoutingContext rc) {
        var current = Arc.container().instance(CurrentVertxRequest.class);
        if (current.isAvailable()) {
            current.get().setCurrent(rc);
        }
    }

    private static boolean isWebSocketUpgrade(HttpServerRequest request) {
        var upgrade = request.getHeader(HttpHeaders.UPGRADE);
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
