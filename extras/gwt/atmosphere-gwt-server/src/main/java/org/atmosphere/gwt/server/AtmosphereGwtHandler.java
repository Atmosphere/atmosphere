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



import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServletProcessor;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.gwt.server.impl.GwtAtmosphereResourceImpl;
import org.atmosphere.gwt.server.impl.GwtRpcDeserializer;
import org.atmosphere.gwt.server.spi.JSONSerializerProvider;
import org.atmosphere.gwt.shared.Constants;
import org.atmosphere.gwt.shared.SerialMode;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private boolean escapeText = true;
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private GwtRpcDeserializer gwtRpc;
    private JSONDeserializer jsonSerializer;

    /**
     * This is the main entrypoint on the server that you will want to hook into and override.
     * This method is called when a client has request a new connection. Best practice is to do all your
     * required setup here and tie the AtmosphereResource to a Broadcaster, but do not send anything to
     * the client yet. If you wish to do so it is best to let the client send a notification to the server
     * using the {@link AtmosphereClient#post} method in the onConnected event.
     *
     * @param resource
     * @return
     * @throws ServletException
     * @throws IOException
     */
    public int doComet(GwtAtmosphereResource resource) throws ServletException, IOException {
        Broadcaster broadcaster = BroadcasterFactory.getDefault().lookup(Broadcaster.class, GWT_BROADCASTER_ID);
        if (broadcaster == null) {
            broadcaster = BroadcasterFactory.getDefault().get(DefaultBroadcaster.class, GWT_BROADCASTER_ID);
        }
        resource.getAtmosphereResource().setBroadcaster(broadcaster);
        return NO_TIMEOUT;
    }

    /**
     * When the connection has died this method will be called to let you know about it.
     *
     *
     * @param cometResponse
     * @param serverInitiated
     */
    public void cometTerminated(GwtAtmosphereResource cometResponse, boolean serverInitiated) {
    }

    /**
     * Called when a message is sent from the client using the post method.
     *
     * Default implementation echo's the message back to the client
     *
     * @param messages
     * @param cometResource
     */
    public void doPost(HttpServletRequest postRequest, HttpServletResponse postResponse,
            List<?> messages, GwtAtmosphereResource cometResource) {
        if (cometResource != null) {
            if (messages.size() == 1) {
                cometResource.post(messages.get(0));
            } else {
                cometResource.post(messages);
            }
        }
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
        if (resources == null) {
            return null;
        }
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
    private Map<GwtAtmosphereResource, SerialMode> resourceSerialModeMap;
    private ServletContext context;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        executorService = Executors.newCachedThreadPool();
        String heartbeat = servletConfig.getInitParameter("heartbeat");
        context = servletConfig.getServletContext();
        if (heartbeat != null) {
            this.heartbeat = Integer.parseInt(heartbeat);
        }

        String escText = servletConfig.getInitParameter("escapeText");
        if (escText != null) {
            this.escapeText = Boolean.valueOf(escText);
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

    public boolean isEscapeText() {
        return escapeText;
    }

    public void setEscapeText(boolean escapeText) {
        this.escapeText = escapeText;
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
                    resourceSerialModeMap.remove(resources.remove(resource.getConnectionID()));
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
            int requestHeartbeat = getRequestedHeartbeat(request);
            boolean requestEscapeText = getRequestedEscapeOfText(request);

            GwtAtmosphereResourceImpl resourceWrapper =
                    new GwtAtmosphereResourceImpl(resource, this, requestHeartbeat, requestEscapeText);
            request.setAttribute(AtmosphereGwtHandler.class.getName() + ".connectionID",
                    (Integer) resourceWrapper.getConnectionID());
            doCometImpl(resourceWrapper);
        } catch (IOException e) {
//            GwtAtmosphereResourceImpl resource = new GwtAtmosphereResourceImpl(atm, this, -1);
            logger.error("Unable to initiated comet" + e.getMessage(), e);
//			resource.getResponseWriter().sendError(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    private boolean getRequestedEscapeOfText(HttpServletRequest request) throws IOException {
        boolean requestEscapeText = this.escapeText;
        String requestedEscapeText = request.getParameter("escapeText");
        if (requestedEscapeText != null) {
            requestEscapeText = Boolean.valueOf(requestedEscapeText);
        }
        return requestEscapeText;
    }

    private int getRequestedHeartbeat(HttpServletRequest request) throws IOException {
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
        return requestHeartbeat;
    }

    /// --- server message handlers

    protected void doServerMessage(HttpServletRequest request, HttpServletResponse response, int connectionID)
        throws IOException{
        BufferedReader data = request.getReader();
        List<Object> postMessages = new ArrayList<Object>();
        GwtAtmosphereResource resource = lookupResource(connectionID);
        if (resource == null) {
            return;
        }

        final SerialMode serialMode = this.getSerialMode(resource);

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
                if (event.equals("o") || event.equals("s")) {
                    int length = Integer.parseInt(data.readLine());
                    char[] messageData = new char[length];
                    int totalRead = 0;
                    int read = 0;
                    while ((read = data.read(messageData, totalRead, length - totalRead)) != -1) {
                        totalRead += read;
                        if (totalRead == length) {
                            break;
                        }
                    }
                    if (totalRead != length) {
                        throw new IllegalStateException("Corrupt message received");
                    }
                    Object message = null;
                    if (event.equals("o")) {
                        try {
                            message = deserialize(messageData, serialMode);
                        } catch (SerializationException ex) {
                            logger.error("Failed to deserialize message", ex);
                        }
                    } else {
                        message = String.copyValueOf(messageData);
                    }
                    if (message != null) {
                        if (action.equals("p")) {
                            postMessages.add(message);
                        } else if (action.equals("b")) {
                            broadcast(message, resource);
                        }
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

    private SerialMode getSerialMode(GwtAtmosphereResource resource) {
        SerialMode serialMode = this.resourceSerialModeMap.get(resource);
        if (resource.isAlive()) {
            String mode = resource.getRequest().getParameter(Constants.CLIENT_SERIALZE_MODE_PARAMETER);
            if (mode != null)
                serialMode = SerialMode.valueOf(mode);
        }
        if (serialMode == null)
            serialMode = this.getDefaultSerialMode();

        this.resourceSerialModeMap.put(resource, serialMode);

        return serialMode;
    }

    /**
     * <p>
     * Specifies the default {@link SerialMode} for this {@link org.atmosphere.cpr.AtmosphereHandler}.  This value is used if no
     * serial mode parameter is sent with the suspended request.
     * @return default {@link SerialMode} if not specified in the suspended request's parameter map
     */
    protected SerialMode getDefaultSerialMode() {
        return SerialMode.RPC;
    }

//    protected void writePostResponse(HttpServletRequest request,
//            HttpServletResponse response, ServletContext context, String responsePayload) throws IOException {
//        boolean gzipEncode = RPCServletUtils.acceptsGzipEncoding(request)
//                && shouldCompressResponse(request, response, responsePayload);
//
//        RPCServletUtils.writeResponse(context, response,
//                responsePayload, gzipEncode);
//    }

    protected Object deserialize(char[] data, SerialMode mode) throws SerializationException {
        return deserialize(String.copyValueOf(data), mode);
    }
    protected Object deserialize(String data, SerialMode mode) throws SerializationException {
        switch (mode) {
            default:
            case RPC:
                return getGwtRpc().deserialize(data);

            case JSON:
                return getJSONDeserializer().deserialize(data);
            case PLAIN:
                return data;
        }
    }

    protected GwtRpcDeserializer getGwtRpc() {
        if (gwtRpc == null) {
            gwtRpc = new GwtRpcDeserializer();
        }
        return gwtRpc;
    }

    protected JSONDeserializer getJSONDeserializer() {
        if (jsonSerializer == null) {
            ServiceLoader<JSONSerializerProvider> loader = ServiceLoader.load(JSONSerializerProvider.class,
                    getClass().getClassLoader());
            if (loader != null && loader.iterator().hasNext()) {
                jsonSerializer = loader.iterator().next().getDeserializer();
            }
            if (jsonSerializer == null) {
                jsonSerializer = new JSONDeserializer() {
                    @Override
                    public Object deserialize(String data) {
                        // TODO create better default implementation
                        return data;
                    }
                };
            }
        }
        return jsonSerializer;
    }
//
//    protected String serialize(Serializable message) throws SerializationException {
//        ServerSerializationStreamWriter streamWriter = new ServerSerializationStreamWriter(RPCUtil.createSimpleSerializationPolicy());
//        streamWriter.prepareToWrite();
//        streamWriter.writeObject(message);
//        return streamWriter.toString();
//	}

    final public void post(HttpServletRequest postRequest, HttpServletResponse postResponse,
            List<?> messages, GwtAtmosphereResource cometResource) {
        if (messages == null) {
            return;
        }
        doPost(postRequest, postResponse, messages, cometResource);
    }

    public void broadcast(Object message, GwtAtmosphereResource resource) {
        if (message == null) {
            return;
        }
        resource.getBroadcaster().broadcast(message);
    }

    public void broadcast(List<?> messages, GwtAtmosphereResource resource) {
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
                resourceSerialModeMap = new ConcurrentHashMap<GwtAtmosphereResource, SerialMode>(5);
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

    public void terminate(GwtAtmosphereResource cometResponse, boolean serverInitiated) {
        resourceSerialModeMap.remove(resources.remove(cometResponse.getConnectionID()));
        cometTerminated(cometResponse, serverInitiated);
    }

    @Override
    public String toString() {
        return "AtmosphereGwtAtmosphereHandler";
    }

}
