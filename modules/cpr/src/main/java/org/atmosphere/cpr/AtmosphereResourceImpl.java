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

import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
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

/**
 * {@link AtmosphereResource} implementation for supporting {@link HttpServletRequest}
 * and {@link HttpServletResponse}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceImpl implements
        AtmosphereResource<HttpServletRequest, HttpServletResponse>, AtmosphereEventLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereResourceImpl.class);

    public static final String PRE_SUSPEND = AtmosphereResourceImpl.class.getName() + ".preSuspend";

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
    private final static String beginCompatibleData = createCompatibleStringJunk();
    private boolean useWriter = true;

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

        String nocache = config.getInitParameter(AtmosphereServlet.NO_CACHE_HEADERS);
        injectCacheHeaders = nocache != null ? false : true;

        String ac = config.getInitParameter(AtmosphereServlet.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER);
        enableAccessControl = ac != null ? false : true;

        String wh = config.getInitParameter(AtmosphereServlet.WRITE_HEADERS);
        writeHeaders = wh != null ? Boolean.parseBoolean(wh) : true;

        req.setAttribute(AtmosphereServlet.NO_CACHE_HEADERS, injectCacheHeaders);
        req.setAttribute(AtmosphereServlet.DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER, enableAccessControl);
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
    public void resume() {
        if (!event.isResuming() && !event.isResumedOnTimeout() && event.isSuspended() && isInScope) {
            action.type = AtmosphereServlet.Action.TYPE.RESUME;

            // We need it as Jetty doesn't support timeout
            Broadcaster b = getBroadcaster();
            if (b instanceof DefaultBroadcaster) {
                ((DefaultBroadcaster) b).broadcastOnResume(this);
            }

            notifyListeners();
            listeners.clear();
            try {
                broadcaster.removeAtmosphereResource(this);
            } catch (IllegalStateException ex) {
                logger.trace(ex.getMessage(), ex);
            }

            // Resuming here means we need to pull away from all other Broadcaster, if they exists.
            if (BroadcasterFactory.getDefault() != null) {
                BroadcasterFactory.getDefault().removeAllAtmosphereResource(this);
            }

            try {
                req.setAttribute(AtmosphereServlet.RESUMED_ON_TIMEOUT, Boolean.FALSE);
            } catch (Exception ex) {
                logger.debug("Cannot resume an already resumed/cancelled request");
            }
            cometSupport.action(this);
        } else {
            logger.debug("Cannot resume an already resumed/cancelled request");
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

        if (req.getSession(false) != null && req.getSession().getMaxInactiveInterval() != -1 && req.getSession().getMaxInactiveInterval() * 1000 < timeout) {
            throw new IllegalStateException("Cannot suspend a " +
                    "response longer than the session timeout. Increase the value of session-timeout in web.xml");
        }

        if (!event.isResumedOnTimeout()) {

            String[] e = req.getHeaders("Connection").nextElement().split(",");
            for(String upgrade: e) {
                if (upgrade.trim().equalsIgnoreCase("Upgrade")) {
                    if (writeHeaders && !cometSupport.supportWebSocket()) {
                        response.addHeader("X-Atmosphere-error", "Websocket protocol not supported");
                    } else {
                        flushComment = false;
                    }
                }
            }

            if (writeHeaders && injectCacheHeaders) {
                // Set to expire far in the past.
                response.setHeader("Expires", "-1");
                // Set standard HTTP/1.1 no-cache headers.
                response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                // Set standard HTTP/1.0 no-cache header.
                response.setHeader("Pragma", "no-cache");
            }

            if (writeHeaders && enableAccessControl) {
                response.setHeader("Access-Control-Allow-Origin", "*");
            }

            if (flushComment) {
                write();
            }
            req.setAttribute(PRE_SUSPEND, "true");
            action.type = AtmosphereServlet.Action.TYPE.SUSPEND;
            action.timeout = timeout;

            // TODO: We can possibly optimize that call by avoiding creating a Broadcaster if we are sure the Broadcaster
            // is unique.
            if (broadcaster.getScope() == Broadcaster.SCOPE.REQUEST) {
                String id = broadcaster.getID();
                Class<? extends Broadcaster> clazz = broadcaster.getClass();
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
            if (useWriter && !((Boolean) req.getAttribute(AtmosphereServlet.PROPERTY_USE_STREAM))) {
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
    public HttpServletRequest getRequest() {
        if (!isInScope) {
            throw new IllegalStateException("Request object no longer" + " valid. This object has been cancelled");
        }
        return req;
    }

    /**
     * {@inheritDoc}
     */
    public HttpServletResponse getResponse() {
        if (!isInScope) {
            throw new IllegalStateException("Response object no longer valid. This object has been cancelled");
        }
        return response;
    }

    /**
     * {@inheritDoc}
     */
    public Broadcaster getBroadcaster() {
        if (broadcaster == null) {
            throw new IllegalStateException("No Broadcaster associated with this AtmosphereResource.");
        }
        return broadcaster;
    }

    /**
     * {@inheritDoc}
     */
    public void setBroadcaster(Broadcaster broadcaster) {
        this.broadcaster = broadcaster;
        if (broadcaster != null) {
            broadcaster.getBroadcasterConfig().setAtmosphereConfig(config);
        }
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
    protected void setIsInScope(boolean isInScope) {
        this.isInScope = isInScope;
    }

    /**
     * Is the {@link HttpServletRequest} still valid.
     *
     * @return true if the {@link HttpServletRequest} still vali
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
    public static String createCompatibleStringJunk() {
        StringBuilder s = new StringBuilder();

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
                        req.getAttribute(AtmosphereServlet.ATMOSPHERE_HANDLER);
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

    @Override
    public String toString() {
        return "AtmosphereResourceImpl{" +
                ", action=" + action +
                ", broadcaster=" + broadcaster.getClass().getName() +
                ", cometSupport=" + cometSupport +
                ", serializer=" + serializer +
                ", isInScope=" + isInScope +
                ", useWriter=" + useWriter +
                ", listeners=" + listeners +
                '}';
    }

}
