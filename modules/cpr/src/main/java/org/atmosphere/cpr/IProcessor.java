package org.atmosphere.cpr;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.eclipse.jetty.websocket.WebSocketFactory;

public interface IProcessor {
	
	Action processAction(AsynchronousProcessor processor, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException;
	
	WebSocketFactory getWebSocketFactory();
}
