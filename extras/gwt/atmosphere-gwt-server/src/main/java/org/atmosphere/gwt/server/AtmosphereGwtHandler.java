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

package org.atmosphere.gwt.server;

import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.server.rpc.SerializationPolicy;
import com.google.gwt.user.server.rpc.SerializationPolicyProvider;
import com.google.gwt.user.server.rpc.impl.ServerSerializationStreamReader;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServletProcessor;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.gwt.server.impl.GwtAtmosphereResourceImpl;
import org.atmosphere.gwt.server.impl.RPCUtil;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.gwt.shared.Constants;
import org.atmosphere.gwt.shared.SerialMode;

/**
 * @author p.havelaar
 */
public class AtmosphereGwtHandler extends AbstractReflectorAtmosphereHandler
        implements Executor, AtmosphereServletProcessor {

    public static final int NO_TIMEOUT = -1;
    public static final String GWT_BROADCASTER_ID = "GWT_BROADCASTER";

    private static final int DEFAULT_HEARTBEAT = 15 * 1000; // 15 seconds by default
    private ExecutorService executorService;
    private int heartbeat = DEFAULT_HEARTBEAT;
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    protected SerializationPolicyProvider cometSerializationPolicyProvider = new SerializationPolicyProvider() {
        @Override
        public SerializationPolicy getSerializationPolicy(String moduleBaseURL, String serializationPolicyStrongName) {
            return RPCUtil.createSimpleSerializationPolicy();
        }
    };

    public int doComet(GwtAtmosphereResource resource) throws ServletException, IOException {
        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(Broadcaster.class, GWT_BROADCASTER_ID);
        if (broadcaster == null) {
            broadcaster = BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, GWT_BROADCASTER_ID);
        }
        resource.getAtmosphereResource().setBroadcaster(broadcaster);
        return NO_TIMEOUT;
    }

    public void cometTerminated(GwtAtmosphereResource cometResponse, boolean serverInitiated) {
        resources.remove(cometResponse.getConnectionID());
    }

    /**
     * Default implementation echo's the message back to the client
     *
     * @param messages
     * @param cometResource
     */
    public void doPost(HttpServletRequest postRequest, HttpServletResponse postResponse,
            List<Serializable> messages, GwtAtmosphereResource cometResource) {
        if (cometResource != null) {
            if (messages.size() == 1) {
                cometResource.post(messages.get(0));
            } else {
                cometResource.post(messages);
            }
        }
    }

    @Deprecated
    protected Broadcaster getBroadcaster(GwtAtmosphereResource resource) {
        return resource.getBroadcaster();
    }

    /**
     * This can be used to lookup a resource for instance if you are implementing a remote service call
     * You will need to pass the connectionID, which you can pass as an url parameter {getConnectionID()} or
     * directly in your remote call
     *
     * @param connectionId
     * @return
     */
    protected GwtAtmosphereResource lookupResource(int connectionId) {
        GwtAtmosphereResource r = resources.get(connectionId);
        if (r != null) {
            return r;
        } else {
            logger.info("Failed to find resource for [" + connectionId + "]");
        }
        return null;
    }

    // -------------- you most likely don't need to override the functions below -----------------

    private Map<Integer, GwtAtmosphereResource> resources;
    private ServletContext context;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        executorService = Executors.newCachedThreadPool();
        String heartbeat = servletConfig.getInitParameter("heartbeat");
        context = servletConfig.getServletContext();
        if (heartbeat != null) {
            this.heartbeat = Integer.parseInt(heartbeat);
        }
    }

    @Override
    public void destroy() {
        if (executorService != null) {
            executorService.shutdown();
        }

        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public int getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(int heartbeat) {
        this.heartbeat = heartbeat;
    }

    public ServletContext getServletContext() {
        return context;
    }

    protected void reapResources() {
        if (resources != null) {
            for (GwtAtmosphereResource resource : resources.values()) {
                if (!resource.isAlive()) {
                    resources.remove(resource.getConnectionID());
                }
            }
        }
    }

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {

        HttpServletRequest request = resource.getRequest();

        String servertransport = request.getParameter("servertransport");
        Object webSocketSubProtocol = resource.getRequest().getAttribute(FrameworkConfig.WEBSOCKET_SUBPROTOCOL);
        if ("rpcprotocol".equals(servertransport)) {
            
            Integer connectionID = Integer.parseInt(request.getParameter("connectionID"));
            doServerMessage(request, resource.getResponse(), connectionID);
            return;
            
        } else if (webSocketSubProtocol != null 
                  && webSocketSubProtocol.equals(FrameworkConfig.SIMPLE_HTTP_OVER_WEBSOCKET)) {
        
            Integer connectionID = (Integer) request.getAttribute(AtmosphereGwtHandler.class.getName() 
                                        + ".connectionID");
            doServerMessage(request, resource.getResponse(), connectionID);
            return;
        }
        
        try {
            int requestHeartbeat = heartbeat;
            String requestedHeartbeat = request.getParameter("heartbeat");
            if (requestedHeartbeat != null) {
                try {
                    requestHeartbeat = Integer.parseInt(requestedHeartbeat);
                    if (requestHeartbeat <= 0) {
                        throw new IOException("invalid heartbeat parameter");
                    }
                    requestHeartbeat = computeHeartbeat(requestHeartbeat);
                } catch (NumberFormatException e) {
                    throw new IOException("invalid heartbeat parameter");
                }
            }

            GwtAtmosphereResourceImpl resourceWrapper = new GwtAtmosphereResourceImpl(resource, this, requestHeartbeat);
            request.setAttribute(AtmosphereGwtHandler.class.getName() + ".connectionID", 
                    (Integer) resourceWrapper.getConnectionID());
            doCometImpl(resourceWrapper);
        } catch (IOException e) {
//            GwtAtmosphereResourceImpl resource = new GwtAtmosphereResourceImpl(atm, this, -1);
            logger.error("Unable to initiated comet" + e.getMessage(), e);
//			resource.getResponseWriter().sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    /// --- server message handlers

    protected void doServerMessage(HttpServletRequest request, HttpServletResponse response, int connectionID)
        throws IOException{
        BufferedReader data = request.getReader();
        List<Serializable> postMessages = new ArrayList<Serializable>();
        GwtAtmosphereResource resource = lookupResource(connectionID);
        if (resource == null) {
            return;
        }
        String mode = resource.getRequest().getParameter(Constants.CLIENT_SERIALZE_MODE_PARAMETER);
        SerialMode serialMode;
        if (mode != null) {
            serialMode = SerialMode.valueOf(mode);
        } else {
            serialMode = SerialMode.RPC;
        }
        
        try {
            while (true) {
                String event = data.readLine();
                if (event == null) {
                    break;
                }
                String action = data.readLine();
                
                if (logger.isTraceEnabled()) {
                    logger.trace("[" + connectionID + "] Server message received: " + event + ";" + action);
                }
                if (event.equals("o")) {
                    int length = Integer.parseInt(data.readLine());
                    char[] messageData = new char[length];
                    if (data.read(messageData, 0, length) != length) {
                        throw new IllegalStateException("Corrupt message received");
                    }
                    if (action.equals("p")) {
                        Serializable message = deserialize(messageData, serialMode);
                        if (message != null) {
                            postMessages.add(message);
                        }
                    } else if (action.equals("b")) {
                        Serializable message = deserialize(messageData, serialMode);
                        broadcast(message, resource);
                    }

                } else if (event.equals("s")) {
                    int length = Integer.parseInt(data.readLine());
                    char[] messageData = new char[length];
                    if (data.read(messageData, 0, length) != length) {
                        throw new IllegalStateException("Corrupt message received");
                    }
                    if (action.equals("p")) {
                        postMessages.add(String.copyValueOf(messageData));
                    } else if (action.equals("b")) {
                        broadcast(String.copyValueOf(messageData), resource);
                    }

                } else if (event.equals("c")) {
                    if (action.equals("d")) {
                        disconnect(resource);
                    }
                }
            }
        } catch (IOException ex) {
            logger.error("[" + connectionID + "] Failed to read", ex);
        }

        if (postMessages.size() > 0) {
            post(request, response, postMessages, resource);
        }
    }
//    protected void writePostResponse(HttpServletRequest request,
//            HttpServletResponse response, ServletContext context, String responsePayload) throws IOException {
//        boolean gzipEncode = RPCServletUtils.acceptsGzipEncoding(request)
//                && shouldCompressResponse(request, response, responsePayload);
//
//        RPCServletUtils.writeResponse(context, response,
//                responsePayload, gzipEncode);
//    }

    protected Serializable deserialize(char[] data, SerialMode mode) {
        return deserialize(String.copyValueOf(data), mode);
    }
    protected Serializable deserialize(String data, SerialMode mode) {
        switch (mode) {
            default:
            case RPC:
                try {
                    ServerSerializationStreamReader reader = new ServerSerializationStreamReader(getClass().getClassLoader(), cometSerializationPolicyProvider);
                    reader.prepareToRead(data);
                    return (Serializable) reader.readObject();
                } catch (SerializationException ex) {
                    logger.error("Failed to deserialize message", ex);
                    return null;
                }
            case DE_RPC:
            case JSON:
                throw new UnsupportedOperationException("Not implemented");
                
            case PLAIN:
                return data;
        }
    }
//
//    protected String serialize(Serializable message) throws SerializationException {
//        ServerSerializationStreamWriter streamWriter = new ServerSerializationStreamWriter(RPCUtil.createSimpleSerializationPolicy());
//        streamWriter.prepareToWrite();
//        streamWriter.writeObject(message);
//        return streamWriter.toString();
//	}

    final public void post(HttpServletRequest postRequest, HttpServletResponse postResponse,
            List<Serializable> messages, GwtAtmosphereResource cometResource) {
        if (messages == null) {
            return;
        }
        doPost(postRequest, postResponse, messages, cometResource);
    }

    public void broadcast(Serializable message, GwtAtmosphereResource resource) {
        if (message == null) {
            return;
        }
        resource.getBroadcaster().broadcast(message);
    }

    public void broadcast(List<Serializable> messages, GwtAtmosphereResource resource) {
        if (messages == null) {
            return;
        }
        resource.getBroadcaster().broadcast(messages);
    }

    public void disconnect(GwtAtmosphereResource resource) {
        if (resource != null) {
            logger.debug("Resuming connection[" + resource.getConnectionID() + "] after client disconnect message");
            resource.getAtmosphereResource().resume();
        }
    }

    /// --- end server message handlers

    /**
     * Execute a task in a seperate thread, the thread pool will grow and shrink depending on demand
     *
     * @param command
     */
    @Override
    public void execute(Runnable command) {
        executorService.execute(command);
    }

    protected int computeHeartbeat(int requestedHeartbeat) {
        return requestedHeartbeat < heartbeat ? heartbeat : requestedHeartbeat;
    }

    private void doCometImpl(GwtAtmosphereResourceImpl resource) throws IOException {

        try {
            // setup the request
            resource.getWriterImpl().initiate();
            if (resources == null) {
                resources = new ConcurrentHashMap<Integer, GwtAtmosphereResource>(5);
                scheduler.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        reapResources();
                    }
                }, 30, 10, TimeUnit.SECONDS);
            }
            resources.put(resource.getConnectionID(), resource);
        } catch (IOException e) {
            logger.error("Error initiating GwtComet", e);
            return;
        }

        int timeout;
        try {
            // call the application code
            timeout = doComet(resource);
            if (timeout == -1) {
                logger.info("You have set an infinite timeout for your comet connection this is not recommended");
            }
        } catch (ServletException e) {
            logger.error("Error calling doComet()", e);
//			resource.getResponseWriter().sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        } catch (IOException e) {
            logger.error("Error calling doComet()", e);
//			resource.getResponseWriter().sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }

        // at this point the application may have spawned threads to process this response
        // so we have to be careful about concurrency from here on
        resource.suspend(timeout);
    }

    @Override
    public String toString() {
        return "AtmosphereGwtAtmosphereHandler";
    }

}
