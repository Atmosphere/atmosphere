
package org.atmosphere.gwt.client.impl;

/**
 *
 * @author p.havelaar
 */
public interface WebSocketListener {

    void onOpen(WebSocket socket);
    void onClose(WebSocket socket);
    void onError(WebSocket socket);
    void onMessage(WebSocket socket, String message);

}
