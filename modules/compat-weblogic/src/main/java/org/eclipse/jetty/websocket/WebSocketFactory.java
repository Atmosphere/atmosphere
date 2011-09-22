package org.eclipse.jetty.websocket;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory to create WebSocket connections
 */
public class WebSocketFactory {
    private static final Logger LOG = Log.getLogger(WebSocketFactory.class);

    public interface Acceptor {
        /* ------------------------------------------------------------ */

        /**
         * <p>Factory method that applications needs to implement to return a
         * {@link WebSocket} object.</p>
         *
         * @param request  the incoming HTTP upgrade request
         * @param protocol the websocket sub protocol
         * @return a new {@link WebSocket} object that will handle websocket events.
         */
        WebSocket doWebSocketConnect(HttpServletRequest request, String protocol);

        /* ------------------------------------------------------------ */

        /**
         * <p>Checks the origin of an incoming WebSocket handshake request.</p>
         *
         * @param request the incoming HTTP upgrade request
         * @param origin  the origin URI
         * @return boolean to indicate that the origin is acceptable.
         */
        boolean checkOrigin(HttpServletRequest request, String origin);
    }

    private final Map<String, Class<? extends Extension>> _extensionClasses = new HashMap<String, Class<? extends Extension>>();

    {
    }

    public WebSocketFactory(Acceptor acceptor) {
    }

    public WebSocketFactory(Acceptor acceptor, int bufferSize) {
    }


    /**
     * @return A modifiable map of extension name to extension class
     */
    public Map<String, Class<? extends Extension>> getExtensionClassesMap() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the maxIdleTime.
     *
     * @return the maxIdleTime
     */
    public long getMaxIdleTime() {
        throw new UnsupportedOperationException();
    }

    /**
     * Set the maxIdleTime.
     *
     * @param maxIdleTime the maxIdleTime to set
     */
    public void setMaxIdleTime(int maxIdleTime) {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the bufferSize.
     *
     * @return the bufferSize
     */
    public int getBufferSize() {
        throw new UnsupportedOperationException();
    }

    /**
     * Set the bufferSize.
     *
     * @param bufferSize the bufferSize to set
     */
    public void setBufferSize(int bufferSize) {
        throw new UnsupportedOperationException();

    }

    /**
     * @return The initial maximum text message size (in characters) for a connection
     */
    public int getMaxTextMessageSize() {
        throw new UnsupportedOperationException();
    }

    /**
     * Set the initial maximum text message size for a connection. This can be changed by
     * the application calling {@link WebSocket.Connection#setMaxTextMessageSize(int)}.
     *
     * @param maxTextMessageSize The default maximum text message size (in characters) for a connection
     */
    public void setMaxTextMessageSize(int maxTextMessageSize) {
        throw new UnsupportedOperationException();
    }

    /**
     * @return The initial maximum binary message size (in bytes)  for a connection
     */
    public int getMaxBinaryMessageSize() {
        throw new UnsupportedOperationException();
    }

    /**
     * Set the initial maximum binary message size for a connection. This can be changed by
     * the application calling {@link WebSocket.Connection#setMaxBinaryMessageSize(int)}.
     *
     * @param maxBinaryMessageSize The default maximum binary message size (in bytes) for a connection
     */
    public void setMaxBinaryMessageSize(int maxBinaryMessageSize) {
        throw new UnsupportedOperationException();
    }

    /**
     * Upgrade the request/response to a WebSocket Connection.
     * <p>This method will not normally return, but will instead throw a
     * UpgradeConnectionException, to exit HTTP handling and initiate
     * WebSocket handling of the connection.
     *
     * @param request   The request to upgrade
     * @param response  The response to upgrade
     * @param websocket The websocket handler implementation to use
     * @param protocol  The websocket protocol
     * @throws IOException in case of I/O errors
     */
    public void upgrade(HttpServletRequest request, HttpServletResponse response, WebSocket websocket, String protocol)
            throws IOException {
        throw new UnsupportedOperationException();
    }

    protected String[] parseProtocols(String protocol) {
        throw new UnsupportedOperationException();
    }

    public boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        throw new UnsupportedOperationException();

    }

    public List<Extension> initExtensions(List<String> requested, int maxDataOpcodes, int maxControlOpcodes, int maxReservedBits) {
        throw new UnsupportedOperationException();

    }

    private Extension newExtension(String name) {
        throw new UnsupportedOperationException();
    }


}