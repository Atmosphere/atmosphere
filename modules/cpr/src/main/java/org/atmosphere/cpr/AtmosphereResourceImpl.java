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
package org.atmosphere.cpr;

import org.atmosphere.interceptor.AllowInterceptor;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SESSION_CREATE;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.AtmosphereResource.TRANSPORT.UNDEFINED;
import static org.atmosphere.cpr.Broadcaster.ROOT_MASTER;
import static org.atmosphere.cpr.HeaderConfig.LONG_POLLING_TRANSPORT;
import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;
import static org.atmosphere.websocket.WebSocketEventListener.WebSocketEvent.TYPE.CLOSE;

/**
 * {@link AtmosphereResource} implementation for supporting {@link AtmosphereRequest}
 * and {@link AtmosphereResponse}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceImpl implements AtmosphereResource {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereResourceImpl.class);

    public static final String PRE_SUSPEND = AtmosphereResourceImpl.class.getName() + ".preSuspend";
    public static final String SKIP_BROADCASTER_CREATION = AtmosphereResourceImpl.class.getName() + ".skipBroadcasterCreation";
    public static final String METEOR = Meteor.class.getName();

    private AtmosphereRequest req;
    private AtmosphereResponse response;
    private final Action action = new Action();
    protected final List<Broadcaster> broadcasters = new CopyOnWriteArrayList<Broadcaster>();
    protected Broadcaster broadcaster;
    private AtmosphereConfig config;
    protected AsyncSupport asyncSupport;
    private Serializer serializer;
    private final AtomicBoolean isInScope = new AtomicBoolean(true);
    private AtmosphereResourceEventImpl event;
    private final AtomicBoolean isResumed = new AtomicBoolean();
    private final AtomicBoolean isCancelled = new AtomicBoolean();
    private final AtomicBoolean resumeOnBroadcast = new AtomicBoolean();
    private Object writeOnTimeout;
    private boolean disableSuspend;
    private final AtomicBoolean disconnected = new AtomicBoolean();

    private final ConcurrentLinkedQueue<AtmosphereResourceEventListener> listeners =
            new ConcurrentLinkedQueue<AtmosphereResourceEventListener>();

    private final AtomicBoolean isSuspendEvent = new AtomicBoolean();
    private AtmosphereHandler atmosphereHandler;
    private String uuid;
    protected HttpSession session;
    private boolean disableSuspendEvent;
    private TRANSPORT transport;
    private boolean forceBinaryWrite;
    private final AtomicBoolean suspended = new AtomicBoolean();
    private WebSocket webSocket;
    private final AtomicBoolean inClosingPhase = new AtomicBoolean();
    private boolean closeOnCancel;
    private final AtomicBoolean isPendingClose = new AtomicBoolean();

    public AtmosphereResourceImpl() {
    }

    @Deprecated
    public AtmosphereResourceImpl(AtmosphereConfig config, Broadcaster broadcaster,
                                  AtmosphereRequest req, AtmosphereResponse response,
                                  AsyncSupport asyncSupport, AtmosphereHandler atmosphereHandler) {
        initialize(config, broadcaster, req, response, asyncSupport, atmosphereHandler);
    }

    /**
     * Initialize an {@link AtmosphereResource}.
     *
     * @param config            The {@link org.atmosphere.cpr.AtmosphereConfig}
     * @param broadcaster       The {@link org.atmosphere.cpr.Broadcaster}.
     * @param req               The {@link AtmosphereRequest}
     * @param response          The {@link AtmosphereResource}
     * @param asyncSupport      The {@link AsyncSupport}
     * @param atmosphereHandler The {@link AtmosphereHandler}
     * @return this
     */
    @Override
    public AtmosphereResource initialize(AtmosphereConfig config, Broadcaster broadcaster,
                                         AtmosphereRequest req, AtmosphereResponse response,
                                         AsyncSupport asyncSupport, AtmosphereHandler atmosphereHandler) {
        this.req = req;
        this.response = response;
        this.config = config;
        this.asyncSupport = asyncSupport;
        this.atmosphereHandler = atmosphereHandler;
        this.event = new AtmosphereResourceEventImpl(this);

        this.broadcaster = broadcaster;
        uniqueBroadcaster(broadcaster);

        String s = (String) req.getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
        if (s == null) {
            s = response.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
            if (s == null && req != null) {
                String tmp = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
                s = tmp != null && !tmp.equalsIgnoreCase("0") ? tmp : null;
            }
        }
        uuid = s == null ? config.uuidProvider().generateUuid() : s;

        if (config.isSupportSession()) {
            // Keep a reference to an HttpSession in case the associated request get recycled by the underlying container.
            try {
                session = req.getSession(config.getInitParameter(PROPERTY_SESSION_CREATE, true));
            } catch (NullPointerException ex) {
                // http://java.net/jira/browse/GLASSFISH-18856
                logger.trace("http://java.net/jira/browse/GLASSFISH-18856", ex);
            }
        }
        transport = configureTransport();
        closeOnCancel = config.getInitParameter(ApplicationConfig.CLOSE_STREAM_ON_CANCEL, false);
        return this;
    }


    protected void register() {
        if (!Utils.pollableTransport(transport()) && !Utils.webSocketMessage(this)) {
            config.resourcesFactory().registerUuidForFindCandidate(this);
        }
    }

    private TRANSPORT configureTransport() {
        if (req == null) return UNDEFINED;

        String s = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        if (s == null) return UNDEFINED;

        if (s.equals(UNDEFINED.name()) && Utils.rawWebSocket(req)) {
            return TRANSPORT.WEBSOCKET;
        }

        s = s.replace("-", "_").toUpperCase();
        if (TRANSPORT.POLLING.name().equals(s)) {
            return TRANSPORT.POLLING;
        } else if (TRANSPORT.LONG_POLLING.name().equals(s)) {
            return TRANSPORT.LONG_POLLING;
        } else if (TRANSPORT.STREAMING.name().equals(s)) {
            return TRANSPORT.STREAMING;
        } else if (TRANSPORT.JSONP.name().equals(s)) {
            return TRANSPORT.JSONP;
        } else if (TRANSPORT.WEBSOCKET.name().equals(s)) {
            return TRANSPORT.WEBSOCKET;
        } else if (TRANSPORT.SSE.name().equals(s)) {
            return TRANSPORT.SSE;
        } else if (TRANSPORT.AJAX.name().equals(s)) {
            return TRANSPORT.AJAX;
        } else if (TRANSPORT.CLOSE.name().equals(s)) {
            return TRANSPORT.CLOSE;
        } else {
            return UNDEFINED;
        }
    }

    @Override
    public AtmosphereResourceEventImpl getAtmosphereResourceEvent() {
        return event;
    }

    @Override
    public AtmosphereHandler getAtmosphereHandler() {
        return atmosphereHandler;
    }

    @Override
    public AtmosphereResource writeOnTimeout(Object o) {
        writeOnTimeout = o;
        return this;
    }

    @Override
    public Object writeOnTimeout() {
        return writeOnTimeout;
    }

    @Override
    public String uuid() {
        return uuid;
    }

    @Override
    public TRANSPORT transport() {
        return transport;
    }

    /**
     * Manually set the {@link TRANSPORT}
     *
     * @param transport set the {@link TRANSPORT}
     * @return
     */
    public AtmosphereResourceImpl transport(TRANSPORT transport) {
        this.transport = transport;
        return this;
    }

    @Override
    public AtmosphereResource resumeOnBroadcast(boolean resumeOnBroadcast) {
        this.resumeOnBroadcast.set(resumeOnBroadcast);
        // For legacy reason
        req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, resumeOnBroadcast);
        return this;
    }

    @Override
    public boolean isSuspended() {
        return suspended.get();
    }

    @Override
    public boolean resumeOnBroadcast() {
        boolean rob = resumeOnBroadcast.get();
        if (!rob) {
            Boolean b = (Boolean) req.getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
            return b == null ? false : b;
        }
        return rob;
    }

    @Override
    public AtmosphereResource resume() {

        if (!isSuspended()) {
            logger.trace("AtmosphereResource {} not suspended {}, cannot resume it.", uuid(), action);
            return this;
        }

        try {
            if (!isResumed.getAndSet(true) && isInScope.get()) {
                suspended.set(false);
                logger.trace("AtmosphereResource {} is resuming", uuid());

                action.type(Action.TYPE.RESUME);

                boolean notify = true;
                for (Broadcaster b : broadcasters) {
                    // We need it as Jetty doesn't support timeout
                    if (!b.isDestroyed() && b instanceof DefaultBroadcaster) {
                        ((DefaultBroadcaster) b).broadcastOnResume(this);
                    }

                    if (notify) {
                        notify = false;
                        notifyListeners();
                    }

                    if (b.getScope() == Broadcaster.SCOPE.REQUEST) {
                        logger.debug("Broadcaster's scope is set to request, destroying it {}", b.getID());
                        b.destroy();
                    }
                }
                removeFromAllBroadcasters();

                try {
                    req.setAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT, Boolean.FALSE);
                } catch (Exception ex) {
                    logger.debug("Resume exception: Cannot resume an already resumed/cancelled request", ex);
                }

                if (req.getAttribute(PRE_SUSPEND) == null) {
                    asyncSupport.action(this);
                }
            } else {
                logger.trace("Already resumed {}", this);
                return this;
            }
        } catch (Throwable t) {
            logger.trace("Wasn't able to resume a connection {}", this, t);
        } finally {
            unregister();
            Utils.destroyMeteor(req);
        }
        listeners.clear();
        return this;
    }

    @Override
    public AtmosphereResource suspend() {
        return suspend(-1);
    }

    @Override
    public AtmosphereResource suspend(long timeout, TimeUnit timeunit) {
        long timeoutms = -1;
        if (timeunit != null) {
            timeoutms = TimeUnit.MILLISECONDS.convert(timeout, timeunit);
        }

        return suspend(timeoutms);
    }

    @Override
    public AtmosphereResource suspend(long timeout) {

        if (event.isSuspended() || disableSuspend) return this;

        if (config.isSupportSession()
                && req.getSession(false) != null
                && req.getSession().getMaxInactiveInterval() >= 0
                && req.getSession().getMaxInactiveInterval() * 1000 < timeout) {
            throw new IllegalStateException("Cannot suspend a " +
                    "response longer than the session timeout. Increase the value of session-timeout in web.xml");
        }

        if (Utils.resumableTransport(transport())) {
            resumeOnBroadcast.set(true);
        }

        onPreSuspend(event);

        // Recheck based on preSuspend
        if (event.isSuspended() || disableSuspend) return this;

        if (!event.isResumedOnTimeout()) {
            suspended.set(true);

            Enumeration<String> connection = req.getHeaders("Connection");
            if (connection == null) {
                connection = req.getHeaders("connection");
            }

            if (connection != null && connection.hasMoreElements()) {
                String[] e = connection.nextElement().toString().split(",");
                for (String upgrade : e) {
                    if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                        if (!asyncSupport.supportWebSocket()) {
                            response.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
                        } else {
                            req.setAttribute(FrameworkConfig.TRANSPORT_IN_USE, HeaderConfig.WEBSOCKET_TRANSPORT);
                        }
                    }
                }
            }

            if (req.getHeader(X_ATMOSPHERE_TRANSPORT) == null || transport().equals(UNDEFINED)) {
                req.setAttribute(FrameworkConfig.TRANSPORT_IN_USE, LONG_POLLING_TRANSPORT);
            }

            req.setAttribute(PRE_SUSPEND, "true");
            action.type(Action.TYPE.SUSPEND);
            action.timeout(timeout);

            // TODO: We can possibly optimize that call by avoiding creating a Broadcaster if we are sure the Broadcaster
            // is unique.
            boolean isJersey = req.getAttribute(FrameworkConfig.CONTAINER_RESPONSE) != null;

            boolean skipCreation = false;
            if (req.getAttribute(SKIP_BROADCASTER_CREATION) != null) {
                skipCreation = true;
            }

            Broadcaster broadcaster = getBroadcaster();

            // Null means SCOPE=REQUEST set by a Meteor
            if (!skipCreation && (broadcaster == null || broadcaster.getScope() == Broadcaster.SCOPE.REQUEST) && !isJersey) {
                String id = broadcaster != null ? broadcaster.getID() : ROOT_MASTER;
                Class<? extends Broadcaster> clazz = broadcaster != null ? broadcaster.getClass() : DefaultBroadcaster.class;

                broadcaster = config.getBroadcasterFactory().lookup(clazz, id, false);
                if (broadcaster == null || !broadcaster.getAtmosphereResources().isEmpty()) {
                    broadcaster = config.getBroadcasterFactory().lookup(clazz, id + "/" + UUID.randomUUID(), true);
                }
            }

            broadcaster.addAtmosphereResource(this);
            if (req.getAttribute(DefaultBroadcaster.CACHED) != null && transport() != null && Utils.resumableTransport(transport())) {
                action.type(Action.TYPE.CONTINUE);
                // Do nothing because we have found cached message which was written already, and the handler resumed.
                logger.debug("Cached message found, not suspending {}", uuid());
                return this;
            }
            req.removeAttribute(PRE_SUSPEND);
            register();
            notifyListeners();
        }
        return this;
    }

    public AtmosphereRequest getRequest(boolean enforceScope) {
        if (enforceScope && !isInScope.get()) {
            throw new IllegalStateException("Request object no longer" + " valid. This object has been cancelled");
        }
        return req;
    }

    public AtmosphereResponse getResponse(boolean enforceScope) {
        if (enforceScope && !isInScope.get()) {
            throw new IllegalStateException("Response object no longer valid. This object has been cancelled");
        }
        return response;
    }

    @Override
    public AtmosphereRequest getRequest() {
        return getRequest(true);
    }

    @Override
    public AtmosphereResponse getResponse() {
        return getResponse(true);
    }

    @Override
    public Broadcaster getBroadcaster() {
        return getBroadcaster(true);
    }

    @Override
    public List<Broadcaster> broadcasters() {
        return Collections.unmodifiableList(broadcasters);
    }

    protected Broadcaster getBroadcaster(boolean autoCreate) {
        if (broadcaster == null) {
            throw new IllegalStateException("No Broadcaster associated with this AtmosphereResource.");
        }

        String s = config.getInitParameter(ApplicationConfig.RECOVER_DEAD_BROADCASTER);
        if (s != null) {
            autoCreate = Boolean.parseBoolean(s);
        }

        if (autoCreate && broadcaster.isDestroyed() && config.getBroadcasterFactory() != null) {
            logger.debug("Broadcaster {} has been destroyed and cannot be re-used. Recreating a new one with the same name. You can turn off this" +
                    " mechanism by adding, in web.xml, {} set to false", broadcaster.getID(), ApplicationConfig.RECOVER_DEAD_BROADCASTER);

            Broadcaster.SCOPE scope = broadcaster.getScope();
            synchronized (this) {
                String id = scope != Broadcaster.SCOPE.REQUEST ? broadcaster.getID() : broadcaster.getID() + ".recovered"
                        + config.uuidProvider().generateUuid();

                // Another Thread may have added the Broadcaster.
                broadcaster = config.getBroadcasterFactory().lookup(id, true);
                broadcaster.setScope(scope);
                broadcaster.addAtmosphereResource(this);
            }
        }
        return broadcaster;
    }

    @Override
    public AtmosphereResource setBroadcaster(Broadcaster broadcaster) {
        this.broadcaster = broadcaster;
        return uniqueBroadcaster(broadcaster);
    }

    @Override
    public AtmosphereResource addBroadcaster(Broadcaster broadcaster) {
        return uniqueBroadcaster(broadcaster);
    }

    @Override
    public AtmosphereResource removeBroadcaster(Broadcaster broadcaster) {
        broadcasters.remove(broadcaster);
        return this;
    }

    protected AtmosphereResource uniqueBroadcaster(Broadcaster newB) {
        if (newB == null) {
            return this;
        }
        // TODO: performance bottleneck
        for (Broadcaster b: broadcasters) {
            if (b.getID() != null && b.getID().equalsIgnoreCase(newB.getID())) {
                logger.trace("Duplicate Broadcaster {}", newB);
                return this;
            }
        }
        broadcasters.add(newB);
        return this;
    }


    @Override
    public AtmosphereConfig getAtmosphereConfig() {
        return config;
    }

    /**
     * Return the {@link Action} which represent the state of the response, e.g. suspended, resumed or timed out.
     *
     * @return the {@link Action}
     */
    public Action action() {
        return action;
    }

    /**
     * Completely reset the instance to its initial state.
     */
    public void reset() {
        isResumed.set(false);
        isCancelled.set(false);
        isPendingClose.set(false);
        isInScope.set(true);
        isSuspendEvent.set(false);
        listeners.clear();
        action.type(Action.TYPE.CREATED);
    }

    /**
     * Protect the object from being used after it got cancelled.
     *
     * @param isInScope
     */
    public void setIsInScope(boolean isInScope) {
        this.isInScope.set(isInScope);
    }

    /**
     * Check if the {@link AtmosphereRequest} still is valid.
     *
     * @return true if the {@link AtmosphereRequest} still is valid
     */
    public boolean isInScope() {
        return isInScope.get();
    }

    /**
     * Set the {@link Serializer} used to write broadcasted objects.
     *
     * @param s
     */
    @Override
    public AtmosphereResource setSerializer(Serializer s) {
        serializer = s;
        return this;
    }

    @Override
    public boolean isResumed() {
        return isResumed.get();
    }

    @Override
    public boolean isCancelled() {
        return isCancelled.get();
    }

    @Override
    public AtmosphereResource write(String s) {
        response.write(s);
        if (resumeOnBroadcast()) {
            resume();
        }
        return this;
    }

    @Override
    public AtmosphereResource write(byte[] o) {
        response.write(o);
        if (resumeOnBroadcast()) {
            resume();
        }
        return this;
    }

    @Override
    public Serializer getSerializer() {
        return serializer;
    }

    /**
     * Add a {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEventListener
     */
    @Override
    public AtmosphereResource addEventListener(AtmosphereResourceEventListener e) {
        if (listeners.contains(e)) return this;
        listeners.add(e);
        return this;
    }

    @Override
    public AtmosphereResource removeEventListener(AtmosphereResourceEventListener e) {
        listeners.remove(e);
        return this;
    }

    @Override
    public AtmosphereResource removeEventListeners() {
        listeners.clear();
        return this;
    }

    @Override
    public AtmosphereResource notifyListeners() {
        notifyListeners(event);
        return this;
    }

    @Override
    public AtmosphereResource notifyListeners(AtmosphereResourceEvent event) {
        if (listeners.isEmpty() && config.framework().atmosphereResourceListeners().isEmpty()) {
            logger.trace("No listener with {}", uuid);
            return this;
        }
        logger.trace("Invoking listener {} for {}", listeners, uuid);

        Action oldAction = action;
        try {
            if (HeartbeatAtmosphereResourceEvent.class.isAssignableFrom(event.getClass())) {
                onHeartbeat(event);
            } else if (event.isClosedByApplication()) {
                onClose(event);
            } else if (event.isCancelled() || event.isClosedByClient()) {
                if (!disconnected.getAndSet(true)) {
                    onDisconnect(event);
                } else {
                    logger.trace("Skipping notification, already disconnected {}", event.getResource().uuid());
                }
            } else if (event.isResuming() || event.isResumedOnTimeout()) {
                onResume(event);
            } else if (!isSuspendEvent.getAndSet(true) && event.isSuspended()) {
                onSuspend(event);
            } else if (event.throwable() != null) {
                onThrowable(event);
            } else {
                onBroadcast(event);
            }

            if (oldAction.type() != action.type()) {
                action().type(Action.TYPE.CREATED);
            }
        } catch (Throwable t) {
            AtmosphereResourceEventImpl.class.cast(event).setThrowable(t);
            if (event.isSuspended()) {
                logger.warn("Exception during suspend() operation {}", t.toString());
                logger.debug("", t);
                removeFromAllBroadcasters();
            } else {
                logger.debug("Listener error {}", t);
            }

            try {
                onThrowable(event);
            } catch (Throwable t2) {
                logger.warn("Listener error {}", t2);
            }
        }
        return this;
    }

    @Override
    public AtmosphereResource removeFromAllBroadcasters() {
        for (Broadcaster b : broadcasters) {
            try {
                b.removeAtmosphereResource(this);
            } catch (Exception ex) {
                logger.trace("", ex);
            }
        }
        return this;
    }

    /**
     * Notify {@link AtmosphereResourceEventListener} thah an unexpected exception occured.
     *
     * @pram a {@link Throwable}
     */
    public void onThrowable(Throwable t) {
        onThrowable(new AtmosphereResourceEventImpl(this, false, false, t));
    }

    void onThrowable(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onThrowable(e);
        }
    }

    /**
     * <p>
     * Notifies to all listeners that a heartbeat has been sent.
     * </p>
     *
     * @param e the event
     */
    void onHeartbeat(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onHeartbeat(e);
        }
    }

    void onSuspend(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            if (disableSuspendEvent) {
                if (!AllowInterceptor.class.isAssignableFrom(r.getClass())) {
                    continue;
                }
            }
            r.onSuspend(e);
        }
        if (e.getResource() != null) {
            config.framework().notifySuspended(e.getResource().uuid());
        }
    }

    void onPreSuspend(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            if (disableSuspendEvent) {
                if (!AllowInterceptor.class.isAssignableFrom(r.getClass())) {
                    continue;
                }
            }
            r.onPreSuspend(e);
        }
    }

    void onResume(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onResume(e);
        }
    }

    void onDisconnect(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onDisconnect(e);
            if (transport.equals(TRANSPORT.WEBSOCKET) && WebSocketEventListener.class.isAssignableFrom(r.getClass())) {
                WebSocketEventListener.class.cast(r).onDisconnect(new WebSocketEventListener.WebSocketEvent(1005, CLOSE, webSocket));
            }
        }

        if (e.getResource() != null) {
            config.framework().notifyDestroyed(e.getResource().uuid());
        }
    }

    void onBroadcast(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onBroadcast(e);
        }
    }

    void onClose(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onClose(e);
            if (transport.equals(TRANSPORT.WEBSOCKET) && WebSocketEventListener.class.isAssignableFrom(r.getClass())) {
                WebSocketEventListener.class.cast(r).onClose(new WebSocketEventListener.WebSocketEvent(1005, CLOSE, webSocket));
            }
        }
    }

    public ConcurrentLinkedQueue<AtmosphereResourceEventListener> atmosphereResourceEventListener() {
        return listeners;
    }

    public AtmosphereResourceImpl atmosphereHandler(AtmosphereHandler atmosphereHandler) {
        this.atmosphereHandler = atmosphereHandler;
        return this;
    }

    public void cancel() throws IOException {
        try {
            if (!isCancelled.getAndSet(true)) {
                suspended.set(false);
                logger.trace("Cancelling {}", uuid);

                if (config.getBroadcasterFactory() != null) {
                    removeFromAllBroadcasters();
                    if (transport.equals(TRANSPORT.WEBSOCKET)) {
                        String parentUUID = (String) req.getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
                        AtmosphereResource p = config.resourcesFactory().find(parentUUID);
                        if (p != null) {
                            p.removeFromAllBroadcasters();
                        }
                    }
                }

                asyncSupport.complete(this);

                try {
                    Object o = req.getAttribute(AtmosphereResourceImpl.METEOR);
                    if (o != null && Meteor.class.isAssignableFrom(o.getClass())) {
                        Meteor.class.cast(o).destroy();
                    }
                } catch (Exception ex) {
                    logger.trace("Meteor exception {}", ex);
                }

                SessionTimeoutSupport.restoreTimeout(req);
                action.type(Action.TYPE.CANCELLED);
                if (asyncSupport != null) asyncSupport.action(this);
                // We must close the underlying WebSocket as well.
                if (AtmosphereResponseImpl.class.isAssignableFrom(response.getClass())) {
                    if (closeOnCancel) {
                        AtmosphereResponseImpl.class.cast(response).close();
                    }
                    AtmosphereResponseImpl.class.cast(response).destroy();
                }

                if (AtmosphereRequestImpl.class.isAssignableFrom(req.getClass())) {
                    if (closeOnCancel) {
                        AtmosphereRequestImpl.class.cast(req).destroy();
                    }
                }
                event.destroy();
            }
        } finally {
            unregister();
        }
    }

    private void unregister() {
        config.resourcesFactory().remove(uuid);
    }

    public void _destroy() {
        try {
            if (!isCancelled.get()) {
                removeFromAllBroadcasters();
            }
            broadcasters.clear();

            unregister();
            removeEventListeners();
        } catch (Throwable t) {
            logger.trace("destroyResource", t);
        } finally {
            unregister();
            webSocket = null;
        }
    }

    @Override
    public String toString() {
        try {
            return "AtmosphereResource{" +
                    "\n\t uuid=" + uuid +
                    ",\n\t transport=" + transport() +
                    ",\n\t isInScope=" + isInScope +
                    ",\n\t isResumed=" + isResumed() +
                    ",\n\t isCancelled=" + isCancelled() +
                    ",\n\t isSuspended=" + isSuspended() +
                    ",\n\t broadcasters=" + getBroadcaster().getID() +
                    ",\n\t isClosedByClient=" + (event != null ? event.isClosedByClient() : false) +
                    ",\n\t isClosedByApplication=" + (event != null ? event.isClosedByApplication() : false) +
                    ",\n\t action=" + action +
                    '}';
        } catch (NullPointerException ex) {
            // Prevent logger
            return "AtmosphereResourceImpl{" + uuid + "}";
        }
    }

    public AtmosphereResourceImpl disableSuspend(boolean disableSuspend) {
        this.disableSuspend = disableSuspend;
        return this;
    }

    @Override
    public HttpSession session(boolean create) {
        if (config.isSupportSession() && session == null) {
            // http://java.net/jira/browse/GLASSFISH-18856
            session = req.getSession(create);
        }
        return session;
    }

    @Override
    public void close() throws IOException {
        event.setCloseByApplication(true);
        notifyListeners();
        cancel();
        if (webSocket != null && webSocket.isOpen()) {
            webSocket.close();
        }
    }

    public void dirtyClose() {
        try {
            event.setCancelled(true);
            notifyListeners();
            cancel();
            if (webSocket != null) {
                webSocket.close();
            }
        } catch (IOException ex) {
            logger.trace("", ex);
        }
    }

    @Override
    public AtmosphereResource forceBinaryWrite(boolean forceBinaryWrite) {
        this.forceBinaryWrite = forceBinaryWrite;
        return this;
    }

    @Override
    public boolean forceBinaryWrite() {
        return forceBinaryWrite;
    }

    @Override
    public HttpSession session() {
        return session(true);
    }

    public AtmosphereResourceImpl session(HttpSession session) {
        this.session = session;
        return this;
    }

    public AtmosphereResourceImpl cloneState(AtmosphereResource r) {
        for (AtmosphereResourceEventListener l : AtmosphereResourceImpl.class.cast(r).atmosphereResourceEventListener()) {
            addEventListener(l);
        }
        AtmosphereResourceImpl.class.cast(r).session(r.session());
        boolean isFirst = true;
        for (Broadcaster b : broadcasters) {
            if (isFirst) {
                isFirst = false;
                setBroadcaster(b);
            } else {
                addBroadcaster(b);
            }
        }
        atmosphereHandler(r.getAtmosphereHandler());
        return this;
    }

    public ConcurrentLinkedQueue<AtmosphereResourceEventListener> listeners() {
        return listeners;
    }

    /**
     * Disable invocation of {@link AtmosphereResourceEventListener#onSuspend(AtmosphereResourceEvent)} and
     * {@link AtmosphereResourceEventListener#onPreSuspend(AtmosphereResourceEvent)}. You normally disable those events
     * after the first onSuspend has been called so all transports behave the same way.
     * <br/>
     * {@link AtmosphereResourceEventListener} marked with {@link org.atmosphere.interceptor.AllowInterceptor} will not
     * be affected by this property.
     *
     * @param disableSuspendEvent
     * @return this
     */
    public AtmosphereResourceImpl disableSuspendEvent(boolean disableSuspendEvent) {
        this.disableSuspendEvent = disableSuspendEvent;
        return this;
    }

    /**
     * Return true if {@link AtmosphereResourceEventListener#onSuspend(AtmosphereResourceEvent)} and
     * {@link AtmosphereResourceEventListener#onPreSuspend(AtmosphereResourceEvent)} events are disabled.
     *
     * @return true if disabled
     */
    public boolean disableSuspendEvent() {
        return disableSuspendEvent;
    }

    public WebSocket webSocket() {
        return webSocket;
    }

    public AtmosphereResourceImpl webSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AtmosphereResourceImpl that = (AtmosphereResourceImpl) o;

        if (uuid != null ? !uuid.equals(that.uuid) : that.uuid != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return uuid != null ? uuid.hashCode() : 0;
    }

    public boolean getAndSetInClosingPhase() {
        return inClosingPhase.getAndSet(true);
    }

    /**
     * @return
     */
    public boolean isPendingClose () {
        return isPendingClose.get();
    }
    
    public boolean getAndSetPendingClose() {
        return isPendingClose.getAndSet(true);
    }
}
