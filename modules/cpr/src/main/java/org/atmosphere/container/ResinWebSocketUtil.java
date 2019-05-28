package org.atmosphere.container;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.caucho.websocket.WebSocketServletRequest;

public class ResinWebSocketUtil {
    private static final Logger logger = LoggerFactory.getLogger(ResinWebSocketUtil.class);

    protected static WebSocketServletRequest getWebSocketServletRequest(final ServletRequest request) {
        if (request instanceof WebSocketServletRequest) {
            return (WebSocketServletRequest) request;
        }

        ServletRequest wrapped = request;

        while (wrapped instanceof HttpServletRequestWrapper) {
            wrapped = ((HttpServletRequestWrapper) wrapped).getRequest();

            if (wrapped instanceof WebSocketServletRequest) {
                return (WebSocketServletRequest) wrapped;
            }
        }

        return null;
    }

    public final static Action doService(final AsynchronousProcessor cometSupport, final AtmosphereRequest req, final AtmosphereResponse res, final ResinWebSocketHandler webSocketHandler) throws IOException, ServletException {
        Boolean b = (Boolean) req.getAttribute(WebSocket.WEBSOCKET_INITIATED);
        if (b == null) {
            b = Boolean.FALSE;
        }

        if (!Utils.webSocketEnabled(req) && req.getAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE) == null) {
            if (req.resource() != null && req.resource().transport() == AtmosphereResource.TRANSPORT.WEBSOCKET) {
                WebSocket.notSupported(req, res);
                return Action.CANCELLED;
            } else {
                return null;
            }
        } else {
            if (webSocketHandler != null && !b) {
                req.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
                try {
                    final HttpServletRequest wrappedRequest = req.wrappedRequest();
                    final WebSocketServletRequest webSocketServletRequest = getWebSocketServletRequest(wrappedRequest);
                    if (webSocketServletRequest != null) {
                        webSocketServletRequest.startWebSocket(webSocketHandler);
                    }
                } catch (final IllegalStateException ex) {
                    logger.trace("", ex);
                    WebSocket.notSupported(req, res);
                    return Action.CANCELLED;
                }
                req.setAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE, true);
                return new Action();
            }

            final Action action = cometSupport.suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
            } else if (action.type() == Action.TYPE.RESUME) {
                req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
            }

            return action;
        }
    }

    public final static ResinWebSocketHandler getHandler(final AtmosphereRequest req, final AtmosphereConfig config, final WebSocketProcessor webSocketProcessor) {
        final AtomicBoolean useBuildInSession = new AtomicBoolean(config.isSupportSession());

        {
            final String s = config.getInitParameter(ApplicationConfig.BUILT_IN_SESSION);

            if (s != null) {
                useBuildInSession.set(Boolean.valueOf(s));
            }
        }

        final HttpServletRequest wrapped = req.wrappedRequest();

        if (!webSocketProcessor.handshake(wrapped)) {
            // res.sendError(HttpServletResponse.SC_FORBIDDEN, "WebSocket requests rejected.");
            throw new IllegalStateException();
        }

        return new ResinWebSocketHandler(req, config.framework(), webSocketProcessor);
    }
}
