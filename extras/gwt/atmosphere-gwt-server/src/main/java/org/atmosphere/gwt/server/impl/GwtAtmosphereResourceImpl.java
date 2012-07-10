/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.gwt.server.impl;

import javax.servlet.ServletContext;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.Serializer;
import org.atmosphere.gwt.server.AtmosphereGwtHandler;
import org.atmosphere.gwt.server.GwtAtmosphereResource;
import org.atmosphere.gwt.server.GwtResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author p.havelaar
 */
public class GwtAtmosphereResourceImpl implements GwtAtmosphereResource {

    public static final String HEARTBEAT_MESSAGE = "4dc5bdb9-edc8-4edf-8833-ab478326d8c9";

    public GwtAtmosphereResourceImpl(AtmosphereResource resource,
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
    public ServletContext getServletContext() {
        return atmosphereHandler.getServletContext();
    }

    @Override
    public HttpSession getSession() {
        return atmResource.session();
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (atmResource.session() != null) {
            return atmResource.session();
        } else if (atmResource.getRequest() != null) {
            return atmResource.getRequest().getSession(create);
        } else {
            return null;
        }
    }

    GwtResponseWriter getResponseWriter() {
        return writer;
    }

    @Override
    public int getHeartBeatInterval() {
        return heartBeatInterval;
    }

    @Override
    public void post(Serializable message) {
        getBroadcaster().broadcast(message, atmResource);
    }

    @Override
    public void post(List<Serializable> messages) {
        getBroadcaster().broadcast(messages, atmResource);
    }

    @Override
    public AtmosphereResource getAtmosphereResource() {
        return atmResource;
    }

    /**
     * Check to see if this atmosphere resource is still in use by the system.
     * It will query the associated broadcaster if it is still referenced.
     *
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
        Collection<AtmosphereResource> res = atmResource.getBroadcaster().getAtmosphereResources();
        for (AtmosphereResource ar : res) {
            if (ar == atmResource) {
                return true;
            }
        }
        return suspended;
    }

    @Override
    public boolean isSystemMessage(Serializable message) {
        return HEARTBEAT_MESSAGE.equals(message);
    }    

    long getStartTime() {
        return startTime;
    }

    @Override
    public AtmosphereRequest getRequest() {
        return atmResource.getRequest();
    }

    @Override
    public AtmosphereResponse getResponse() {
        return atmResource.getResponse();
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
            atmResource.setSerializer(serializer).addEventListener(eventListener);
            writer.suspend();
            atmResource.suspend(timeout, false);
        }
    }

    public void resume() {
        atmResource.resume();
    }

    /**
     * this will be called by the writer to resume an already dead connection.
     * Because this can possibly block for as long as the timeout of http connections we do this in
     * 1 or more seperate threads
     */
    void resumeAfterDeath() {
        if (atmosphereHandler != null) {
            atmosphereHandler.execute(new Runnable() {
                @Override
                public void run() {
                    atmResource.resume();
                }
            });
        }
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

        String transport = atmResource.getRequest().getParameter("tr");
        if ("WebSocket".equals(transport)) {
            logger.debug("atmosphere-gwt Using websocket");
            return new WebsocketResponseWriter(this);
        } else if ("HTTPRequest".equals(transport)) {
            logger.debug("atmosphere-gwt Using XMLHttpRequest");
            return new HTTPRequestResponseWriter(this);
        } else if ("IFrame".equals(transport)) {
            logger.debug("atmosphere-gwt Using streaming IFrame");
            return new IFrameResponseWriter(this);
        } else if ("OperaEventSource".equals(transport)) {
            logger.debug("atmosphere-gwt Using Opera EventSource");
            return new OperaEventSourceResponseWriter(this);
        } else if ("IEXDomainRequest".equals(transport)) {
            logger.debug("atmosphere-gwt Using IE XDomainRequest");
            return new IEXDomainRequestResponseWriter(this);
        } else if ("IEHTMLFile".equals(transport)) {
            logger.debug("atmosphere-gwt Using IE html file iframe");
            return new IEHTMLFileResponseWriter(this);
        } else {
            throw new IllegalStateException("Failed to determine responsewriter");
        }
    }

    private final static long WARMUP_TIME = 10000;
    private long startTime = System.currentTimeMillis();
    private final GwtResponseWriterImpl writer;
    private AtmosphereResource atmResource;
    private final int heartBeatInterval;
    AtmosphereGwtHandler atmosphereHandler;
    private boolean suspended = false;
    private Logger logger = LoggerFactory.getLogger(getClass());

    private Runnable heartBeatTask = new Runnable() {
        @Override
        public void run() {
            if (isAlive()) {
                post(HEARTBEAT_MESSAGE);
            }
        }
    };

    private final Serializer serializer = new Serializer() {
        @Override
        public void write(OutputStream out, Object o) throws IOException {
            if (o instanceof Serializable) {
                try {
                    writer.write((Serializable) o);
                } catch (IOException e) {
                    if (writer.isTerminated()) {
                        logger.debug("broadcast failed, connection terminated:" + e.getMessage(), e);
                    }
                    throw e;
                }
            } else if (o instanceof List) {
                List<?> list = (List) o;
                if (list.size() > 0) {
                    if (!(list.get(0) instanceof Serializable)) {
                        throw new IOException("Failed to write a list of objects that are not serializable");
                    }
                    writer.write((List<Serializable>) o);
                }
            } else {
                logger.warn("Failed to write an object that is not serializable");
            }
        }
    };

    private final AtmosphereResourceEventListener eventListener = new AtmosphereResourceEventListener() {

        @Override
        public void onSuspend(AtmosphereResourceEvent are) {
            suspended = true;
        }

        @Override
        public void onResume(AtmosphereResourceEvent event) {
            suspended = false;
            writer.setTerminated(false);
        }

        @Override
        public void onDisconnect(AtmosphereResourceEvent event) {
            suspended = false;
            writer.setTerminated(false);
        }

        @Override
        public void onBroadcast(AtmosphereResourceEvent event) {
        }

        @Override
        public void onThrowable(AtmosphereResourceEvent event) {
        }
    };
}
