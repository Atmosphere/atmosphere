package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractHttpTransport extends AbstractTransport {
	
	private static final Logger logger = LoggerFactory.getLogger(AbstractHttpTransport.class);
	
	/**
	 * This is a sane default based on the timeout values of various browsers.
	 */
	public static final long HTTP_REQUEST_TIMEOUT = 30 * 1000;

	/**
	 * The amount of time the session will wait before trying to send a ping. Using a value of half the HTTP_REQUEST_TIMEOUT should be good enough.
	 */
	public static final long HEARTBEAT_DELAY = HTTP_REQUEST_TIMEOUT / 2;

	/**
	 * This specifies how long to wait for a pong (ping response).
	 */
	public static final long HEARTBEAT_TIMEOUT = 10 * 1000;

	/**
	 * For non persistent connection transports, this is the amount of time to wait for messages before returning empty results.
	 */
	public static long REQUEST_TIMEOUT = 20 * 1000;

	protected static final String SESSION_KEY = "com.glines.socketio.server.AbstractHttpTransport.Session";

	public AbstractHttpTransport() {
	}

	protected abstract SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException;

	protected abstract SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException;
	
	@Override
	public void handle(AsynchronousProcessor processor, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException {

		HttpServletRequest request = resource.getRequest();
		HttpServletResponse response = resource.getResponse();
		
		Object obj = request.getAttribute(SESSION_KEY);
		SocketIOSession session = null;
		String sessionId = null;
		if (obj != null) {
			session = (SocketIOSession) obj;
		} else {
			sessionId = extractSessionId(request);
			if (sessionId != null && sessionId.length() > 0) {
				session = sessionFactory.getSession(sessionId);
			}
		}
		
		boolean isDisconnectRequest = isDisconnectRequest(request);
		
		if (session != null) {
			SessionTransportHandler handler = session.getTransportHandler();
			if (handler != null) {
				if(!isDisconnectRequest){
					handler.handle(request, response, session);
				} else {
					handler.disconnect();
					response.setStatus(200);
				}
			} else {
				if(!isDisconnectRequest){
					// on fait un connect
					session = connect(session, resource, atmosphereHandler, sessionFactory);
					if (session == null) {
						response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
					}
				} else {
					response.setStatus(200);
				}
			}
		} else if (sessionId != null && sessionId.length() > 0) {
			logger.error("Session NULL but not sessionId : Soit un mauvais sessionID ou il y a eu un DISCONNECT");
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
		} else {
			if ("GET".equals(request.getMethod())) {
				session = connect(resource, atmosphereHandler, sessionFactory);
				if (session == null) {
					response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				}
			} else {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			}
		}
	}
}
