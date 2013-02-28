package org.atmosphere.samples.chat;

import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple listener for events.
 */
public final class WebSocketEventListener extends WebSocketEventListenerAdapter {

    private final static Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    @Override
    public void onConnect(WebSocketEvent event) {
        logger.debug("{}", event);
    }

    @Override
    public void onDisconnect(WebSocketEvent event) {
        logger.debug("{}", event);
    }
}