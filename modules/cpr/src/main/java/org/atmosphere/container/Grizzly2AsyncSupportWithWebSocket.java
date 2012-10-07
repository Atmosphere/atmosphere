/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.container;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;
import java.io.IOException;
import javax.servlet.ServletException;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.util.Utils;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Grizzly2AsyncSupportWithWebSocket} - AsynchronousProcessesor that makes use of Grizzly2WebSocketSupport and falls back to
 * Grizzly2CometSupport if websockets aren't supported e.g. a proxy between client and server doesn't understand the connetion upgrade.
 * 
 * @author <a href="mailto:marc.arens@open-xchange.com">Marc Arens</a>
 */
public class Grizzly2AsyncSupportWithWebSocket extends Grizzly2CometSupport {

    private static final Logger LOG = LoggerFactory.getLogger(Grizzly2AsyncSupportWithWebSocket.class);

    public Grizzly2AsyncSupportWithWebSocket(AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        Action action = null;

        // Check if the websocket is already initiated
        Boolean isWebSocketInitiated = (Boolean) req.getAttribute(WebSocket.WEBSOCKET_INITIATED);
        if (isWebSocketInitiated == null) {
            isWebSocketInitiated = Boolean.FALSE;
        }

        // Connection: Upgrade missing and this websocket isn't accepted yet? -> Use CometSupport or report error to client
        if (!Utils.webSocketEnabled(req) && req.getAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE) == null) {
            if (req.resource() != null && req.resource().transport() == AtmosphereResource.TRANSPORT.WEBSOCKET) { // Upgrade missing but
                                                                                                                  // client want websocket
                                                                                                                  // as transport -> error
                LOG.trace("Invalid WebSocket Specification {}", req);
                res.addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
                res.sendError(501, "Websocket protocol not supported");
                return Action.CANCELLED;
            } else { // Use CometSupport as fallback
                return super.service(req, res);
            }
        } else { // Use WebSocketSupport
            if (!isWebSocketInitiated) { // not initiated -> initiate
                req.setAttribute(WebSocket.WEBSOCKET_INITIATED, true);
                Grizzly2WebSocketSupport webSocketSupport = new Grizzly2WebSocketSupport(config);
                // Upgrade the request/response to a WebSocket Connection
                /**
                 *  // First, handshake
                    if (req.getAttribute(WebSocket.WEBSOCKET_SUSPEND) == null) {
                        // Information required to send the server handshake message
                        String key;
                        String subProtocol = null;
            
                        if (!headerContainsToken(req, "Upgrade", "websocket")) {
                            return delegate.doService(req, res);
                        }
            
                        if (!headerContainsToken(req, "Connection", "upgrade")) {
                            return delegate.doService(req, res);
                        }
            
                        if (!headerContainsToken(req, "sec-websocket-version", "13")) {
                            logger.debug("WebSocket version not supported. Downgrading to Comet");
            
                            res.sendError(501, "Websocket protocol not supported");
                            return new Action(Action.TYPE.CANCELLED);
                        }
            
                        key = req.getHeader("Sec-WebSocket-Key");
                        if (key == null) {
                            return delegate.doService(req, res);
                        }
            
                        // If we got this far, all is good. Accept the connection.
                        res.setHeader("Upgrade", "websocket");
                        res.setHeader("Connection", "upgrade");
                        res.setHeader("Sec-WebSocket-Accept", getWebSocketAccept(key));
            
                        if (subProtocol != null) {
                            res.setHeader("Sec-WebSocket-Protocol", subProtocol);
                        }
            
                        HttpServletRequest hsr = req.wrappedRequest();
                        while (hsr instanceof HttpServletRequestWrapper)
                            hsr = (HttpServletRequest) ((HttpServletRequestWrapper) hsr).getRequest();
            
                        RequestFacade facade = (RequestFacade) hsr;
                        boolean isDestroyable = false;
                        String s = config.getInitParameter(ApplicationConfig.RECYCLE_ATMOSPHERE_REQUEST_RESPONSE);
                        if (s != null && Boolean.valueOf(s)) {
                            isDestroyable = true;
                        }
                        StreamInbound inbound = new TomcatWebSocketHandler(AtmosphereRequest.cloneRequest(req, true, config.isSupportSession(), isDestroyable),
                                config.framework(), webSocketProcessor);
                        facade.doUpgrade(inbound);
                        return new Action(Action.TYPE.CREATED);
                    }
                 */
                
                /** webSocketFactory.acceptWebSocket(req, res);
                req.setAttribute(WebSocket.WEBSOCKET_ACCEPT_DONE, true);
                return new Action();
                */
            }

            // Was already initiated, shall we suspend or not
            action = suspended(req, res);
            if (action.type() == Action.TYPE.SUSPEND) {
            } else if (action.type() == Action.TYPE.RESUME) {
                req.setAttribute(WebSocket.WEBSOCKET_RESUME, true);
            }
        }

        return action == null ? super.service(req, res) : action;
    }

    @Override
    public String getContainerName() {
        return config.getServletConfig().getServletContext().getServerInfo() + " with WebSocket enabled.";
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

}
