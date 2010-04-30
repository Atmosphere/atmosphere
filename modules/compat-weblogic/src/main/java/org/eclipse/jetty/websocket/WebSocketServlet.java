package org.eclipse.jetty.websocket;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

/**
 * Fake class for portability across servers.
 */
public abstract class WebSocketServlet extends HttpServlet {
    abstract protected WebSocket doWebSocketConnect(HttpServletRequest request, String protocol);
}
