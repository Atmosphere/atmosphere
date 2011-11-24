package org.atmosphere.websocket;

import javax.servlet.http.HttpServletRequest;

import org.atmosphere.container.JettyWebSocketHandler;
import org.atmosphere.container.JettyWebSocketUtil;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AtmosphereWebSocketFactory {
	
	private static final Logger logger = LoggerFactory.getLogger(AtmosphereWebSocketFactory.class);
	
	private WebSocketFactory webSocketFactory;
	
	private AtmosphereConfig config = null;
	
	public AtmosphereWebSocketFactory(final AtmosphereConfig config) {

		this.config = config;
		
		String[] jettyVersion = config.getServletContext().getServerInfo().substring(6).split("\\.");
        if (Integer.valueOf(jettyVersion[0]) > 7 || Integer.valueOf(jettyVersion[0]) == 7 && Integer.valueOf(jettyVersion[1]) > 4) {
            webSocketFactory = getFactory();
        } else {
            webSocketFactory = null;
        }
        //TODO: Add Grizzly support here as well.

    }

	public WebSocketFactory getFactory() {
    	
    	logger.error("calling from " + JettyWebSocketUtil.class.getName() + " : " + "getFactory");
    	
        WebSocketFactory webSocketFactory = new WebSocketFactory(new WebSocketFactory.Acceptor() {
            public boolean checkOrigin(HttpServletRequest request, String origin) {
                // Allow all origins
                logger.debug("WebSocket-checkOrigin request {} with origin {}", request.getRequestURI(), origin);
                return true;
            }

            public org.eclipse.jetty.websocket.WebSocket doWebSocketConnect(HttpServletRequest request, String protocol) {
                logger.debug("WebSocket-connect request {} with protocol {}", request.getRequestURI(), protocol);
                return new JettyWebSocketHandler(request, config.getServlet(), config.getServlet().getWebSocketProtocolClassName());
            }
        });

        int bufferSize = 8192;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE) != null) {
            bufferSize = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_BUFFER_SIZE));
        }
        logger.info("WebSocket Buffer side {}", bufferSize);

        webSocketFactory.setBufferSize(bufferSize);
        int timeOut = 5 * 60000;
        if (config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME) != null) {
            timeOut = Integer.valueOf(config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME));
        }
        logger.info("WebSocket idle timeout {}", timeOut);

        webSocketFactory.setMaxIdleTime(timeOut);
        return webSocketFactory;
    }
	
}
