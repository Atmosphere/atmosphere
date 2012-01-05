package org.atmosphere.protocol.socketio;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;

public interface SocketIOSessionFactory {

	SocketIOSession createSession(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest,HttpServletResponse> handler);
	
	SocketIOSession getSession(String sessionid);
}
