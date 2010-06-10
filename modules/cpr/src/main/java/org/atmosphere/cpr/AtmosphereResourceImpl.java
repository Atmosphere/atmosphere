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
import org.atmosphere.util.LoggerUtils;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * {@link AtmosphereResource} implementation for supporting {@link HttpServletRequest}
 * and {@link HttpServletResponse}.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereResourceImpl implements
        AtmosphereResource<HttpServletRequest, HttpServletResponse>, AtmosphereEventLifecycle {

    public final static String PRE_SUSPEND = AtmosphereResourceImpl.class.getName() + ".preSuspend";


    // The {@link HttpServletRequest}
    private final HttpServletRequest req;

    // The {@link HttpServletResponse}
    private final HttpServletResponse res;

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


    /**
     * Create an {@link AtmosphereResource}.
     *
     * @param config
     * @param broadcaster The {@link Broadcaster}.
     * @param req         The {@link HttpServletRequest}
     * @param res         The {@link HttpServletResponse}
     */
    public AtmosphereResourceImpl(AtmosphereConfig config, Broadcaster broadcaster,
                                  HttpServletRequest req, HttpServletResponse res,
                                  CometSupport cometSupport) {
        this.req = req;
        this.res = res;
        this.broadcaster = broadcaster;
        this.config = config;
        this.cometSupport = cometSupport;
        this.event = new AtmosphereResourceEventImpl(this);
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
    public void resume() {
        if (!event.isResuming() && !event.isResumedOnTimeout() && event.isSuspended() && isInScope) {
            action.type = AtmosphereServlet.Action.TYPE.RESUME;
            notifyListeners();
            listeners.clear();
            broadcaster.removeAtmosphereResource(this);
            try {
                req.setAttribute(AtmosphereServlet.RESUMED_ON_TIMEOUT, Boolean.FALSE);
            } catch (Exception ex) {
                if (LoggerUtils.getLogger().isLoggable(Level.FINE)){
                    LoggerUtils.getLogger().fine("Cannot resume an already resumed/cancelled request ");
                }
            }
            cometSupport.action(this);
        } else {
            if (LoggerUtils.getLogger().isLoggable(Level.FINE)){
                LoggerUtils.getLogger().fine("Cannot resume an already resumed/cancelled request ");
            }
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

    public void suspend(long timeout, boolean flushComment) {
        if (!event.isResumedOnTimeout()) {
            
            // Set to expire far in the past.
            res.setHeader("Expires", "-1");
            // Set standard HTTP/1.1 no-cache headers.
            res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            // Set standard HTTP/1.0 no-cache header.
            res.setHeader("Pragma", "no-cache");

            if (flushComment && !Boolean.valueOf(config.getInitParameter(AtmosphereServlet.SUSPEND_WITHOUT_COMMENT))) {
                write();
            }
            req.setAttribute(PRE_SUSPEND, "true");
            action.type = AtmosphereServlet.Action.TYPE.SUSPEND;
            action.timeout = timeout;
            broadcaster.addAtmosphereResource(this);
            req.removeAttribute(PRE_SUSPEND);
            notifyListeners();
        }
    }

    void write() {
        try {
            if (useWriter && !((Boolean) req.getAttribute(AtmosphereServlet.PROPERTY_USE_STREAM))) {
                try {
                    res.getWriter();
                } catch (IllegalStateException e) {
                    return;
                }

                res.getWriter().write(beginCompatibleData);
                res.getWriter().flush();
            } else {
                try {
                    res.getOutputStream();
                } catch (IllegalStateException e) {
                    return;
                }

                res.getOutputStream().write(beginCompatibleData.getBytes());
                res.getOutputStream().flush();
            }

        } catch (Throwable ex) {
            LoggerUtils.getLogger().log(Level.WARNING, "", ex);
        }
    }


    /**
     * {@inheritDoc}
     */
    public HttpServletRequest getRequest() {
        if (!isInScope)
            throw new IllegalStateException("Request object no longer" +
                    " valid. This object has been cancelled");
        return req;
    }

    /**
     * {@inheritDoc}
     */
    public HttpServletResponse getResponse() {
        if (!isInScope)
            throw new IllegalStateException("Response object no longer" +
                    " valid. This object has been cancelled");
        return res;
    }

    /**
     * {@inheritDoc}
     */
    public Broadcaster getBroadcaster() {
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
    protected void setIsInScope(boolean isInScope) {
        this.isInScope = isInScope;
    }

    /**
     * Is the {@link HttpServletRequest} still valid.
     * @return true if the {@link HttpServletRequest} still vali
     */
    public boolean isInScope(){
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
     * @param o an Object
     * @throws IOException
     */
    public void write(OutputStream os, Object o) throws IOException {
        if (o == null) throw new IllegalStateException("Object cannot be null");

        if (serializer != null) {
            serializer.write(os, o);
        } else {
            res.getOutputStream().write(o.toString().getBytes());
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
                "---------------------------------------------------------------" +
                "-------------------------------------------- -->\n");
        s.append("<!-- Welcome to the Atmosphere Framework. To work with all the" +
                " browsers when suspending connection, Atmosphere must output some" +
                " data to makes WebKit based browser working.-->\n");
        for (int i = 0; i < 10; i++) {
            s.append("<!-- ----------------------------------------------------------" +
                    "---------------------------------------------------------------" +
                    "-------------------------------------------- -->\n");
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
        if (event.isResuming() || event.isResumedOnTimeout()) {
            onResume(event);
        } else if (event.isCancelled()) {
            onDisconnect(event);
        } else if (event.isSuspended()){
            onSuspend(event);
        } else {
            onBroadcast(event);
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


}
