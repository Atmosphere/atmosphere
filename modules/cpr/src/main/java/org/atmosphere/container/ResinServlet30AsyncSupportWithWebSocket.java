package org.atmosphere.container;

import java.io.IOException;

import javax.servlet.ServletException;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocketProcessor;

public class ResinServlet30AsyncSupportWithWebSocket extends Servlet30CometSupport {
    public ResinServlet30AsyncSupportWithWebSocket(final AtmosphereConfig config) {
        super(config);
    }

    @Override
    public Action service(final AtmosphereRequest req, final AtmosphereResponse res) throws IOException, ServletException {
        final WebSocketProcessor webSocketProcessor = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config.framework());
        final ResinWebSocketHandler webSocketHandler = ResinWebSocketUtil.getHandler(req, config, webSocketProcessor);
        final Action action = ResinWebSocketUtil.doService(this, req, res, webSocketHandler);

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
