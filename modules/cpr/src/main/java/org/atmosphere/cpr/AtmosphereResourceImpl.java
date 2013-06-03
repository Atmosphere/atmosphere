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
/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
package org.atmosphere.cpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.atmosphere.cpr.HeaderConfig.CACHE_CONTROL;
import static org.atmosphere.cpr.HeaderConfig.EXPIRES;
import static org.atmosphere.cpr.HeaderConfig.PRAGMA;
import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;

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

    private final AtmosphereRequest req;
    private final AtmosphereResponse response;
    protected final Action action = new Action();
    protected Broadcaster broadcaster;
    private final AtmosphereConfig config;
    protected final AsyncSupport asyncSupport;
    private Serializer serializer;
    private boolean isInScope = true;
    private final AtmosphereResourceEventImpl event;
    private String beginCompatibleData;
    private boolean useWriter = true;
    private AtomicBoolean isResumed = new AtomicBoolean();
    private AtomicBoolean isCancelled = new AtomicBoolean();
    private AtomicBoolean resumeOnBroadcast = new AtomicBoolean();
    private Object writeOnTimeout = null;
    private boolean disableSuspend = false;
    private final AtomicBoolean disconnected = new AtomicBoolean();

    private final ConcurrentLinkedQueue<AtmosphereResourceEventListener> listeners =
            new ConcurrentLinkedQueue<AtmosphereResourceEventListener>();

    private final boolean injectCacheHeaders;
    private final boolean enableAccessControl;
    private final AtomicBoolean isSuspendEvent = new AtomicBoolean();
    private AtmosphereHandler atmosphereHandler;
    private final boolean writeHeaders;
    private String padding;
    private final String uuid;
    protected HttpSession session;

    /**
     * Create an {@link AtmosphereResource}.
     *
     * @param config            The {@link org.atmosphere.cpr.AtmosphereConfig}
     * @param broadcaster       The {@link org.atmosphere.cpr.Broadcaster}.
     * @param req               The {@link AtmosphereRequest}
     * @param response          The {@link AtmosphereResource}
     * @param asyncSupport      The {@link AsyncSupport}
     * @param atmosphereHandler The {@link AtmosphereHandler}
     */
    public AtmosphereResourceImpl(AtmosphereConfig config, Broadcaster broadcaster,
                                  AtmosphereRequest req, AtmosphereResponse response,
                                  AsyncSupport asyncSupport, AtmosphereHandler atmosphereHandler) {
        this.req = req;
        this.response = response;
        this.broadcaster = broadcaster;
        this.config = config;
        this.asyncSupport = asyncSupport;
        this.atmosphereHandler = atmosphereHandler;
        this.event = new AtmosphereResourceEventImpl(this);

        String nocache = config.getInitParameter(ApplicationConfig.NO_CACHE_HEADERS);
        injectCacheHeaders = nocache != null ? false : true;

        String ac = config.getInitParameter(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER);
        enableAccessControl = ac != null ? !Boolean.parseBoolean(ac) : true;

        String wh = config.getInitParameter(FrameworkConfig.WRITE_HEADERS);
        writeHeaders = wh != null ? Boolean.parseBoolean(wh) : true;


        req.setAttribute(ApplicationConfig.NO_CACHE_HEADERS, injectCacheHeaders);
        req.setAttribute(ApplicationConfig.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, enableAccessControl);

        padding = config.getInitParameter(ApplicationConfig.STREAMING_PADDING_MODE);
        if (padding == null) {
            padding = FrameworkConfig.WHITESPACE_PADDING;
        }
        req.setAttribute(ApplicationConfig.STREAMING_PADDING_MODE, padding);

        String s = response.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);

        if (s == null) {
            s = (String) getRequest().getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
        }

        uuid = s == null ? UUID.randomUUID().toString() : s;

        if (config.isSupportSession()) {
            //Keep a reference to an HttpSession in case the associated request get recycled by the underlying container.
            try {
                session = req.getSession(true);
            } catch (NullPointerException ex) {
                // http://java.net/jira/browse/GLASSFISH-18856
                logger.trace("http://java.net/jira/browse/GLASSFISH-18856", ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResourceEventImpl getAtmosphereResourceEvent() {
        return event;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereHandler getAtmosphereHandler() {
        return atmosphereHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource writeOnTimeout(Object o) {
        writeOnTimeout = o;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object writeOnTimeout() {
        return writeOnTimeout;
    }

    @Override
    public String uuid() {
        return uuid;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TRANSPORT transport() {
        if (req == null) return TRANSPORT.UNDEFINED;

        String s = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        if (s == null) return TRANSPORT.UNDEFINED;

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
        } else {
            return TRANSPORT.UNDEFINED;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource resumeOnBroadcast(boolean resumeOnBroadcast) {
        this.resumeOnBroadcast.set(resumeOnBroadcast);
        // For legacy reason
        req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, resumeOnBroadcast);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSuspended() {
        return event.isSuspended();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean resumeOnBroadcast() {
        boolean rob = resumeOnBroadcast.get();
        if (!rob) {
            Boolean b = (Boolean) req.getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
            return b ==  null ? false : b;
        }
        return rob;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource resume() {
        try {
            if (!isResumed.getAndSet(true) && isInScope) {
                logger.trace("AtmosphereResource {} is resuming", uuid());

                action.type(Action.TYPE.RESUME);

                // We need it as Jetty doesn't support timeout
                Broadcaster b = getBroadcaster(false);
                if (!b.isDestroyed() && b instanceof DefaultBroadcaster) {
                    ((DefaultBroadcaster) b).broadcastOnResume(this);
                }

                notifyListeners();
                try {
                    if (!b.isDestroyed()) {
                        broadcaster.removeAtmosphereResource(this);
                    }
                } catch (IllegalStateException ex) {
                    logger.warn("Unable to resume", this);
                    logger.debug(ex.getMessage(), ex);
                }

                if (b.getScope() == Broadcaster.SCOPE.REQUEST) {
                    logger.debug("Broadcaster's scope is set to request, destroying it {}", b.getID());
                    b.destroy();
                }

                // Resuming here means we need to pull away from all other Broadcaster, if they exists.
                if (config.getBroadcasterFactory() != null) {
                    config.getBroadcasterFactory().removeAllAtmosphereResource(this);
                }

                try {
                    req.setAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT, Boolean.FALSE);
                } catch (Exception ex) {
                    logger.debug("Resume exception: Cannot resume an already resumed/cancelled request", ex);
                } finally {
                    try {
                        Meteor m = (Meteor) req.getAttribute(METEOR);
                        if (m != null) {
                            m.destroy();
                        }
                    } catch (Exception ex) {
                        logger.debug("Meteor resume exception: Cannot resume an already resumed/cancelled request", ex);
                    }
                }

                if (req.getAttribute(PRE_SUSPEND) == null) {
                    asyncSupport.action(this);
                }
            } else {
                logger.trace("Cannot resume an already resumed/cancelled request {}", this);
                return this;
            }
        } catch (Throwable t) {
            logger.trace("Wasn't able to resume a connection {}", this, t);
        }
        listeners.clear();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource suspend() {
        return suspend(-1);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource suspend(long timeout) {
        return suspend(timeout, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource suspend(long timeout, TimeUnit timeunit) {
        return suspend(timeout, timeunit, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource suspend(long timeout, TimeUnit timeunit, boolean flushComment) {
        long timeoutms = -1;
        if (timeunit != null) {
            timeoutms = TimeUnit.MILLISECONDS.convert(timeout, timeunit);
        }

        return suspend(timeoutms, flushComment);
    }

    public AtmosphereResource suspend(long timeout, boolean flushComment) {

        if (event.isSuspended() || disableSuspend) return this;

        if (req.getSession(false) != null
                && req.getSession().getMaxInactiveInterval() != -1
                && req.getSession().getMaxInactiveInterval() * 1000 < timeout) {
            throw new IllegalStateException("Cannot suspend a " +
                    "response longer than the session timeout. Increase the value of session-timeout in web.xml");
        }

        if (!event.isResumedOnTimeout()) {

            Enumeration<String> connection = req.getHeaders("Connection");
            if (connection == null) {
                connection = req.getHeaders("connection");
            }

            if (connection != null && connection.hasMoreElements()) {
                String[] e = connection.nextElement().toString().split(",");
                for (String upgrade : e) {
                    if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                        if (writeHeaders && !asyncSupport.supportWebSocket()) {
                            response.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
                        } else {
                            req.setAttribute(FrameworkConfig.TRANSPORT_IN_USE, HeaderConfig.WEBSOCKET_TRANSPORT);
                            flushComment = false;
                        }
                    }
                }
            }

            if (req.getHeader(X_ATMOSPHERE_TRANSPORT) != null && !req.getHeader(X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase("streaming")) {
                flushComment = false;
            }

            if (flushComment) {
                req.setAttribute(FrameworkConfig.TRANSPORT_IN_USE, HeaderConfig.STREAMING_TRANSPORT);
            } else if (req.getHeader(X_ATMOSPHERE_TRANSPORT) == null) {
                req.setAttribute(FrameworkConfig.TRANSPORT_IN_USE, HeaderConfig.LONG_POLLING_TRANSPORT);
            }

            if (writeHeaders && injectCacheHeaders) {
                // Set to expire far in the past.
                response.setHeader(EXPIRES, "-1");
                // Set standard HTTP/1.1 no-cache headers.
                response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate");
                // Set standard HTTP/1.0 no-cache header.
                response.setHeader(PRAGMA, "no-cache");
            }

            cors();

            if (flushComment) {
                write(true);
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

            // Null means SCOPE=REQUEST set by a Meteor
            if (!skipCreation && (broadcaster == null || broadcaster.getScope() == Broadcaster.SCOPE.REQUEST) && !isJersey) {
                String id = broadcaster != null ? broadcaster.getID() : getClass().getName();
                Class<? extends Broadcaster> clazz = broadcaster != null ? broadcaster.getClass() : DefaultBroadcaster.class;

                broadcaster = config.getBroadcasterFactory().lookup(clazz, id, false);
                if (broadcaster == null || broadcaster.getAtmosphereResources().size() > 0) {
                    broadcaster = config.getBroadcasterFactory().lookup(clazz, id + "/" + UUID.randomUUID(), true);
                }
            }

            broadcaster.addAtmosphereResource(this);

            if (req.getAttribute(DefaultBroadcaster.CACHED) != null && transport() != null && (
                    transport().equals(TRANSPORT.LONG_POLLING) || transport().equals(TRANSPORT.JSONP))) {
                action.type(Action.TYPE.CONTINUE);
                // Do nothing because we have found cached message which was written already, and the handler resumed.
                logger.debug("Cached message found, not suspending {}", uuid());
                return this;
            }
            req.removeAttribute(PRE_SUSPEND);
            notifyListeners();
        }
        return this;
    }

    void write(boolean flushPadding) {

        if (beginCompatibleData == null) {
            beginCompatibleData = createStreamingPadding(padding);
        }

        try {
            if (useWriter && !((Boolean) req.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM))) {
                try {
                    response.getWriter();
                } catch (IllegalStateException e) {
                    return;
                }

                if (flushPadding) response.getWriter().write(beginCompatibleData);
                response.getWriter().flush();
            } else {
                try {
                    response.getOutputStream();
                } catch (IllegalStateException e) {
                    return;
                }

                if (flushPadding) response.getOutputStream().write(beginCompatibleData.getBytes());
                response.getOutputStream().flush();
            }

        } catch (Throwable ex) {
            logger.warn("failed to write to response", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    public AtmosphereRequest getRequest(boolean enforceScope) {
        if (enforceScope && !isInScope) {
            throw new IllegalStateException("Request object no longer" + " valid. This object has been cancelled");
        }
        return req;
    }

    /**
     * {@inheritDoc}
     */
    public AtmosphereResponse getResponse(boolean enforceScope) {
        if (enforceScope && !isInScope) {
            throw new IllegalStateException("Response object no longer valid. This object has been cancelled");
        }
        return response;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereRequest getRequest() {
        return getRequest(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResponse getResponse() {
        return getResponse(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Broadcaster getBroadcaster() {
        return getBroadcaster(true);
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
            logger.debug("Broadcaster {} has been destroyed and cannot be re-used. Recreating a new one with the same name. You can turn off that" +
                    " mechanism by adding, in web.xml, {} set to false", broadcaster.getID(), ApplicationConfig.RECOVER_DEAD_BROADCASTER);

            Broadcaster.SCOPE scope = broadcaster.getScope();
            synchronized (this) {
                String id = scope != Broadcaster.SCOPE.REQUEST ? broadcaster.getID() : broadcaster.getID() + ".recovered" + UUID.randomUUID();

                // Another Thread may have added the Broadcaster.
                broadcaster = config.getBroadcasterFactory().lookup(id, true);
                broadcaster.setScope(scope);
                broadcaster.addAtmosphereResource(this);
            }
        }
        return broadcaster;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResourceImpl setBroadcaster(Broadcaster broadcaster) {
        this.broadcaster = broadcaster;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereConfig getAtmosphereConfig() {
        return config;
    }

    /**
     * Return the {@link Action} which represent the state of the response,
     * e.g. suspended, resumed or timedout.
     *
     * @return the {@link Action}
     */
    public Action action() {
        return action;
    }

    /**
     * Protect the object for being used after it got cancelled.
     *
     * @param isInScope
     */
    public void setIsInScope(boolean isInScope) {
        this.isInScope = isInScope;
    }

    /**
     * Is the {@link AtmosphereRequest} still valid.
     *
     * @return true if the {@link AtmosphereRequest} still valid
     */
    public boolean isInScope() {
        return isInScope;
    }

    /**
     * Set the {@link Serializer} used to write broadcasted object.
     *
     * @param s
     */
    @Override
    public AtmosphereResource setSerializer(Serializer s) {
        serializer = s;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isResumed() {
        return isResumed.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        return isCancelled.get();
    }

    /**
     * Write the broadcasted object using the {@link OutputStream}. If a
     * {@link Serializer} is defined, the operation will be delagated to it. If
     * not, the <F> (Response) OutputStream will be used by calling
     * Object.toString.getBytes()
     *
     * @param os an {@link OutputStream}
     * @param o  an Object
     * @throws IOException
     */
    @Override
    public AtmosphereResource write(OutputStream os, Object o) throws IOException {
        if (o == null) throw new IllegalStateException("Object cannot be null");

        if (serializer != null) {
            serializer.write(os, o);
        } else {
            response.getOutputStream().write(o.toString().getBytes(response.getCharacterEncoding()));
        }
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Serializer getSerializer() {
        return serializer;
    }

    /**
     * Output message when Atmosphere suspend a connection.
     *
     * @return message when Atmosphere suspend a connection.
     */
    public static String createStreamingPadding(String padding) {
        StringBuilder s = new StringBuilder();

        if (padding == null || padding.equalsIgnoreCase(FrameworkConfig.ATMOSPHERE_PADDING)) {
            logger.error("Atmosphere PADDING no longer supported started with 1.0.13");
            s.append("<!-- ----------------------------------------------------------" +
                    "------ http://github.com/Atmosphere ----------------------------" +
                    "-------------------------------------------- -->\n");
            s.append("<!-- Welcome to the Atmosphere Framework. To work with all the" +
                    " browsers when suspending connection, Atmosphere must output some" +
                    " data to makes WebKit based browser working.-->\n");
            for (int i = 0; i < 10; i++) {
                s.append("<!-- ----------------------------------------------------------" +
                        "---------------------------------------------------------------" +
                        "-------------------------------------------- -->\n");
            }
            s.append("<!-- EOD -->");
        } else if (padding.equalsIgnoreCase(FrameworkConfig.WHITESPACE_PADDING)) {
            for (int i = 0; i < 8192; i++) {
                s.append(" ");
            }
        } else {
            return padding;
        }
        return s.toString();
    }

    /**
     * Add a {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEventListener
     */
    public AtmosphereResource addEventListener(AtmosphereResourceEventListener e) {
        if (listeners.contains(e)) return this;
        listeners.add(e);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource removeEventListener(AtmosphereResourceEventListener e) {
        listeners.remove(e);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource removeEventListeners() {
        listeners.clear();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource notifyListeners() {
        notifyListeners(event);
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource notifyListeners(AtmosphereResourceEvent event) {
        if (listeners.size() > 0) {
            logger.trace("Invoking listener with {}", event);
        } else {
            return this;
        }

        Action oldAction = action;
        try {
            if (event.isCancelled()) {
                if (!disconnected.getAndSet(true)) {
                    onDisconnect(event);
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
            logger.trace("Listener error {}", t);
            AtmosphereResourceEventImpl.class.cast(event).setThrowable(t);
            try {
                onThrowable(event);
            } catch (Throwable t2) {
                logger.warn("Listener error {}", t2);
            }
        }
        return this;
    }

    /**
     * Notify {@link AtmosphereResourceEventListener} an unexpected exception occured.\
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

    void onSuspend(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onSuspend(e);
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
        }
    }

    void onBroadcast(AtmosphereResourceEvent e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onBroadcast(e);
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
        if (!isCancelled.getAndSet(true)) {
            logger.trace("Cancelling {}", uuid);
            SessionTimeoutSupport.restoreTimeout(req);
            action.type(Action.TYPE.RESUME);
            asyncSupport.action(this);
            // We must close the underlying WebSocket as well.
            if (AtmosphereResponse.class.isAssignableFrom(response.getClass())) {
                AtmosphereResponse.class.cast(response).close();
                AtmosphereResponse.class.cast(response).destroy();
            }

            if (AtmosphereRequest.class.isAssignableFrom(req.getClass())) {
                AtmosphereRequest.class.cast(req).destroy();
            }

            if (broadcaster != null) {
                broadcaster.removeAtmosphereResource(this);
            }
            event.destroy();
        }
    }

    public void _destroy() {
        try {
            removeEventListeners();
            try {
                getBroadcaster(false).removeAtmosphereResource(this);
            } catch (IllegalStateException ex) {
                logger.trace(ex.getMessage(), ex);
            }
            if (config.getBroadcasterFactory().getDefault() != null) {
                config.getBroadcasterFactory().getDefault().removeAllAtmosphereResource(this);
            }
        } catch (Throwable t) {
            logger.trace("destroyResource", t);
        }
    }

    @Override
    public String toString() {
        return "AtmosphereResourceImpl{" +
                "\n uuid=" + uuid +
                ",\n transport=" + transport() +
                ",\n action=" + action +
                ",\n isResumed=" + isResumed() +
                ",\n isCancelled=" + isCancelled() +
                ",\n isSuspended=" + isSuspended() +
                ",\n broadcaster=" + broadcaster.getID() +
                ",\n isInScope=" + isInScope +
                ",\n useWriter=" + useWriter +
                ",\n listeners=" + listeners +
                '}';
    }

    public AtmosphereResourceImpl disableSuspend(boolean disableSuspend) {
        this.disableSuspend = disableSuspend;
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpSession session(boolean create) {
        if (session == null) {
            // http://java.net/jira/browse/GLASSFISH-18856
            session = req.getSession(create);
        }
        return session;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AtmosphereResource padding(String padding) {
        this.padding = padding;
        return this;
    }

    /**
     * {@inheritDoc}
     */
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
        setBroadcaster(r.getBroadcaster());
        atmosphereHandler(r.getAtmosphereHandler());
        return this;
    }

    public void cors() {
        if (enableAccessControl && writeHeaders) {
            response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, req.getHeader("Origin") == null ? "*" : req.getHeader("Origin"));
            response.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

}
