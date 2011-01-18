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
package org.atmosphere.gwt.server.impl;

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.atmosphere.gwt.server.GwtAtmosphereResource;
import org.atmosphere.gwt.server.AtmosphereGwtHandler;
import org.atmosphere.gwt.server.GwtResponseWriter;
import java.util.Collection;
import org.atmosphere.cpr.AtmosphereEventLifecycle;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author p.havelaar
 */
public class GwtAtmosphereResourceImpl implements GwtAtmosphereResource {

    public GwtAtmosphereResourceImpl(AtmosphereResource<HttpServletRequest, HttpServletResponse> resource,
            AtmosphereGwtHandler servlet, int heartBeatInterval) throws IOException {
        this.atmosphereHandler = servlet;
        this.atmResource = resource;
        this.heartBeatInterval = heartBeatInterval;
        this.writer = createResponseWriter();
        resource.getRequest().setAttribute(GwtAtmosphereResource.class.getName(), this);
    }

    @Override
    public Broadcaster getBroadcaster() {
        return atmResource.getBroadcaster();
    }

    @Override
    public HttpSession getSession() {
        return atmResource.getRequest().getSession();
    }

    @Override
    public HttpSession getSession(boolean create) {
        return atmResource.getRequest().getSession(create);
    }

    GwtResponseWriter getResponseWriter() {
        return writer;
    }

    @Override
    public int getHeartBeatInterval() {
        return heartBeatInterval;
    }

    @Override
    public void broadcast(Serializable message) {
        getBroadcaster().broadcast(message, atmResource);
    }

    @Override
    public void broadcast(List<Serializable> messages) {
        getBroadcaster().broadcast(messages, atmResource);
    }

    @Override
    public AtmosphereResource<HttpServletRequest, HttpServletResponse> getAtmosphereResource() {
        return atmResource;
    }

    /**
     * Check to see if this atmosphere resource is still in use by the system.
     * It will query the associated broadcaster if it is still referenced.
     *
     * @param resource
     * @return
     */
    @Override
    public boolean isAlive() {
        if (writer.isTerminated()) {
            return false;
        }
        if ((System.currentTimeMillis() - startTime) < WARMUP_TIME) {
            return true;
        }
        if (atmResource.getBroadcaster() == null) {
            return false;
        }
        Collection<AtmosphereResource<?,?>> res = atmResource.getBroadcaster().getAtmosphereResources();
        for (AtmosphereResource<?,?> ar : res) {
            if (ar == atmResource) {
                return true;
            }
        }
        return suspended;
    }

    long getStartTime() {
        return startTime;
    }

    @Override
    public HttpServletRequest getRequest() {
        return atmResource.getRequest();
    }

    @Override
    public int getConnectionID() {
        return writer.connectionID;
    }

    public void suspend() throws IOException {
        suspend(-1);
    }
    public void suspend(int timeout) throws IOException {
        if (!suspended) {
            atmResource.setSerializer(serializer);
            if (atmResource instanceof AtmosphereEventLifecycle) {
                AtmosphereEventLifecycle ael = (AtmosphereEventLifecycle)atmResource;
                ael.addEventListener(eventListener);
            }
            writer.suspend();
            atmResource.suspend(timeout, false);
        }
    }
    
    public void resume() {
        atmResource.resume();
    }
    
    /** this will be called by the writer to resume an already dead connection.
     * Because this can possibly block for as long as the timeout of http connections we do this in
     * 1 or more seperate threads
     */
    void resumeAfterDeath() {
        atmosphereHandler.execute(new Runnable() {
            @Override
            public void run() {
                atmResource.resume();
            }
        });
    }

    @Override
    public <T> void setAttribute(String name, T value) {
        atmResource.getRequest().setAttribute(name, value);
    }

    @Override
    public <T> T getAttribute(String name) {
        return (T) atmResource.getRequest().getAttribute(name);
    }

    public GwtResponseWriterImpl getWriterImpl() {
        return writer;
    }
    
	ScheduledFuture<?> scheduleHeartbeat() {
        return getBroadcaster().getBroadcasterConfig().getScheduledExecutorService()
                .schedule(heartBeatTask, heartBeatInterval, TimeUnit.MILLISECONDS);
	}

    void terminate(boolean serverInitiated) {
        AtmosphereGwtHandler s = atmosphereHandler;
        if (s != null) {
            atmosphereHandler = null;
            if (suspended) {
                atmResource.resume();
            }
            s.cometTerminated(this, serverInitiated);
        }
    }

	private GwtResponseWriterImpl createResponseWriter() throws IOException {

		ClientOracle clientOracle = RPCUtil.getClientOracle(atmResource.getRequest(), atmosphereHandler.getServletContext());
		SerializationPolicy serializationPolicy = clientOracle == null ? RPCUtil.createSimpleSerializationPolicy() : null;

        String transport = atmResource.getRequest().getParameter("tr");
        if ("WebSocket".equals(transport)) {
            logger.debug("atmosphere-gwt Using websocket");
            return new WebsocketResponseWriter(this, serializationPolicy, clientOracle);
        } else if ("HTTPRequest".equals(transport)) {
            logger.debug("atmosphere-gwt Using XMLHttpRequest");
            return new HTTPRequestResponseWriter(this, serializationPolicy, clientOracle);
		} else if ("IFrame".equals(transport)) {
            logger.debug("atmosphere-gwt Using streaming IFrame");
            return new IFrameResponseWriter(this, serializationPolicy, clientOracle);
		} else if ("OperaEventSource".equals(transport)) {
            logger.debug("atmosphere-gwt Using Opera EventSource");
			return new OperaEventSourceResponseWriter(this, serializationPolicy, clientOracle);
		} else if ("IEXDomainRequest".equals(transport)) {
            logger.debug("atmosphere-gwt Using IE XDomainRequest");
            return new IEXDomainRequestResponseWriter(this, serializationPolicy, clientOracle);
        } else if ("IEHTMLFile".equals(transport)) {
            logger.debug("atmosphere-gwt Using IE html file iframe");
			return new IEHTMLFileResponseWriter(this, serializationPolicy, clientOracle);
		} else {
            throw new IllegalStateException("Failed to determine responsewriter");
        }
	}

    private final static long WARMUP_TIME = 10000;
    private long startTime = System.currentTimeMillis();
    private final GwtResponseWriterImpl writer;
    private AtmosphereResource<HttpServletRequest, HttpServletResponse> atmResource;
    private final int heartBeatInterval;
    private Heartbeat heartBeatMessage = new Heartbeat();
    private AtmosphereGwtHandler atmosphereHandler;
    private boolean suspended = false;
    private Logger logger = LoggerFactory.getLogger(getClass());

    private Runnable heartBeatTask = new Runnable() {
        @Override
        public void run() {
            if (isAlive()) {
                broadcast(heartBeatMessage);
            }
        }
    };

    private final Serializer serializer = new Serializer() {
        @Override
        public void write(OutputStream out, Object o) throws IOException {
            if (o instanceof Serializable) {
                try {
                    writer.write((Serializable)o);
                } catch (IOException e) {
                    if (writer.isTerminated()) {
                        logger.debug("broadcast failed, connection terminated:" + e.getMessage(), e);
                    }
                    throw e;
                }
            } else  if (o instanceof List) {
                List<?> list = (List)o;
                if (list.size() > 0) {
                    if (!(list.get(0) instanceof Serializable)) {
                        throw new IOException("Failed to write a list of objects that are not serializable");
                    }
                    writer.write((List<Serializable>)o);
                }
            } else {
                logger.warn("Failed to write an object that is not serializable");
            }
        }
    };

    private final AtmosphereResourceEventListener eventListener = new AtmosphereResourceEventListener() {

        @Override
        public void onSuspend(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> are) {
            suspended = true;
        }

        @Override
        public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
            suspended = false;
            writer.setTerminated(false);
        }

        @Override
        public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
            suspended = false;
            writer.setTerminated(false);
        }

        @Override
        public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        }

        @Override
        public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        }
    };
}
