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

import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_CREDENTIALS;
import static org.atmosphere.cpr.HeaderConfig.ACCESS_CONTROL_ALLOW_ORIGIN;
import static org.atmosphere.cpr.HeaderConfig.CACHE_CONTROL;
import static org.atmosphere.cpr.HeaderConfig.EXPIRES;
import static org.atmosphere.cpr.HeaderConfig.PRAGMA;
import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * {@link AtmosphereResource} implementation for supporting {@link HttpServletRequest}
 * and {@link HttpServletResponse}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceImpl implements
        AtmosphereResource<HttpServletRequest, HttpServletResponse> {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereResourceImpl.class);

    public static final String PRE_SUSPEND = AtmosphereResourceImpl.class.getName() + ".preSuspend";
    public static final String SKIP_BROADCASTER_CREATION = AtmosphereResourceImpl.class.getName() + ".skipBroadcasterCreation";
    public static final String METEOR = Meteor.class.getName();

    // The {@link HttpServletRequest}
    private final HttpServletRequest req;
    // The {@link HttpServletResponse}
    private final HttpServletResponse response;
    // The upcoming Action.
    protected final AtmosphereServlet.Action action = new AtmosphereServlet.Action();
    // The Broadcaster
    protected Broadcaster broadcaster;
    // ServletContext
    private final AtmosphereConfig config;
    protected final CometSupport cometSupport;
    private Serializer serializer;
    private boolean isInScope = true;
    private final AtmosphereResourceEventImpl event;
    private String beginCompatibleData;
    private boolean useWriter = true;
    private boolean isResumed = false;
    private boolean isCancelled = false;


    private final ConcurrentLinkedQueue<AtmosphereResourceEventListener> listeners =
            new ConcurrentLinkedQueue<AtmosphereResourceEventListener>();

    private final boolean injectCacheHeaders;
    private final boolean enableAccessControl;
    private final AtomicBoolean isSuspendEvent = new AtomicBoolean(false);
    private final AtmosphereHandler atmosphereHandler;
    private final boolean writeHeaders;

    /**
     * Create an {@link AtmosphereResource}.
     *
     * @param config            The {@link org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig}
     * @param broadcaster       The {@link org.atmosphere.cpr.Broadcaster}.
     * @param req               The {@link javax.servlet.http.HttpServletRequest}
     * @param response          The {@link javax.servlet.http.HttpServletResponse}
     * @param cometSupport      The {@link org.atmosphere.cpr.CometSupport}
     * @param atmosphereHandler The {@link AtmosphereHandler}
     */
    public AtmosphereResourceImpl(AtmosphereConfig config, Broadcaster broadcaster,
                                  HttpServletRequest req, HttpServletResponse response,
                                  CometSupport cometSupport, AtmosphereHandler atmosphereHandler) {
        this.req = req;
        this.response = response;
        this.broadcaster = broadcaster;
        this.config = config;
        this.cometSupport = cometSupport;
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

        String padding = config.getInitParameter(ApplicationConfig.STREAMING_PADDING_MODE);
        beginCompatibleData = createStreamingPadding(padding);

        req.setAttribute(ApplicationConfig.STREAMING_PADDING_MODE, padding);
    }

    /**
     * {@inheritDoc}
     */
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
    public synchronized void resume() {
        // We need to synchronize the method because the resume may occurs at the same time a message is published
        // and we will miss that message. The DefaultBroadcaster synchronize on that method before writing a message.
        try {
            if (!isResumed && isInScope) {
                action.type = AtmosphereServlet.Action.TYPE.RESUME;
                isResumed = true;

                try {
                    logger.debug("Resuming {}", getRequest(false));
                } catch (Throwable ex) {
                    // Jetty NPE toString()
                    // Ignore
                    // Stop here as the request object as becomes invalid.
                    return;
                }

                // We need it as Jetty doesn't support timeout
                Broadcaster b = getBroadcaster(false);
                if (!b.isDestroyed() && b instanceof DefaultBroadcaster) {
                    ((DefaultBroadcaster) b).broadcastOnResume(this);
                }

                notifyListeners();
                listeners.clear();

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
                if (BroadcasterFactory.getDefault() != null) {
                    BroadcasterFactory.getDefault().removeAllAtmosphereResource(this);
                }

                try {
                    req.setAttribute(ApplicationConfig.RESUMED_ON_TIMEOUT, Boolean.FALSE);
                    Meteor m = (Meteor) req.getAttribute(METEOR);
                    if (m != null) {
                        m.destroy();
                    }
                } catch (Exception ex) {
                    logger.debug("Meteor resume exception: Cannot resume an already resumed/cancelled request", ex);
                }

                if (req.getAttribute(PRE_SUSPEND) == null) {
                    cometSupport.action(this);
                }
            } else {
                logger.debug("Cannot resume an already resumed/cancelled request {}", this);
            }

            if (AtmosphereResponse.class.isAssignableFrom(response.getClass())) {
                AtmosphereResponse.class.cast(response).destroy();
            }

            if (AtmosphereRequest.class.isAssignableFrom(req.getClass())) {
                AtmosphereRequest.class.cast(req).destroy();
            }
        } catch (Throwable t) {
            logger.trace("Wasn't able to resume a connection {}", this, t);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void suspend() {
        suspend(-1);
    }

    /**
     * {@inheritDoc}
     */
    public void suspend(long timeout) {
        suspend(timeout, true);
    }

    /**
     * {@inheritDoc}
     */
    public void suspend(long timeout, TimeUnit timeunit) {
        suspend(timeout, timeunit, true);
    }

    /**
     * {@inheritDoc}
     */
    public void suspend(long timeout, TimeUnit timeunit, boolean flushComment) {
        long timeoutms = -1;
        if (timeunit != null) {
            timeoutms = TimeUnit.MILLISECONDS.convert(timeout, timeunit);
        }

        suspend(timeoutms, true);
    }

    public void suspend(long timeout, boolean flushComment) {

        if (event.isSuspended()) return;

        if (config.isSupportSession()
                && req.getSession(false) != null
                && req.getSession().getMaxInactiveInterval() != -1
                && req.getSession().getMaxInactiveInterval() * 1000 < timeout) {
            throw new IllegalStateException("Cannot suspend a " +
                    "response longer than the session timeout. Increase the value of session-timeout in web.xml");
        }

        if (req.getAttribute(DefaultBroadcaster.CACHED) != null) {
            // Do nothing because we have found cached message which was written already, and the handler resumed.
            req.removeAttribute(DefaultBroadcaster.CACHED);
            return;
        }

        if (!event.isResumedOnTimeout()) {

            if (req.getHeaders("Connection") != null && req.getHeaders("Connection").hasMoreElements()) {
                String[] e = req.getHeaders("Connection").nextElement().toString().split(",");
                for (String upgrade : e) {
                    if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                        if (writeHeaders && !cometSupport.supportWebSocket()) {
                            response.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
                        } else {
                            flushComment = false;
                        }
                    }
                }
            }

            if (writeHeaders && injectCacheHeaders) {
                // Set to expire far in the past.
                response.setHeader(EXPIRES, "-1");
                // Set standard HTTP/1.1 no-cache headers.
                response.setHeader(CACHE_CONTROL, "no-store, no-cache, must-revalidate");
                // Set standard HTTP/1.0 no-cache header.
                response.setHeader(PRAGMA, "no-cache");
            }

            if (writeHeaders && enableAccessControl) {
                response.setHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                response.setHeader(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            }

            if (flushComment) {
                write();
            }
            req.setAttribute(PRE_SUSPEND, "true");
            action.type = AtmosphereServlet.Action.TYPE.SUSPEND;
            action.timeout = timeout;

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

                broadcaster = BroadcasterFactory.getDefault().lookup(clazz, id, false);
                if (broadcaster == null || broadcaster.getAtmosphereResources().size() > 0) {
                    broadcaster = BroadcasterFactory.getDefault().lookup(clazz, id + "/" + UUID.randomUUID(), true);
                }
            }

            broadcaster.addAtmosphereResource(this);
            req.removeAttribute(PRE_SUSPEND);
            notifyListeners();
        }
    }

    void write() {
        try {
            if (useWriter && !((Boolean) req.getAttribute(ApplicationConfig.PROPERTY_USE_STREAM))) {
                try {
                    response.getWriter();
                } catch (IllegalStateException e) {
                    return;
                }

                response.getWriter().write(beginCompatibleData);
                response.getWriter().flush();
            } else {
                try {
                    response.getOutputStream();
                } catch (IllegalStateException e) {
                    return;
                }

                response.getOutputStream().write(beginCompatibleData.getBytes());
                response.getOutputStream().flush();
            }

        } catch (Throwable ex) {
            logger.warn("failed to write to response", ex);
        }
    }

    /**
     * {@inheritDoc}
     */
    public HttpServletRequest getRequest(boolean enforceScope) {
        if (enforceScope && !isInScope) {
            throw new IllegalStateException("Request object no longer" + " valid. This object has been cancelled");
        }
        return req;
    }

    /**
     * {@inheritDoc}
     */
    public HttpServletResponse getResponse(boolean enforceScope) {
        if (enforceScope && !isInScope) {
            throw new IllegalStateException("Response object no longer valid. This object has been cancelled");
        }
        return response;
    }

    /**
     * {@inheritDoc}
     */
    public HttpServletRequest getRequest() {
        return getRequest(true);
    }

    /**
     * {@inheritDoc}
     */
    public HttpServletResponse getResponse() {
        return getResponse(true);
    }

    /**
     * {@inheritDoc}
     */
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

        if (autoCreate && broadcaster.isDestroyed() && BroadcasterFactory.getDefault() != null) {
            logger.debug("Broadcaster {} has been destroyed and cannot be re-used. Recreating a new one with the same name. You can turn off that" +
                    " mechanism by adding, in web.xml, {} set to false", broadcaster.getID(), ApplicationConfig.RECOVER_DEAD_BROADCASTER);

            Broadcaster.SCOPE scope = broadcaster.getScope();
            synchronized (this) {
                String id = scope != Broadcaster.SCOPE.REQUEST ? broadcaster.getID() : broadcaster.getID() + ".recovered" + UUID.randomUUID();

                // Another Thread may have added the Broadcaster.
                broadcaster = BroadcasterFactory.getDefault().lookup(id, true);
                broadcaster.setScope(scope);
                broadcaster.addAtmosphereResource(this);
            }
        }
        return broadcaster;
    }

    /**
     * {@inheritDoc}
     */
    public void setBroadcaster(Broadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * {@inheritDoc}
     */
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
     * Is the {@link HttpServletRequest} still valid.
     *
     * @return true if the {@link HttpServletRequest} still valid
     */
    public boolean isInScope() {
        return isInScope;
    }

    /**
     * Set the {@link Serializer} used to write broadcasted object.
     *
     * @param s
     */
    public void setSerializer(Serializer s) {
        serializer = s;
    }

    protected boolean isResumed(){
        return isResumed;
    }

    protected boolean isCancelled(){
        return isCancelled;
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
    public void write(OutputStream os, Object o) throws IOException {
        if (o == null) throw new IllegalStateException("Object cannot be null");

        if (serializer != null) {
            serializer.write(os, o);
        } else {
            response.getOutputStream().write(o.toString().getBytes());
        }
    }

    /**
     * {@inheritDoc}
     */
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

        if (padding == null || padding.equalsIgnoreCase("atmosphere")) {
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
        } else {
            for (int i = 0; i < 2048; i++) {
                s.append(" ");
            }
        }
        return s.toString();
    }

    /**
     * Add a {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEventListener
     */
    public void addEventListener(AtmosphereResourceEventListener e) {
        if (listeners.contains(e)) return;
        listeners.add(e);
    }

    /**
     * Add a {@link AtmosphereResourceEventListener}.
     *
     * @param e an instance of AtmosphereResourceEventListener
     */
    public void removeEventListener(AtmosphereResourceEventListener e) {
        listeners.remove(e);
    }

    /**
     * Remove all {@link AtmosphereResourceEventListener}.
     */
    public void removeEventListeners() {
        listeners.clear();
    }

    /**
     * Notify {@link AtmosphereResourceEventListener}.
     */
    public void notifyListeners() {
        notifyListeners(event);
    }

    /**
     * Notify {@link AtmosphereResourceEventListener}.
     */
    public void notifyListeners(AtmosphereResourceEvent event) {
        if (listeners.size() > 0) {
            logger.trace("Invoking listener with {}", event);
        } else {
            return;
        }

        Action oldAction = action;
        try {
            if (event.isResuming() || event.isResumedOnTimeout()) {
                onResume(event);
            } else if (event.isCancelled()) {
                onDisconnect(event);
            } else if (!isSuspendEvent.getAndSet(true) && event.isSuspended()) {
                onSuspend(event);
            } else if (event.throwable() != null) {
                onThrowable(event);
            } else {
                onBroadcast(event);
            }

            if (oldAction.type != action.type) {
                action().type = Action.TYPE.CREATED;
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
        AtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler =
                (AtmosphereHandler<HttpServletRequest, HttpServletResponse>)
                        req.getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);
        for (AtmosphereResourceEventListener r : listeners) {
            r.onThrowable(e);
        }
    }

    void onSuspend(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onSuspend(e);
        }
    }

    void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onResume(e);
        }
    }

    void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onDisconnect(e);
        }
    }

    void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> e) {
        for (AtmosphereResourceEventListener r : listeners) {
            r.onBroadcast(e);
        }
    }

    public ConcurrentLinkedQueue<AtmosphereResourceEventListener> atmosphereResourceEventListener() {
        return listeners;
    }

    public synchronized void cancel() throws IOException {
        action.type = Action.TYPE.RESUME;
        isCancelled = true;
        cometSupport.action(this);
        // We must close the underlying WebSocket as well.
        if (AtmosphereResponse.class.isAssignableFrom(response.getClass())) {
            AtmosphereResponse.class.cast(response).close();
            AtmosphereResponse.class.cast(response).destroy();
        }

        if (AtmosphereRequest.class.isAssignableFrom(req.getClass())) {
            AtmosphereRequest.class.cast(req).destroy();
        }

        // TODO: Grab some measurement.
//        req = null;
//        response = null;

        // Just in case
        if (broadcaster != null) {
            broadcaster.removeAtmosphereResource(this);
        }
        event.destroy();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AtmosphereResourceImpl that = (AtmosphereResourceImpl) o;

        if (enableAccessControl != that.enableAccessControl) return false;
        if (injectCacheHeaders != that.injectCacheHeaders) return false;
        if (isInScope != that.isInScope) return false;
        if (writeHeaders != that.writeHeaders) return false;
        if (atmosphereHandler != null ? !atmosphereHandler.equals(that.atmosphereHandler) : that.atmosphereHandler != null)
            return false;
        if (broadcaster != null ? !broadcaster.equals(that.broadcaster) : that.broadcaster != null) return false;
        if (isSuspendEvent != null ? !isSuspendEvent.equals(that.isSuspendEvent) : that.isSuspendEvent != null)
            return false;
        if (req != null ? !req.equals(that.req) : that.req != null) return false;
        if (response != null ? !response.equals(that.response) : that.response != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = req != null ? req.hashCode() : 0;
        result = 31 * result + (response != null ? response.hashCode() : 0);
        result = 31 * result + (broadcaster != null ? broadcaster.hashCode() : 0);
        result = 31 * result + (isInScope ? 1 : 0);
        result = 31 * result + (injectCacheHeaders ? 1 : 0);
        result = 31 * result + (enableAccessControl ? 1 : 0);
        result = 31 * result + (isSuspendEvent != null ? isSuspendEvent.hashCode() : 0);
        result = 31 * result + (atmosphereHandler != null ? atmosphereHandler.hashCode() : 0);
        result = 31 * result + (writeHeaders ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "AtmosphereResourceImpl{" +
                ", hasCode" + hashCode() +
                ",\n action=" + action +
                ",\n broadcaster=" + broadcaster.getClass().getName() +
                ",\n cometSupport=" + cometSupport +
                ",\n serializer=" + serializer +
                ",\n isInScope=" + isInScope +
                ",\n useWriter=" + useWriter +
                ",\n listeners=" + listeners +
                '}';
    }

}
