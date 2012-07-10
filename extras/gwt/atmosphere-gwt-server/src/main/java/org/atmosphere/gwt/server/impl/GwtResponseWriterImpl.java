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

import org.atmosphere.gwt.server.GwtResponseWriter;
import org.atmosphere.gwt.server.deflate.DeflaterOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.atmosphere.gwt.shared.Constants;
import org.atmosphere.gwt.shared.SerialMode;

/**
 * @author p.havelaar
 */
public abstract class GwtResponseWriterImpl implements GwtResponseWriter {


    protected Writer writer;
    protected final GwtAtmosphereResourceImpl resource;
    protected final int connectionID;
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private GwtRpcSerializer gwtRpc;


    protected GwtResponseWriterImpl(GwtAtmosphereResourceImpl resource) {
        this.resource = resource;
        this.connectionID = connectionIDs.getAndIncrement();
        if (getSerializationMode() == SerialMode.RPC) {
            gwtRpc = new GwtRpcSerializer(resource.getRequest(), resource.getServletContext());
        }
    }


    @Override
    public synchronized boolean isTerminated() {
        return terminated;
    }

    protected SerialMode getSerializationMode() {
        String mode = resource.getRequest().getParameter(Constants.CLIENT_DESERIALZE_MODE_PARAMETER);
        if (mode != null) {
            return SerialMode.valueOf(mode);
        } else {
            return SerialMode.RPC;
        }
    }

    public HttpServletRequest getRequest() {
        return resource.getAtmosphereResource().getRequest();
    }

    public HttpServletResponse getResponse() {
        return resource.getAtmosphereResource().getResponse();
    }

    synchronized void scheduleHeartbeat() {
        if (logger.isTraceEnabled()) {
            logger.trace("Schedule heartbeat for [" + connectionID + "]");
            logger.trace("Last write for [" + connectionID + "] was " + new Date(lastWriteTime).toString());
        }
        lastWriteTime = System.currentTimeMillis();
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
        }
        heartbeatFuture = resource.scheduleHeartbeat();
    }

    @Override
    public void sendError(int statusCode) throws IOException {
        sendError(statusCode, null);
    }

    @Override
    public synchronized void sendError(int statusCode, String message) throws IOException {
        try {
            if (writer == null) {
                getResponse().reset();
                getResponse().setHeader("Cache-Control", "no-cache");
                getResponse().setCharacterEncoding("UTF-8");

                writer = new OutputStreamWriter(getResponse().getOutputStream(), "UTF-8");
            }

            doSendError(statusCode, message);
        } catch (IllegalStateException e) {
            logger.error("Error resetting response to send error: " + e.getMessage());
        } catch (IOException e) {
            logger.debug("Failed to send error to client", e);
        } finally {
            setTerminated(true);
        }
    }

    protected OutputStream getOutputStream(OutputStream outputStream) {
        return outputStream;
    }

    public synchronized void initiate() throws IOException {
        getResponse().setHeader("Cache-Control", "no-cache");
        getResponse().setCharacterEncoding("UTF-8");

        OutputStream outputStream = getResponse().getOutputStream();
        outputStream = getOutputStream(outputStream);

        if (supportsDeflate()) {
            String acceptEncoding = getRequest().getHeader("Accept-Encoding");
            if (acceptEncoding != null && acceptEncoding.contains("deflate")) {
                getResponse().setHeader("Content-Encoding", "deflate");
                outputStream = new DeflaterOutputStream(outputStream);
            }
        }

        writer = new OutputStreamWriter(outputStream, "UTF-8");

        if (logger.isTraceEnabled()) {
            logger.trace("Initiated [" + connectionID + "]");
        }
        getRequest().setAttribute("connectionID", connectionID);
        scheduleHeartbeat();
    }
    
    public void suspend() throws IOException {
        try {
            synchronized (this) {
                if (terminated) {
                    return;
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("Suspending [" + connectionID + "]");
                }
                doSuspend();

                flush();
            }
        } catch (IOException e) {
            logger.error("Error suspending response", e);
            synchronized (this) {
                setTerminated(false);
            }
            throw e;
        }
    }

    @Override
    public synchronized void terminate() throws IOException {
        if (!terminated) {
            try {
                doTerminate();
                flush();
            } finally {
                setTerminated(true);
            }
        }
    }

    void tryTerminate() {
        try {
            terminate();
        } catch (IOException e) {
            logger.error("Error terminating response", e);
        }
    }

    @Override
    public void write(Serializable message) throws IOException {
        write(Collections.singletonList(message), true);
    }

    @Override
    public void write(Serializable message, boolean flush) throws IOException {
        write(Collections.singletonList(message), flush);
    }

    @Override
    public void write(List<? extends Serializable> messages) throws IOException {
        write(messages, true);
    }

    @Override
    public synchronized void write(List<? extends Serializable> messages, boolean flush) throws IOException {
        if (terminated) {
            throw new IOException("CometServletResponse terminated");
        }
        try {
            if (messages.size() == 1 && messages.get(0) instanceof String
                    && messages.get(0).equals(GwtAtmosphereResourceImpl.HEARTBEAT_MESSAGE)) {
                heartbeat();
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("Writing #" + messages.size() + " messages to [" + connectionID + "]");
                }
                doWrite(messages);
                if (flush) {
                    flush();
                }
                scheduleHeartbeat();
            }
        } catch (IOException e) {
            resource.resumeAfterDeath();
            setTerminated(false);
            throw e;
        }
    }

    @Override
    public synchronized void heartbeat() throws IOException {
        if (!terminated) {
            try {
                logger.trace("Sending heartbeat [" + connectionID + "]");
                doHeartbeat();
                flush();
                scheduleHeartbeat();
            } catch (IOException e) {
                logger.debug("Failed to send heartbeat", e);
                setTerminated(false);
                throw e;
            }
        }
    }

    synchronized void flush() throws IOException {
        writer.flush();
    }

    synchronized void setTerminated(boolean serverInitiated) {

        if (!terminated) {
            terminated = true;

            if (logger.isTraceEnabled()) {
                logger.trace("Terminating [" + connectionID + "]");
            }
            if (heartbeatFuture != null) {
                heartbeatFuture.cancel(false);
                heartbeatFuture = null;
            }

            if (serverInitiated) {
                try {
                    if (writer != null) {
                        writer.close();
                    }
                } catch (IOException e) {
                    logger.error("Error closing connection", e);
                }
            }
            resource.terminate(serverInitiated);
        }
    }

    @Override
    public long getLastWriteTime() {
        return lastWriteTime;
    }

    protected abstract void doSendError(int statusCode, String message) throws IOException;

    protected abstract void doSuspend() throws IOException;

    protected abstract void doWrite(List<? extends Serializable> messages) throws IOException;

    protected abstract void doHeartbeat() throws IOException;

    protected abstract void doTerminate() throws IOException;

    
    protected boolean supportsDeflate() {
        return true;
    }

    protected boolean hasSession() {
        HttpSession session = resource.getSession(false);
        return session != null;
    }

    protected String serialize(Object message) throws NotSerializableException, UnsupportedEncodingException {
        switch (getSerializationMode()) {
        case RPC:
            return gwtRpc.serialize(message);

        case JSON:
            throw new UnsupportedOperationException("Not implemented yet");

        default:
        case PLAIN:
            return message.toString();
        }
    }
    

    private boolean terminated;
    private volatile long lastWriteTime;
    private ScheduledFuture<?> heartbeatFuture;
    private static AtomicInteger connectionIDs = new AtomicInteger(1);
}