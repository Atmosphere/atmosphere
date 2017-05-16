package org.atmosphere.container;

import org.atmosphere.cpr.*;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProcessor;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.ServletWebSocketRequest;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;

abstract class AbstractJetty9AsyncSupportWithWebSocket extends Servlet30CometSupport {

    private static final Logger staticLogger = LoggerFactory.getLogger(AbstractJetty9AsyncSupportWithWebSocket.class);

    private final Logger logger;


    static WebSocketPolicy GetPolicy(final AtmosphereConfig config) {
        String bs = config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE);
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        if (bs != null) {
            policy.setInputBufferSize(Integer.parseInt(bs));
        }

        String max = config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME);
        if (max != null) {
            policy.setIdleTimeout(Integer.parseInt(max));
        }

        try {
            // Crazy Jetty API Incompatibility
            String serverInfo = config.getServletConfig().getServletContext().getServerInfo();
            boolean isJetty91Plus = false;
            if (serverInfo != null) {
                int version = Integer.valueOf(serverInfo.split("/")[1].substring(0, 3).replace(".", ""));
                isJetty91Plus = version > 90;
            }

            max = config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE);
            if (max != null) {
                //policy.setMaxMessageSize(Integer.parseInt(max));
                Method m;
                if (isJetty91Plus) {
                    m = policy.getClass().getMethod("setMaxTextMessageSize", new Class[]{int.class});
                } else {
                    m = policy.getClass().getMethod("setMaxMessageSize", new Class[]{long.class});
                }
                m.invoke(policy, Integer.parseInt(max));
            }

            max = config.getInitParameter(ApplicationConfig.WEBSOCKET_MAXBINARYSIZE);
            if (max != null) {
                //policy.setMaxMessageSize(Integer.parseInt(max));
                Method m;
                if (isJetty91Plus) {
                    m = policy.getClass().getMethod("setMaxBinaryMessageSize", new Class[]{int.class});
                } else {
                    m = policy.getClass().getMethod("setMaxMessageSize", new Class[]{long.class});
                }
                m.invoke(policy, Integer.parseInt(max));
            }
        } catch (Exception ex) {
            staticLogger.warn("", ex);
        }

        return policy;
    }

    AbstractJetty9AsyncSupportWithWebSocket(AtmosphereConfig config, Logger logger) {
        super(config);
        this.logger = logger;
    }

    WebSocketCreator buildCreator(final HttpServletRequest request, final HttpServletResponse response, final WebSocketProcessor webSocketProcessor) {
        return new WebSocketCreator() {

            // @Override  9.0.x
            public Object createWebSocket(UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse) {

                ServletWebSocketRequest r = ServletWebSocketRequest.class.cast(upgradeRequest);
                r.getExtensions().clear();

                if (!webSocketProcessor.handshake(request)) {
                    try {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "WebSocket requests rejected.");
                    } catch (IOException e) {
                        logger.trace("", e);
                    }
                    return null;
                }

                return new Jetty9WebSocketHandler(request, config.framework(), webSocketProcessor);
            }

            // @Override 9.1.x
            public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
                req.getExtensions().clear();

                if (!webSocketProcessor.handshake(request)) {
                    try {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "WebSocket requests rejected.");
                    } catch (IOException e) {
                        logger.trace("", e);
                    }
                    return null;
                }
                return new Jetty9WebSocketHandler(request, config.framework(), webSocketProcessor);
            }
        };
    }

    abstract WebSocketServerFactory getWebSocketFactory();

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res)
            throws IOException, ServletException {

        Action action = null;
        Boolean b = (Boolean) req.getAttribute(WebSocket.WEBSOCKET_INITIATED);
        if (b == null) b = Boolean.FALSE;

        if (!Utils.webSocketEnabled(req) && req.getAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE) == null) {
            if (req.resource() != null && req.resource().transport() == AtmosphereResource.TRANSPORT.WEBSOCKET) {
                WebSocket.notSupported(req, res);
                return Action.CANCELLED;
            } else {
                return super.service(req, res);
            }
        } else {
            if (getWebSocketFactory() != null && !b) {
                req.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
                getWebSocketFactory().acceptWebSocket(req, res);
                req.setAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE, true);
                return new Action();
            }

            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
            } else if (action.type() == Action.TYPE.RESUME) {
                req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
            }
        }

        return action == null ? super.service(req, res) : action;
    }

    /**
     * Return the container's name.
     */
    public String getContainerName() {
        return config.getServletConfig().getServletContext().getServerInfo() + " with WebSocket enabled.";
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

}
