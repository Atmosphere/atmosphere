package org.atmosphere.protocol.socketio.transport;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.Factory;

public interface Transport {
	interface InboundFactory {
		SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> getInbound(HttpServletRequest request);
	}

	/**
	 * @return The name of the transport instance.
	 */
	String getName();

	void init(ServletConfig config);
	
	void destroy();

	void handle(AsynchronousProcessor processor, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException;
}
